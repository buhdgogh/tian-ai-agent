package com.tianaiagent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 将短时间内到达的上传请求聚合为一次 MinerU MCP 批量解析。 */
@Component
@Slf4j
public class MineruParseClient {

    private final McpToolClient mcpToolClient;
    private final ObjectMapper objectMapper;
    private final Object batchLock = new Object();
    private final List<PendingParse> pendingParses = new ArrayList<>();
    private ScheduledExecutorService batchScheduler;
    private ExecutorService batchExecutor;
    private ScheduledFuture<?> scheduledFlush;

    @Value("${knowledge-base.mcp.parse-poll-interval-ms:2000}")
    private long pollIntervalMs;

    @Value("${knowledge-base.mcp.parse-timeout-seconds:1800}")
    private long timeoutSeconds;

    @Value("${knowledge-base.mcp.parse-batch-window-ms:1000}")
    private long batchWindowMs;

    @Value("${knowledge-base.mcp.parse-batch-max-size:20}")
    private int batchMaxSize;

    @Value("${knowledge-base.mcp.parse-batch-concurrency:1}")
    private int batchConcurrency;

    @Value("${knowledge-base.mcp.parse-poll-max-consecutive-failures:5}")
    private int pollMaxConsecutiveFailures;

    @Value("${knowledge-base.mcp.parse-poll-retry-backoff-ms:1000}")
    private long pollRetryBackoffMs;

    public MineruParseClient(McpToolClient mcpToolClient, ObjectMapper objectMapper) {
        this.mcpToolClient = mcpToolClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initializeExecutors() {
        batchScheduler = Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("mineru-batch-window-"));
        batchExecutor = Executors.newFixedThreadPool(
                Math.max(1, batchConcurrency), namedDaemonFactory("mineru-batch-call-"));
    }

    @PreDestroy
    void shutdownExecutors() {
        List<PendingParse> leftovers;
        synchronized (batchLock) {
            if (scheduledFlush != null) scheduledFlush.cancel(false);
            scheduledFlush = null;
            leftovers = new ArrayList<>(pendingParses);
            pendingParses.clear();
        }
        IllegalStateException error = new IllegalStateException("服务正在关闭，MinerU批任务未提交");
        leftovers.forEach(item -> item.future().completeExceptionally(error));
        if (batchScheduler != null) batchScheduler.shutdownNow();
        if (batchExecutor != null) batchExecutor.shutdownNow();
    }

    /**
     * 把本地路径或 OSS 签名 URL 提交给 Java 文档 MCP 服务。文档服务调用 MinerU/Tika 完成解析，
     * 并在 Java 侧完成分块、Embedding 和 H2/Milvus 索引；Admin 轮询任务并接收索引摘要。
     *
     * @param docId         Admin 与文档服务共用的稳定文档 ID
     * @param fileSource    同机本地绝对路径或 OSS 签名 URL
     * @param contentSha256 原始文件内容 SHA-256
     */
    public ParseIndexResult parseAndIndex(String docId, String fileSource, String contentSha256,
                                          Map<String, Object> documentMetadata) {
        if (auditGateway == null) return enqueueParse(docId, fileSource, contentSha256, documentMetadata);
        // A physical batch can serve several tasks. Record each document's logical operation on its
        // original task thread instead of assigning the entire batch to an arbitrary first task.
        return auditGateway.observeOperation("document:parseAndIndex", docId,
                () -> enqueueParse(docId, fileSource, contentSha256, documentMetadata));
    }

    private com.tianaiagent.toolgateway.ToolGateway auditGateway;

    @org.springframework.beans.factory.annotation.Autowired
    void setAuditGateway(com.tianaiagent.toolgateway.ToolGateway gateway) { this.auditGateway = gateway; }

    private ParseIndexResult enqueueParse(String docId, String fileSource, String contentSha256,
                                          Map<String, Object> documentMetadata) {
        PendingParse pending = new PendingParse(
                docId, fileSource, contentSha256, Map.copyOf(documentMetadata),
                System.nanoTime(), new CompletableFuture<>());
        List<PendingParse> ready = null;
        synchronized (batchLock) {
            pendingParses.add(pending);
            if (pendingParses.size() >= Math.max(1, batchMaxSize)) {
                if (scheduledFlush != null) scheduledFlush.cancel(false);
                scheduledFlush = null;
                ready = drainPendingLocked();
            } else if (scheduledFlush == null) {
                scheduledFlush = batchScheduler.schedule(
                        this::flushPending, Math.max(0, batchWindowMs), TimeUnit.MILLISECONDS);
            }
        }
        if (ready != null) dispatchBatch(ready);

        try {
            return pending.future().get(
                    timeoutSeconds + Math.max(5, batchWindowMs / 1000 + 2), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待文档解析/索引时任务被中断", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("等待文档解析/索引超时: docId=" + docId, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("文档解析/索引失败: " + cause.getMessage(), cause);
        }
    }

    private void flushPending() {
        List<PendingParse> batch;
        synchronized (batchLock) {
            scheduledFlush = null;
            batch = drainPendingLocked();
        }
        if (!batch.isEmpty()) dispatchBatch(batch);
    }

    private List<PendingParse> drainPendingLocked() {
        int size = Math.min(pendingParses.size(), Math.max(1, batchMaxSize));
        if (size == 0) return List.of();
        List<PendingParse> batch = new ArrayList<>(pendingParses.subList(0, size));
        pendingParses.subList(0, size).clear();
        if (!pendingParses.isEmpty() && scheduledFlush == null && batchScheduler != null) {
            scheduledFlush = batchScheduler.schedule(
                    this::flushPending, Math.max(0, batchWindowMs), TimeUnit.MILLISECONDS);
        }
        return batch;
    }

    private void dispatchBatch(List<PendingParse> batch) {
        try {
            batchExecutor.execute(() -> executeBatch(batch));
        } catch (RuntimeException e) {
            batch.forEach(item -> item.future().completeExceptionally(e));
        }
    }

    private void executeBatch(List<PendingParse> batch) {
        long started = System.nanoTime();
        try {
            List<String> sources = batch.stream().map(PendingParse::fileSource).toList();
            Map<String, String> hashes = new LinkedHashMap<>();
            Map<String, String> docIds = new LinkedHashMap<>();
            Map<String, Map<String, Object>> documentMetadatas = new LinkedHashMap<>();
            for (PendingParse item : batch) {
                hashes.put(item.fileSource(), item.contentSha256());
                docIds.put(item.fileSource(), item.docId());
                documentMetadatas.put(item.fileSource(), item.documentMetadata());
            }

            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("file_sources", sources);
            arguments.put("content_sha256s", hashes);
            arguments.put("doc_ids", docIds);
            arguments.put("document_metadatas", documentMetadatas);
            arguments.put("auto_index", true);

            JsonNode task = parseTask(mcpToolClient.call(
                    mcpToolClient.getParseBatchTool(), arguments));
            String taskId = requiredText(task, "task_id");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            int consecutivePollFailures = 0;
            while (!isTerminal(task.path("status").asText())) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("等待批量解析/索引超时: taskId=" + taskId);
                }
                Thread.sleep(Math.max(200, pollIntervalMs));
                try {
                    task = parseTask(mcpToolClient.call(
                            mcpToolClient.getParseStatusTool(), Map.of("task_id", taskId)));
                    consecutivePollFailures = 0;
                } catch (RuntimeException pollError) {
                    consecutivePollFailures++;
                    int allowedFailures = Math.max(1, pollMaxConsecutiveFailures);
                    if (consecutivePollFailures >= allowedFailures) {
                        throw new IllegalStateException(
                                "文档 MCP连续不可用，任务状态查询失败: taskId=" + taskId
                                        + ", attempts=" + consecutivePollFailures,
                                pollError);
                    }
                    long backoff = Math.min(30_000L,
                            Math.max(200L, pollRetryBackoffMs) * (1L << Math.min(5, consecutivePollFailures - 1)));
                    log.warn("文档 MCP状态查询暂时失败，将重试: taskId={}, attempt={}/{}, backoffMs={}, cause={}",
                            taskId, consecutivePollFailures, allowedFailures, backoff, pollError.getMessage());
                    Thread.sleep(backoff);
                }
            }
            completeIndividualResults(taskId, task, batch, started);
            log.info("MinerU批任务完成 taskId={}, documents={}, elapsedMs={}",
                    taskId, batch.size(), elapsedMs(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IllegalStateException error = new IllegalStateException(
                    "等待批量解析/索引时任务被中断", e);
            batch.forEach(item -> item.future().completeExceptionally(error));
        } catch (Exception e) {
            log.error("MinerU批量解析/索引失败 documents={}, elapsedMs={}",
                    batch.size(), elapsedMs(started), e);
            batch.forEach(item -> item.future().completeExceptionally(e));
        }
    }

    private void completeIndividualResults(String taskId, JsonNode task, List<PendingParse> batch,
                                           long batchStartedNanos) {
        Map<String, JsonNode> byDocId = new LinkedHashMap<>();
        for (JsonNode item : task.path("items")) {
            byDocId.put(item.path("doc_id").asText(), item);
        }
        Map<String, Object> taskTimings = toMap(task.path("timings"));
        for (PendingParse pending : batch) {
            JsonNode item = byDocId.get(pending.docId());
            if (item == null) {
                pending.future().completeExceptionally(new IllegalStateException(
                        "文档批任务缺少处理结果: docId=" + pending.docId()));
                continue;
            }
            if (!"completed".equals(item.path("status").asText())
                    || !item.path("indexed").asBoolean(false)) {
                String error = item.path("error").asText("");
                pending.future().completeExceptionally(new IllegalStateException(
                        "文档解析/索引失败: " + (error.isBlank() ? taskError(task) : error)));
                continue;
            }
            Map<String, Object> timings = new LinkedHashMap<>();
            timings.put("javaBatchWaitMs", TimeUnit.NANOSECONDS.toMillis(
                    batchStartedNanos - pending.enqueuedNanos()));
            timings.put("pythonTask", taskTimings);
            timings.put("pythonItem", toMap(item.path("timings")));
            pending.future().complete(new ParseIndexResult(
                    taskId, 1, item.path("leaf_node_count").asInt(0), timings));
        }
    }

    boolean isTerminal(String status) {
        return "completed".equals(status)
                || "completed_with_errors".equals(status)
                || "failed".equals(status)
                || "interrupted".equals(status);
    }

    JsonNode parseTask(String json) {
        try {
            JsonNode response = objectMapper.readTree(json);
            if (response == null || !response.isObject()) {
                throw new IllegalStateException("文档任务响应必须是 JSON 对象: " + json);
            }

            // 文档 MCP 接口统一返回 {"ok": true, "data": {...}, "error": null}。
            // 两次调用（提交批任务、查询任务状态）都通过这里解包，后续逻辑继续读取
            // data 中的 task_id、status、items 和 timings。
            if (response.has("ok")) {
                if (!response.path("ok").asBoolean(false)) {
                    JsonNode error = response.get("error");
                    String message = error == null || error.isNull()
                            ? "未知错误"
                            : error.isTextual() ? error.asText() : error.toString();
                    throw new IllegalStateException("文档任务请求失败: " + message);
                }
                JsonNode data = response.get("data");
                if (data == null || !data.isObject()) {
                    throw new IllegalStateException("文档任务成功响应缺少 data 对象: " + response);
                }
                return data;
            }

            // 暂时兼容旧版直接返回任务对象的格式，便于文档服务灰度升级。
            return response;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文档任务响应不是有效 JSON: " + e.getMessage(), e);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("文档任务响应缺少字段 " + field + ": " + node);
        }
        return value;
    }

    private String taskError(JsonNode task) {
        String taskMessage = task.path("error").asText("");
        if (!taskMessage.isBlank()) {
            return taskMessage;
        }
        for (JsonNode item : task.path("items")) {
            String itemMessage = item.path("error").asText("");
            if (!itemMessage.isBlank()) {
                return itemMessage;
            }
        }
        return task.toString();
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record PendingParse(
            String docId,
            String fileSource,
            String contentSha256,
            Map<String, Object> documentMetadata,
            long enqueuedNanos,
            CompletableFuture<ParseIndexResult> future) {
    }

    public record ParseIndexResult(String taskId, int documentCount, int leafNodeCount,
                                   Map<String, Object> timings) {
        public String summary() {
            return "Java 文档 MCP 批量解析索引完成，文档数：" + documentCount
                    + "，叶子节点数：" + leafNodeCount;
        }
    }
}
