package com.tianaiagent.domain.knowledge.service.document;

import com.tianaiagent.domain.knowledge.adapter.port.DocumentIndexer;
import com.tianaiagent.domain.knowledge.model.aggregate.ParseTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 将短时间内到达的上传请求聚合为一次文档解析任务。 */
@Slf4j
public class DocumentIngestionService implements DocumentIndexer {

    private final DocumentParseService parsing;
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

    public DocumentIngestionService(DocumentParseService parsing) {
        this.parsing = parsing;
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
     * 将本地路径或 OSS 签名 URL 交给文档解析服务，等待索引结果。
     *
     * @param docId         Admin 与文档服务共用的稳定文档 ID
     * @param fileSource    同机本地绝对路径或 OSS 签名 URL
     * @param contentSha256 原始文件内容 SHA-256
     */
    public ParseIndexResult parseAndIndex(String spaceId, String docId, String fileSource, String contentSha256,
                                          Map<String, Object> documentMetadata) {
        PendingParse pending = new PendingParse(
                spaceId, docId, fileSource, contentSha256, Map.copyOf(documentMetadata),
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
            Map<String, String> spaceIds = new LinkedHashMap<>();
            Map<String, Map<String, Object>> documentMetadatas = new LinkedHashMap<>();
            for (PendingParse item : batch) {
                hashes.put(item.fileSource(), item.contentSha256());
                docIds.put(item.fileSource(), item.docId());
                spaceIds.put(item.fileSource(), item.spaceId());
                documentMetadatas.put(item.fileSource(), item.documentMetadata());
            }

            ParseTask task = parsing.submit(new DocumentParseService.ParseRequest(
                    sources, hashes, docIds, spaceIds, documentMetadatas, "auto", "vlm",
                    false, true, true, "ch", true));
            String taskId = task.getTaskId();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            int consecutivePollFailures = 0;
            while (!isTerminal(task.getStatus())) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("等待批量解析/索引超时: taskId=" + taskId);
                }
                Thread.sleep(Math.max(200, pollIntervalMs));
                try {
                    task = parsing.get(taskId);
                    consecutivePollFailures = 0;
                } catch (RuntimeException pollError) {
                    consecutivePollFailures++;
                    int allowedFailures = Math.max(1, pollMaxConsecutiveFailures);
                    if (consecutivePollFailures >= allowedFailures) {
                        throw new IllegalStateException(
                                "连续查询文档解析任务失败: taskId=" + taskId
                                        + ", attempts=" + consecutivePollFailures,
                                pollError);
                    }
                    long backoff = Math.min(30_000L,
                            Math.max(200L, pollRetryBackoffMs) * (1L << Math.min(5, consecutivePollFailures - 1)));
                    log.warn("文档解析任务状态查询暂时失败，将重试: taskId={}, attempt={}/{}, backoffMs={}, cause={}",
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

    private void completeIndividualResults(String taskId, ParseTask task, List<PendingParse> batch,
                                           long batchStartedNanos) {
        Map<String, ParseTask.ParseItem> byDocId = new LinkedHashMap<>();
        for (ParseTask.ParseItem item : task.getItems()) byDocId.put(item.getDocId(), item);

        for (PendingParse pending : batch) {
            ParseTask.ParseItem item = byDocId.get(pending.docId());
            if (item == null) {
                pending.future().completeExceptionally(new IllegalStateException(
                        "文档批任务缺少处理结果: docId=" + pending.docId()));
                continue;
            }
            if (!"completed".equals(item.getStatus()) || !item.isIndexed()) {
                String error = item.getError() == null ? task.getError() : item.getError();
                pending.future().completeExceptionally(new IllegalStateException(
                        "文档解析/索引失败: " + (error == null ? task.getStatus() : error)));
                continue;
            }
            Map<String, Object> timings = new LinkedHashMap<>();
            timings.put("javaBatchWaitMs", TimeUnit.NANOSECONDS.toMillis(
                    batchStartedNanos - pending.enqueuedNanos()));
            // 保留已有耗时字段，兼容任务结果的消费者。
            timings.put("pythonTask", task.getTimings());
            timings.put("pythonItem", item.getTimings());
            timings.put("parser", item.getParser());
            timings.put("cleaning", item.getCleaning());
            pending.future().complete(new ParseIndexResult(
                    taskId, 1, item.getLeafNodeCount() == null ? 0 : item.getLeafNodeCount(), timings,
                    "tika-fallback".equals(item.getParser())));
        }
    }

    private boolean isTerminal(String status) {
        return "completed".equals(status) || "completed_with_errors".equals(status)
                || "failed".equals(status) || "interrupted".equals(status);
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
            String spaceId,
            String docId,
            String fileSource,
            String contentSha256,
            Map<String, Object> documentMetadata,
            long enqueuedNanos,
            CompletableFuture<ParseIndexResult> future) {
    }

}
