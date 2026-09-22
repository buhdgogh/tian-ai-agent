package com.tianaiagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.constant.FileConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncTaskCenterTest {
    private String taskId;

    @AfterEach
    void cleanup() throws Exception {
        if (taskId != null) Files.deleteIfExists(Path.of(FileConstant.FILE_SAVE_DIR, "tasks", taskId + ".json"));
    }

    @Test
    void persistsProgressAndCompletion() {
        AsyncTaskCenter center = new AsyncTaskCenter(new ObjectMapper(), Runnable::run);
        TaskRecord submitted = center.submit(TaskType.AGENT_WORKFLOW, "tester", Map.of("key", "value"), context -> {
            context.update(50, "测试阶段", "处理中");
            return Map.of("answer", "ok");
        });
        taskId = submitted.getId();

        TaskRecord completed = center.get(taskId);
        assertEquals(TaskStatus.COMPLETED, completed.getStatus());
        assertEquals(100, completed.getProgress());
        assertEquals("执行完成", completed.getStage());
    }
}
