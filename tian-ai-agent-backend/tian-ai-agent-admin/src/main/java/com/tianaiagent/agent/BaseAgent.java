package com.tianaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.tianaiagent.agent.model.AgentState;
import com.tianaiagent.observability.ExecutionContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 描述：抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {
    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    // 对话 ID 和 记忆
    private String chatId;
    private ChatMemory chatMemory;
    private UnaryOperator<List<Message>> historySelector = messages -> messages;
    private Consumer<List<Message>> completionListener;
    private java.util.function.BiConsumer<String, Throwable> streamObserver;
    private java.util.function.BooleanSupplier cancellationRequested = () -> false;

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        // 1、基础校验（检查当前状态是否可用以及用户是否输入了问题）
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;
        
        // 3、加载记忆（使用spring ai提供的memory包中的chatMemory类）
        int historySize = 0;
        if (StrUtil.isNotBlank(chatId) && chatMemory != null) {
            List<Message> savedMessages = chatMemory.get(chatId);
            if (savedMessages != null) {
                this.messageList = new ArrayList<>(historySelector.apply(savedMessages));
                historySize = this.messageList.size();
            }
        }
        
        // 3.1、记录消息上下文（消息综合 = 历史记忆 + 用户提示词）
        messageList.add(new UserMessage(userPrompt));
        // 4、实际运行过程（最大运行多步，将每一步的结果保存，最后返回结果列表）
        List<String> results = new ArrayList<>();
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                checkCancelled();
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 单步执行
                String stepResult = step();
                checkCancelled();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            
            // 5、保存记忆
            if (StrUtil.isNotBlank(chatId) && chatMemory != null) {
                chatMemory.add(chatId, new ArrayList<>(messageList.subList(historySize, messageList.size())));
                notifyCompletion();
            }
            
            return String.join("\n", results);
        } catch (java.util.concurrent.CancellationException cancelled) {
            state = AgentState.FINISHED;
            return "执行错误：本轮已中断";
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 6、清理资源（每轮对话完成最后需要做的内容）
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return SseEmitter 用于发送 SSE 数据
     */
    public SseEmitter runStream(String userPrompt) {
        // 1、基础校验（校验当前模型的状态 --> 状态机机制）
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }

        // 创建一个超时时间较长的 SseEmitter 进行流式输出
        SseEmitter sseEmitter = new SseEmitter(180000L); // 3 分钟超时
        // 2、异步执行代理，更改状态
        this.state = AgentState.RUNNING;

        // 3、加载记忆
        int loadedHistorySize = 0;
        if (StrUtil.isNotBlank(chatId) && chatMemory != null) {
            List<Message> savedMessages = chatMemory.get(chatId);
            if (savedMessages != null) {
                this.messageList = new ArrayList<>(historySelector.apply(savedMessages));
                loadedHistorySize = this.messageList.size();
            }
        }

        // 3.1、记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        final int historySize = loadedHistorySize;
        StringBuffer streamed = new StringBuffer();
        var notified = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<Throwable> finish = error -> {
            if (streamObserver != null && notified.compareAndSet(false, true)) streamObserver.accept(streamed.toString(), error);
        };
        sseEmitter.onTimeout(() -> finish.accept(new java.util.concurrent.TimeoutException("对话流超时")));
        sseEmitter.onError(finish);
        sseEmitter.onCompletion(() -> finish.accept(new IOException("对话流已关闭")));

        // 4、异步执行流式返回
        CompletableFuture.runAsync(ExecutionContext.wrap(() -> {
            try {
                // 执行循环
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    checkCancelled();
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    String stepResult = step();  // 单步执行
                    checkCancelled();
                    if (!streamed.isEmpty()) streamed.append('\n');
                    streamed.append(stepResult);
                    sseEmitter.send(stepResult);  // 每一步都将结果发送给客户端（非流式的是将多步执行完的结果一起返回）
                }
                // 检查是否超出步骤限制
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    sseEmitter.send("Terminated: Reached max steps (" + maxSteps + ")");
                    streamed.append("\nTerminated: Reached max steps (").append(maxSteps).append(")");
                }
                
                // 5、执行完毕保存记忆
                if (StrUtil.isNotBlank(chatId) && chatMemory != null) {
                    chatMemory.add(chatId, new ArrayList<>(messageList.subList(historySize, messageList.size())));
                    notifyCompletion();
                }
                
                // 执行结束，发送完成标志
                finish.accept(null);
                sseEmitter.send("[DONE]");
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent in stream", e);
                finish.accept(e);
                try {
                    sseEmitter.send("执行错误: " + e.getMessage());
                } catch (IOException ex) {
                    log.error("Failed to send error message via SSE", ex);
                }
                sseEmitter.completeWithError(e);
            } finally {
                // 6、清理资源（每轮对话完成需要做什么）
                this.cleanup();
            }
        }));
        return sseEmitter;
    }

    /**
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    protected void checkCancelled() {
        if (Thread.currentThread().isInterrupted() || cancellationRequested.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("本轮已中断");
        }
    }

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    private void notifyCompletion() {
        if (completionListener == null) return;
        try { completionListener.accept(List.copyOf(messageList)); }
        catch (RuntimeException e) { log.warn("会话记忆压缩调度失败: chatId={}", chatId, e); }
    }
}
