package com.tianaiagent.domain.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            List.of("dangerousExternalAction"), 100, 1, 1, 10, executor, new ObjectMapper());

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
    void auditsStructuredSearchFailureWithoutChangingToolOutput() throws Exception {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("searchWeb").description("search").inputSchema("{}").build());
        String failure = "{\"ok\":false,\"errorCode\":\"SEARCH_API_HTTP_401\",\"error\":\"invalid key\"}";
        String encoded = new ObjectMapper().writeValueAsString(failure);
        when(callback.call("{}")).thenReturn(encoded);

        assertEquals(encoded, gateway.guard(callback).call("{}"));
        var audit = gateway.recentAudits().getFirst();
        assertEquals("FAILED", audit.status());
        assertEquals("SEARCH_API_HTTP_401", audit.error());
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
