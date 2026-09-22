package com.tianaiagent.toolgateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolGatewayTest {
    private final ThreadPoolTaskExecutor executor = createExecutor();
    private final ToolGateway gateway = new ToolGateway(
            List.of("dangerousExternalAction"), 100, 1, 1, 10, executor);

    @AfterEach void close() { executor.shutdown(); }

    private static ThreadPoolTaskExecutor createExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        return executor;
    }

    @Test
    void blocksHighRiskToolBeforeDelegateExecution() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name("dangerousExternalAction")
                .description("a tool forbidden by policy")
                .inputSchema("{}")
                .build();
        when(callback.getToolDefinition()).thenReturn(definition);

        String result = gateway.guard(callback).call("{\"command\":\"whoami\"}");

        assertTrue(result.contains("blocked"));
        assertEquals("BLOCKED", gateway.recentAudits().getFirst().status());
    }
}
