package com.tianaiagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MineruParseClientTest {

    private final MineruParseClient client = new MineruParseClient(
            mock(McpToolClient.class), new ObjectMapper());

    @Test
    void unwrapsSuccessfulPythonResponse() {
        JsonNode task = client.parseTask("""
                {
                  "ok": true,
                  "data": {"task_id": "task-1", "status": "queued"},
                  "error": null
                }
                """);

        assertEquals("task-1", task.path("task_id").asText());
        assertEquals("queued", task.path("status").asText());
    }

    @Test
    void rejectsFailedPythonResponse() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parseTask("""
                        {"ok": false, "data": null, "error": "任务提交失败"}
                        """));

        assertTrue(exception.getMessage().contains("任务提交失败"));
    }

    @Test
    void rejectsSuccessfulResponseWithoutDataObject() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parseTask("""
                        {"ok": true, "data": null, "error": null}
                        """));

        assertTrue(exception.getMessage().contains("缺少 data 对象"));
    }

    @Test
    void keepsLegacyTaskResponseCompatible() {
        JsonNode task = client.parseTask("""
                {"task_id": "legacy-task", "status": "queued"}
                """);

        assertEquals("legacy-task", task.path("task_id").asText());
    }

    @Test
    void treatsRestartInterruptedTaskAsTerminal() {
        assertTrue(client.isTerminal("interrupted"));
    }
}
