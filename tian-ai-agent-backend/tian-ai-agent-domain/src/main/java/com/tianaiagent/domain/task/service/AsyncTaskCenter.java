package com.tianaiagent.domain.task.service;

import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.domain.task.adapter.repository.TaskRepository;
import com.tianaiagent.domain.task.model.valobj.TaskStatus;
import com.tianaiagent.domain.task.model.valobj.TaskType;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/**
 * 同步任务
 */
@Service
public class AsyncTaskCenter {
    private static final int MAX_EVENTS = 100;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, FutureTask<Void>> running = new ConcurrentHashMap<>();
    private final TaskRepository repository;
    private final Executor executor;  // 执行器
    @jakarta.annotation.Resource private AgentRunAudit agentRunAudit;

    public AsyncTaskCenter(TaskRepository repository, @Qualifier("aiTaskExecutor") Executor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    @PostConstruct
    void loadTasks() throws IOException {
        for (TaskRecord task : repository.loadAll()) {
            if (task.fail("服务重启导致任务中断，请重新提交", Instant.now().toString())) persist(task);
            tasks.put(task.getId(), task);
        }
    }

    public TaskRecord submit(TaskType type, String ownerId, Map<String, Object> input, TaskWork work) {
        String taskId = UUID.randomUUID().toString().replace("-", "");  // 生成UUID
        TaskRecord task = new TaskRecord(taskId, type, normalizeOwner(ownerId), input);  // 创建TaskRecord对象
        persist(task);

        // 将任务包装成 FutureTask ，FutureTask实现了RunnableFuture接口
        // 之所以包装为 FutureTask 是因为我们需要线程池并发执行 → Runnable
        // 还需要在执行该任务的时候操作该任务：查询进度、取消任务或等待结果
        FutureTask<Void> future = new FutureTask<>(() -> {
            if ((type == TaskType.AGENT_WORKFLOW || type == TaskType.WIKI_COMPILE) && agentRunAudit != null) {
                var run = agentRunAudit.start(task.getOwnerId(), type == TaskType.WIKI_COMPILE ? "WIKI_COMPILE" : "WORKFLOW", task.getId());
                try {
                    AgentRunContext.within(run, () -> { runTask(task, work); return null; });
                } finally {
                    String status;
                    synchronized (task) { status = task.getStatus().name(); }
                    run.finish(status);
                }
            } else {
                runTask(task, work);
            }
            return null;
        });
        running.put(taskId, future);
        tasks.put(taskId, task);
        try {
            // 提交给线程池（aiTaskExecutor）异步执行，不会阻塞线程，立即返回
            executor.execute(future);
        } catch (RuntimeException e) {
            running.remove(taskId);
            synchronized (task) {
                if (task.fail("任务队列已满: " + e.getMessage(), Instant.now().toString())) persist(task);
            }
        }
        synchronized (task) { return task.copy(); }
    }

    private void runTask(TaskRecord task, TaskWork work) {
        try {
            synchronized (task) {
                if (!task.start(Instant.now().toString())) return;
                updateInternal(task, 1, "开始执行", "任务已启动");
            }
            Object result = work.execute(new TaskContext(task));
            synchronized (task) {
                if (task.complete(result, Instant.now().toString())) {
                    appendEvent(task, "任务执行完成");
                    persist(task);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markCancelled(task);
        } catch (Exception e) {
            synchronized (task) {
                if (task.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), Instant.now().toString())) {
                    appendEvent(task, "执行失败: " + task.getError());
                    persist(task);
                }
            }
        } finally {
            running.remove(task.getId());
        }
    }

    public TaskRecord get(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        synchronized (task) { return task.copy(); }
    }

    public java.util.Optional<TaskRecord> getOwned(String taskId, String ownerId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null || ownerId == null || !ownerId.equals(task.getOwnerId())) return java.util.Optional.empty();
        synchronized (task) { return java.util.Optional.of(task.copy()); }
    }

    public List<TaskRecord> list(String ownerId, TaskType type) {
        String owner = ownerId == null ? null : normalizeOwner(ownerId);
        return tasks.values().stream()
                .filter(task -> owner == null || owner.equals(task.getOwnerId()))
                .filter(task -> type == null || type == task.getType())
                .sorted(Comparator.comparing(TaskRecord::getCreatedAt).reversed())
                .map(task -> { synchronized (task) { return task.copy(); } })
                .toList();
    }

    public TaskRecord cancel(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        return cancelTask(task);
    }

    public java.util.Optional<TaskRecord> cancelOwned(String taskId, String ownerId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null || ownerId == null || !ownerId.equals(task.getOwnerId())) return java.util.Optional.empty();
        return java.util.Optional.of(cancelTask(task));
    }

    private TaskRecord cancelTask(TaskRecord task) {
        synchronized (task) {
            if (markCancelled(task)) {
                FutureTask<Void> future = running.remove(task.getId());
                if (future != null) future.cancel(true);
            }
            return task.copy();
        }
    }

    private boolean markCancelled(TaskRecord task) {
        synchronized (task) {
            if (!task.cancel(Instant.now().toString())) return false;
            appendEvent(task, "任务已取消");
            persist(task);
            return true;
        }
    }

    private String normalizeOwner(String ownerId) {
        return ownerId == null || ownerId.isBlank() ? "anonymous" : ownerId.trim();
    }

    private void updateInternal(TaskRecord task, int progress, String stage, String event) {
        if (!task.advance(progress, stage)) return;
        if (event != null && !event.isBlank()) appendEvent(task, event);
        persist(task);
    }

    private void appendEvent(TaskRecord task, String event) {
        task.getEvents().add(Instant.now() + " " + event);
        if (task.getEvents().size() > MAX_EVENTS) task.getEvents().remove(0);
    }

    private void persist(TaskRecord task) {
        repository.save(task);
    }

    @FunctionalInterface
    public interface TaskWork {
        Object execute(TaskContext context) throws Exception;
    }

    public final class TaskContext {
        private final TaskRecord task;
        private TaskContext(TaskRecord task) { this.task = task; }
        public String taskId() { return task.getId(); }
        public void update(int progress, String stage, String event) throws InterruptedException {
            synchronized (task) {
                if (Thread.currentThread().isInterrupted() || task.getStatus() != TaskStatus.RUNNING) {
                    throw new InterruptedException("任务已取消");
                }
                updateInternal(task, progress, stage, event);
            }
        }
        public Map<String, Object> input() { return new LinkedHashMap<>(task.getInput()); }
    }
}
