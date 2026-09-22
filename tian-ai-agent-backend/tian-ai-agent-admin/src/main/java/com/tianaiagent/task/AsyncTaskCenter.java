package com.tianaiagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.constant.FileConstant;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final Path taskDir = Path.of(FileConstant.FILE_SAVE_DIR, "tasks");
    private final ObjectMapper objectMapper;  //
    private final Executor executor;  // 执行器

    public AsyncTaskCenter(ObjectMapper objectMapper, @Qualifier("aiTaskExecutor") Executor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @PostConstruct
    void loadTasks() throws IOException {
        Files.createDirectories(taskDir);
        try (var files = Files.list(taskDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    TaskRecord task = objectMapper.readValue(path.toFile(), TaskRecord.class);
                    if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
                        task.setStatus(TaskStatus.FAILED);
                        task.setError("服务重启导致任务中断，请重新提交");
                        task.setFinishedAt(Instant.now().toString());
                    }
                    tasks.put(task.getId(), task);
                } catch (IOException ignored) {
                    // 单个损坏任务不阻断应用启动
                }
            });
        }
    }

    public TaskRecord submit(TaskType type, String ownerId, Map<String, Object> input, TaskWork work) {
        String taskId = UUID.randomUUID().toString().replace("-", "");  // 生成UUID
        TaskRecord task = new TaskRecord(taskId, type, normalizeOwner(ownerId), input);  // 创建TaskRecord对象
        tasks.put(taskId, task);
        persist(task);

        // 将任务包装成 FutureTask ，FutureTask实现了RunnableFuture接口
        // 之所以包装为 FutureTask 是因为我们需要线程池并发执行 → Runnable
        // 还需要在执行该任务的时候操作该任务：查询进度、取消任务或等待结果
        FutureTask<Void> future = new FutureTask<>(() -> {
            runTask(task, work);
            return null;
        });
        running.put(taskId, future);
        try {
            // 提交给线程池（aiTaskExecutor）异步执行，不会阻塞线程，立即返回
            executor.execute(future);
        } catch (RuntimeException e) {
            running.remove(taskId);
            synchronized (task) {
                task.setStatus(TaskStatus.FAILED);
                task.setError("任务队列已满: " + e.getMessage());
                task.setFinishedAt(Instant.now().toString());
                persist(task);
            }
        }
        return task.copy();
    }

    private void runTask(TaskRecord task, TaskWork work) {
        synchronized (task) {
            if (task.getStatus() == TaskStatus.CANCELLED) return;
            task.setStatus(TaskStatus.RUNNING);
            task.setStartedAt(Instant.now().toString());
            updateInternal(task, 1, "开始执行", "任务已启动");
        }
        try {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markCancelled(task);
        } catch (Exception e) {
            synchronized (task) {
                task.setStatus(TaskStatus.FAILED);
                task.setError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                task.setStage("执行失败");
                task.setFinishedAt(Instant.now().toString());
                appendEvent(task, "执行失败: " + task.getError());
                persist(task);
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
        FutureTask<Void> future = running.get(taskId);
        if (future != null) future.cancel(true);
        markCancelled(task);
        return task.copy();
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
        return ownerId == null || ownerId.isBlank() ? "anonymous" : ownerId.trim();
    }

    private void updateInternal(TaskRecord task, int progress, String stage, String event) {
        task.setProgress(Math.max(task.getProgress(), Math.min(progress, 99)));
        task.setStage(stage);
        if (event != null && !event.isBlank()) appendEvent(task, event);
        persist(task);
    }

    private void appendEvent(TaskRecord task, String event) {
        task.getEvents().add(Instant.now() + " " + event);
        if (task.getEvents().size() > MAX_EVENTS) task.getEvents().remove(0);
    }

    private void persist(TaskRecord task) {
        try {
            Files.createDirectories(taskDir);
            Path target = taskDir.resolve(task.getId() + ".json");
            Path temp = taskDir.resolve(task.getId() + ".json.tmp");
            objectMapper.writeValue(temp.toFile(), task);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("任务状态持久化失败", e);
        }
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
            if (Thread.currentThread().isInterrupted() || task.getStatus() == TaskStatus.CANCELLED) {
                throw new InterruptedException("任务已取消");
            }
            synchronized (task) { updateInternal(task, progress, stage, event); }
        }
        public Map<String, Object> input() { return new LinkedHashMap<>(task.getInput()); }
    }
}
