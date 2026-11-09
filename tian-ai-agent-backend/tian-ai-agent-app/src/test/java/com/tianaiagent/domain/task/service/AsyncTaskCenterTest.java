package com.tianaiagent.domain.task.service;

import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.domain.task.model.valobj.TaskStatus;
import com.tianaiagent.domain.task.model.valobj.TaskType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.types.storage.FileConstant;
import com.tianaiagent.infrastructure.adapter.repository.task.FileTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskCenterTest {
    private String taskId;

    @AfterEach
    void cleanup() throws Exception {
        if (taskId != null) Files.deleteIfExists(Path.of(FileConstant.FILE_SAVE_DIR, "tasks", taskId + ".json"));
    }

    @Test
    void persistsProgressAndCompletion() {
        AsyncTaskCenter center = new AsyncTaskCenter(new FileTaskRepository(new ObjectMapper()), Runnable::run);
        TaskRecord submitted = center.submit(TaskType.AGENT_WORKFLOW, "tester", Map.of("key", "value"), context -> {
            context.update(50, "测试阶段", "处理中");
            return Map.of("answer", "ok");
        });
        taskId = submitted.getId();

        TaskRecord completed = center.get(taskId);
        assertEquals(TaskStatus.COMPLETED, completed.getStatus());
        assertEquals(100, completed.getProgress());
        assertEquals("执行完成", completed.getStage());
        assertEquals(TaskStatus.COMPLETED, center.cancel(taskId).getStatus());
    }

    @Test
    void cancelledTaskCannotBecomeFailedWhenWorkerWrapsInterrupt() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var started = new CountDownLatch(1);
            var wait = new CountDownLatch(1);
            var center = new AsyncTaskCenter(new FileTaskRepository(new ObjectMapper()), executor);
            taskId = center.submit(TaskType.AGENT_WORKFLOW, "tester", Map.of(), ignored -> {
                started.countDown();
                try { wait.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new IllegalStateException("wrapped", e); }
                return "late result";
            }).getId();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(TaskStatus.CANCELLED, center.cancel(taskId).getStatus());
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(TaskStatus.CANCELLED, center.get(taskId).getStatus());
        } finally {
            executor.shutdownNow();
        }
    }
}
