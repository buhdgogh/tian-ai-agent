package com.tianaiagent.tools;

import com.tianaiagent.mcp.McpToolClient;
import com.tianaiagent.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedWikiToolTest {
    @Test void springToolCallbacksHideExecutionStateAndReturnStructuredGuardDecisions() throws Exception {
        var mcp = mock(McpToolClient.class); var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(mcp.call(eq("wiki_search"), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"pages\":[{\"page_id\":\"A\"}]}}");
        when(mcp.call(eq("wiki_read_note"), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"page_id\":\"A\",\"revision_id\":\"v1\"}}");
        var tools = new MedicalKnowledgeTool(mock(KnowledgeBaseService.class), mcp, new com.tianaiagent.wiki.WikiTraversalGuard(mcp, mapper, 2, 6));
        var callbacks = org.springframework.ai.support.ToolCallbacks.from(tools);
        var read = java.util.Arrays.stream(callbacks).filter(c -> c.getToolDefinition().name().equals("readPublishedWikiPage")).findFirst().orElseThrow();
        var search = java.util.Arrays.stream(callbacks).filter(c -> c.getToolDefinition().name().equals("searchPublishedWikiPages")).findFirst().orElseThrow();
        var schema = mapper.readTree(read.getToolDefinition().inputSchema());
        assertFalse(schema.path("properties").has("context")); assertFalse(schema.path("properties").has("depth"));
        assertTrue(schema.path("properties").has("fromPageId"));
        var context = new org.springframework.ai.chat.model.ToolContext(Map.of(com.tianaiagent.wiki.WikiTraversalState.CONTEXT_KEY, new com.tianaiagent.wiki.WikiTraversalState()));
        search.call("{\"query\":\"随访\",\"tag\":\"\"}", context);
        assertEquals("read", mapper.readTree(read.call("{\"pageId\":\"A\"}", context)).path("status").asText());
        assertEquals("already_visited", mapper.readTree(read.call("{\"pageId\":\"A\"}", context)).path("status").asText());
        verify(mcp, times(1)).call(eq("wiki_read_note"), anyMap());
    }
    @Test void aiFacingToolsAlwaysRequestPublishedContentThroughMcp() {
        var mcp = mock(McpToolClient.class);
        when(mcp.call(eq("wiki_search"), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"pages\":[{\"page_id\":\"topic-1\"}]}}");
        when(mcp.call(eq("wiki_read_note"), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"page_id\":\"topic-1\",\"revision_id\":\"v1\",\"links\":[],\"backlinks\":[]}}");
        var traversal = new com.tianaiagent.wiki.WikiTraversalGuard(mcp, new com.fasterxml.jackson.databind.ObjectMapper(), 2, 6);
        var tools = new MedicalKnowledgeTool(mock(KnowledgeBaseService.class), mcp, traversal);
        var context = new org.springframework.ai.chat.model.ToolContext(Map.of(com.tianaiagent.wiki.WikiTraversalState.CONTEXT_KEY, new com.tianaiagent.wiki.WikiTraversalState()));
        tools.searchPublishedWikiPages("随访", "医疗", context);
        verify(mcp).call("wiki_search", Map.of("query", "随访", "tag", "医疗", "limit", 20, "published_only", true));
        tools.readPublishedWikiPage("topic-1", null, context);
        verify(mcp).call("wiki_read_note", Map.of("page_id", "topic-1", "published_only", true));
        assertThrows(IllegalArgumentException.class, () -> tools.readPublishedWikiPage("", null, context));
    }
}
