package com.tianaiagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.observability.ExecutionContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import com.tianaiagent.conversation.ConversationService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import com.tianaiagent.stream.AgentOutput;

/**
 * Persistent asynchronous tasks. This scheduler runs in a single Admin instance.
 */
@Service
public class AsyncTaskCenter {
    private static final int MAX_EVENTS = 100;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> running = new ConcurrentHashMap<>();
    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private final Map<String, AgentOutput> outputs = new ConcurrentHashMap<>();
    private final TaskRepository repository;
    @Value("${agent.persistence.import-legacy-tasks:false}")
    private boolean importLegacyTasks;
    private final ObjectMapper objectMapper;
    private final Executor executor;  // 执行器
    @Autowired(required = false) private ConversationService conversations;

    public AsyncTaskCenter(ObjectMapper objectMapper, @Qualifier("aiTaskExecutor") Executor executor) {
        this(objectMapper, executor, new FileTaskRepository(objectMapper));
    }

    @Autowired
    public AsyncTaskCenter(ObjectMapper objectMapper, @Qualifier("aiTaskExecutor") Executor executor,
                           TaskRepository repository) {
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.repository = repository;
    }

    @PostConstruct
    void loadTasks() throws IOException {
        if (importLegacyTasks && repository instanceof MysqlTaskRepository mysql) {
            for (TaskRecord task : new FileTaskRepository(objectMapper).list(null, null)) {
                mysql.importIfAbsent(task);
            }
        }
        // Single task scheduler deployment. Restarted work is failed, never silently replayed.
        for (TaskRecord task : repository.list(null, null)) {
            if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
                task.setStatus(TaskStatus.FAILED);
                task.setStage("执行失败");
                task.setError("服务重启导致任务中断，请重新提交");
                task.setFinishedAt(Instant.now().toString());
                appendEvent(task, task.getError());
                persist(task);
            }
        }
    }

    public TaskRecord submit(TaskType type, String ownerId, Map<String, Object> input, TaskWork work) {
        String taskId = UUID.randomUUID().toString().replace("-", "");  // 生成UUID
        TaskRecord task = new TaskRecord(taskId, type, normalizeOwner(ownerId), input);  // 创建TaskRecord对象
        persist(task);
        tasks.put(taskId, task);

        var trace = ExecutionContext.current();
        if (trace.output() != null) {
            outputs.put(taskId, trace.output());
            trace.output().event("task", Map.of("taskId", taskId, "status", "PENDING"));
        }
        // Register before scheduling: even a direct executor may complete synchronously.
        CompletableFuture<Void> future = new CompletableFuture<>();
        running.put(taskId, future);
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    if (!future.isCancelled()) runTask(task, work, trace);
                    future.complete(null);
                } catch (Throwable error) { future.completeExceptionally(error); }
                finally { running.remove(taskId, future); }
            }, executor);
        } catch (RuntimeException e) {
            running.remove(taskId);
            synchronized (task) {
                task.setStatus(TaskStatus.FAILED);
                task.setStage("执行失败");
                task.setError("任务队列已满: " + e.getMessage());
                task.setFinishedAt(Instant.now().toString());
                appendEvent(task, task.getError());
                persist(task);
            }
            tasks.remove(taskId);
            future.completeExceptionally(e);
            notifyTerminal(task);
        }
        synchronized (task) { return task.copy(); }
    }

    private void runTask(TaskRecord task, TaskWork work, ExecutionContext.Trace parent) {
        try (var ignored = ExecutionContext.open(new ExecutionContext.Trace(task.getId(), task.getOwnerId(),
                task.getInput().get("chatId") instanceof String chatId ? chatId : null, null, null, null,
                parent.wikiTraversal() == null ? new com.tianaiagent.wiki.WikiTraversalState() : parent.wikiTraversal(), parent.output()))) {
            synchronized (task) {
                if (task.getStatus() == TaskStatus.CANCELLED) return;
                workers.put(task.getId(), Thread.currentThread());
                task.setStatus(TaskStatus.RUNNING);
                task.setStartedAt(Instant.now().toString());
                updateInternal(task, 1, "开始执行", "任务已启动");
            }
            emitProgress(task, "任务已启动");
            Object result = work.execute(new TaskContext(task));
            synchronized (task) {
                if (task.getStatus() != TaskStatus.CANCELLED) {
                    task.setResult(result);
                    task.setStatus(TaskStatus.COMPLETED);
                    task.setProgress(100);
                    task.setStage("执行完成");
                    appendEvent(task, "任务执行完成");
                }
                task.setFinishedAt(Instant.now().toString());
                persist(task);
            }
        } catch (java.util.concurrent.CancellationException e) {
            checkpoint(task);
            markCancelled(task);
        } catch (InterruptedException e) {
            checkpoint(task);
            Thread.currentThread().interrupt();
            markCancelled(task);
        } catch (Exception e) {
            checkpoint(task);
            synchronized (task) {
                if (task.getStatus() == TaskStatus.CANCELLED) return;
                task.setStatus(TaskStatus.FAILED);
                task.setError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                task.setStage("执行失败");
                task.setFinishedAt(Instant.now().toString());
                appendEvent(task, "执行失败: " + task.getError());
                persist(task);
            }
        } finally {
            workers.remove(task.getId());
            tasks.remove(task.getId());
            notifyTerminal(task);
            if (task.getStatus() == TaskStatus.CANCELLED) Thread.interrupted();
        }
    }

    public TaskRecord get(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return repository.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        synchronized (task) { return task.copy(); }
    }

    public List<TaskRecord> list(String ownerId, TaskType type) {
        String owner = ownerId == null ? null : normalizeOwner(ownerId);
        return repository.list(owner, type);
    }

    public TaskRecord cancel(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return get(taskId); // Terminal records cannot be cancelled retroactively.
        Thread worker;
        TaskRecord snapshot;
        synchronized (task) {
            if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED
                    || task.getStatus() == TaskStatus.CANCELLED) return task.copy();
            markCancelled(task);
            CompletableFuture<Void> future = running.remove(taskId);
            if (future != null) future.cancel(true);
            worker = workers.get(taskId);
            if (worker != null) worker.interrupt();
            tasks.remove(taskId);
            snapshot = task.copy();
        }
        // A live worker closes its own stream on exit. Cancellation must not wait on a slow SSE writer.
        if (worker == null) notifyTerminal(snapshot);
        return snapshot;
    }

    private void markCancelled(TaskRecord task) {
        synchronized (task) {
            task.setStatus(TaskStatus.CANCELLED);
            task.setStage("已取消");
            task.setFinishedAt(Instant.now().toString());
            appendEvent(task, "任务已取消");
            persist(task);
        }
    }

    private String normalizeOwner(String ownerId) {
        String value = ownerId == null || ownerId.isBlank() ? "anonymous" : ownerId.trim();
        if (value.length() > 128) throw new IllegalArgumentException("ownerId最长128字符");
        return value;
    }

    private void updateInternal(TaskRecord task, int progress, String stage, String event) {
        task.setProgress(Math.max(task.getProgress(), Math.min(progress, 99)));
        task.setStage(stage);
        if (event != null && !event.isBlank()) appendEvent(task, event);
        persist(task);
    }
    private void emitProgress(TaskRecord task, String event) {
        var sink = AgentOutput.current();
        if (sink != null) sink.event("progress", Map.of("title", task.getStage(), "detail", event == null ? "" : event, "progress", task.getProgress(), "stage", task.getStage()));
    }

    private void appendEvent(TaskRecord task, String event) {
        task.getEvents().add(Instant.now() + " " + event);
        if (task.getEvents().size() > MAX_EVENTS) task.getEvents().remove(0);
    }

    private void persist(TaskRecord task) {
        repository.save(task.copy());
        if (conversations != null) conversations.taskPersisted(task);
    }

    private void notifyTerminal(TaskRecord task) {
        AgentOutput sink = outputs.remove(task.getId());
        if (sink != null) {
            try { sink.terminal(task.copy()); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(AsyncTaskCenter.class).warn("Task finished but stream delivery failed: taskId={}", task.getId()); }
        }
    }
    private void checkpoint(TaskRecord task) {
        AgentOutput sink = outputs.get(task.getId());
        if (sink != null) try { sink.checkpoint(); } catch (RuntimeException ignored) { /* Terminal state is still persisted below. */ }
    }

    @FunctionalInterface
    public interface TaskWork {
        Object execute(TaskContext context) throws Exception;
    }

    public final class TaskContext {
        private final TaskRecord task;
        private TaskContext(TaskRecord task) { this.task = task; }
        public String taskId() { return task.getId(); }
        public boolean isCancelled() { return Thread.currentThread().isInterrupted() || task.getStatus() == TaskStatus.CANCELLED; }
        public void checkCancelled() { if (isCancelled()) throw new java.util.concurrent.CancellationException("任务已取消"); }
        public void update(int progress, String stage, String event) throws InterruptedException {
            if (Thread.currentThread().isInterrupted() || task.getStatus() == TaskStatus.CANCELLED) {
                throw new InterruptedException("任务已取消");
            }
            synchronized (task) { updateInternal(task, progress, stage, event); }
            emitProgress(task, event);
        }
        public Map<String, Object> input() { return new LinkedHashMap<>(task.getInput()); }
    }
}
