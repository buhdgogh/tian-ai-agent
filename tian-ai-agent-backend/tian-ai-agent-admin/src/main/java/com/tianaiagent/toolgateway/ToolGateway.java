package com.tianaiagent.toolgateway;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 统一工具执行边界：白/黑名单、参数大小、并发、超时和审计。
 * 高风险工具默认关闭，避免模型因提示词注入获得任意系统权限。
 */
@Service
public class ToolGateway {
    private final Set<String> blockedTools;          // 黑名单集合
    private final int maxArgumentChars;              // 参数大小限制
    private final long timeoutSeconds;               // 超时时间
    private final Semaphore permits;                 // 并发信号量
    private final AsyncTaskExecutor executor;        // 工具执行线程池
    private final ArrayDeque<ToolAuditRecord> audits = new ArrayDeque<>();// 审计记录队列(环形队列，保留最近的500次工具调用记录)
    private final int auditCapacity;                 // 审计容量

    public ToolGateway(
            @Value("${agent.tool-gateway.blocked-tools:}") List<String> blockedTools,
            @Value("${agent.tool-gateway.max-argument-chars:20000}") int maxArgumentChars,
            @Value("${agent.tool-gateway.timeout-seconds:30}") long timeoutSeconds,
            @Value("${agent.tool-gateway.max-concurrency:8}") int maxConcurrency,
            @Value("${agent.tool-gateway.audit-capacity:500}") int auditCapacity,
            @Qualifier("toolGatewayExecutor") AsyncTaskExecutor executor) {
        this.blockedTools = Set.copyOf(blockedTools);
        this.maxArgumentChars = maxArgumentChars;
        this.timeoutSeconds = timeoutSeconds;
        this.permits = new Semaphore(maxConcurrency);
        this.executor = executor;
        this.auditCapacity = auditCapacity;
    }

    /**
     *
     * @param callbacks
     * @return 返回一个包装后的 ToolCallback
     */
    public ToolCallback[] guard(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)          // 1. 把数组转为流
                .map(this::guard)                // 2. 对每个元素调用单参数 guard 方法
                .toArray(ToolCallback[]::new);   // 3. 收集回新数组
    }

    /** 将Tool进行改写封装，主要就是call方法，call方法调用execute方法
     *  execute方法中定义工具的黑白名单校验、参数大小、并发限制、超时控制、审计记录
     *  execute方法只做校验，真实的运行还是ToolCallback类中的call方法
     */
    public ToolCallback guard(ToolCallback delegate) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            @Override public String call(String arguments) {
                return execute(delegate, arguments, () -> delegate.call(arguments));
            }
            @Override public String call(String arguments, ToolContext context) {
                return execute(delegate, arguments, () -> delegate.call(arguments, context));
            }
        };
    }

    /**
     * 工具的执行函数
     * @param callback 被包装的原始工具回调
     * @param arguments 工具调用的参数（JSON 字符串）
     * @param invocation 实际执行工具的 Lambda 表达式
     * @return 工具调用结果
     */
    private String execute(ToolCallback callback, String arguments, Callable<String> invocation) {
        String toolName = callback.getToolDefinition().name();
        long started = System.nanoTime();
        String auditId = UUID.randomUUID().toString().replace("-", "");
        // 根据参数的信息计算sha256，计算工具调用次数，做好审计
        String digest = digest(arguments);

        if (blockedTools.contains(toolName)) {  // 黑名单校验
            audit(auditId, toolName, "BLOCKED", started, digest, "工具未获授权");
            return "Tool blocked by gateway policy: " + toolName;
        }
        if (arguments != null && arguments.length() > maxArgumentChars) {  // 参数大小控制
            audit(auditId, toolName, "REJECTED", started, digest, "参数超过大小限制");
            return "Tool arguments exceed gateway limit";
        }
        boolean acquired = false;
        Future<String> future = null;
        try {  // 并发限制
            acquired = permits.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("工具网关并发已满");
            future = executor.submit(invocation);
            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            audit(auditId, toolName, "SUCCESS", started, digest, null);
            return result;
        } catch (Exception e) {
            if (future != null) future.cancel(true);
            audit(auditId, toolName, "FAILED", started, digest, e.getMessage());
            return "Tool execution failed: " + e.getMessage();
        } finally {
            if (acquired) permits.release();
        }
    }

    /**
     *
     * @return 返回工具调用列表（列表逆置，第一条是最近的一次调用）
     */
    public synchronized List<ToolAuditRecord> recentAudits() {
        return audits.stream().toList().reversed();
    }

    /**
     * 向审计记录中添加工具调用记录
     * @param id
     * @param toolName
     * @param status
     * @param started
     * @param digest
     * @param error
     */
    private synchronized void audit(String id, String toolName, String status, long started,
                                    String digest, String error) {
        audits.addLast(new ToolAuditRecord(id, toolName, status,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), digest, error,
                Instant.now().toString()));
        while (audits.size() > auditCapacity) audits.removeFirst();
    }

    /**
     * 生成简短的工具调用的参数摘要（区分不同的调用、统计相同参数的调用次数）
     * @param value 参数列表
     * @return
     */
    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8);
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
