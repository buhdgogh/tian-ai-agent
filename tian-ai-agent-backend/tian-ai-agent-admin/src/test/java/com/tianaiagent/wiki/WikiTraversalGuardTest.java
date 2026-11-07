package com.tianaiagent.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.mcp.McpToolClient;
import com.tianaiagent.observability.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiTraversalGuardTest {
    ObjectMapper mapper = new ObjectMapper();
    McpToolClient mcp;
    WikiTraversalGuard guard;
    ToolContext context;
    Map<String, List<String>> links;
    Map<String, List<String>> backlinks;

    @BeforeEach void setup() {
        mcp = mock(McpToolClient.class); guard = new WikiTraversalGuard(mcp, mapper, 2, 6);
        context = new ToolContext(Map.of(WikiTraversalState.CONTEXT_KEY, new WikiTraversalState()));
        links = new HashMap<>(); backlinks = new HashMap<>();
        when(mcp.call(eq("wiki_read_note"), anyMap())).thenAnswer(call -> {
            String id = (String) ((Map<?, ?>) call.getArgument(1)).get("page_id");
            return mapper.writeValueAsString(Map.of("ok", true, "data", Map.of("page_id", id, "revision_id", "rev-" + id,
                    "title", id, "markdown", "# " + id,
                    "links", links.getOrDefault(id, List.of()).stream().map(to -> Map.of("page_id", to, "resolved", true)).toList(),
                    "backlinks", backlinks.getOrDefault(id, List.of()).stream().map(to -> Map.of("page_id", to)).toList())));
        });
    }
    void seeds(String... ids) throws Exception {
        when(mcp.call(eq("wiki_search"), anyMap())).thenReturn(mapper.writeValueAsString(Map.of("ok", true, "data", Map.of("pages", Arrays.stream(ids).map(id -> Map.of("page_id", id)).toList()))));
        guard.search("query", "", context);
    }
    JsonNode read(String id, String parent) throws Exception { return mapper.readTree(guard.read(id, parent, context)); }

    @Test void depthIsCalculatedFromObservedEdgesAndNoRequestIsSentBeyondTwoHops() throws Exception {
        links.put("A", List.of("B")); links.put("B", List.of("C")); links.put("C", List.of("D")); seeds("A");
        assertEquals(0, read("A", null).at("/traversal/depth").asInt());
        assertEquals(1, read("B", "A").at("/traversal/depth").asInt());
        assertEquals(2, read("C", "B").at("/traversal/depth").asInt());
        assertEquals("max_depth", read("D", "C").path("reason").asText());
        verify(mcp, never()).call("wiki_read_note", Map.of("page_id", "D", "published_only", true));
        assertEquals("invalid_link", read("unrelated", "A").path("reason").asText());
    }

    @Test void pageCountIsGlobalForTheExecutionAndDuplicatesDoNotRefetchOrConsumeBudget() throws Exception {
        guard = new WikiTraversalGuard(mcp, mapper, 2, 2); seeds("A", "B", "C");
        read("A", null);
        var duplicate = read("A", null);
        assertEquals("already_visited", duplicate.path("status").asText()); assertFalse(duplicate.has("data"));
        assertEquals("read", read("B", null).path("status").asText());
        assertEquals("max_pages", read("C", null).path("reason").asText());
        verify(mcp, times(1)).call("wiki_read_note", Map.of("page_id", "A", "published_only", true));
        verify(mcp, times(2)).call(eq("wiki_read_note"), anyMap());
    }

    @Test void backlinkTraversalIsAllowedButCyclesAndPretendingToBeANewRootAreNot() throws Exception {
        backlinks.put("A", List.of("B")); links.put("B", List.of("A")); seeds("A"); read("A", null);
        assertEquals("parent_required", read("B", null).path("reason").asText());
        assertEquals("read", read("B", "A").path("status").asText());
        assertEquals("already_visited", read("A", "B").path("status").asText());
        assertEquals("not_discovered", read("Title alias of A", null).path("reason").asText());
    }

    @Test void laterSearchCannotResetADiscoveredDeepPageToDepthZero() throws Exception {
        links.put("A", List.of("B")); links.put("B", List.of("C")); links.put("C", List.of("D")); seeds("A");
        read("A", null); read("B", "A"); read("C", "B"); seeds("D");
        assertEquals("max_depth", read("D", "C").path("reason").asText());
        assertEquals("parent_required", read("D", null).path("reason").asText());
    }

    @Test void unresolvedOrUnvisitedParentsCannotAuthorizeARead() throws Exception {
        seeds("A");
        assertEquals("invalid_link", read("B", "A").path("reason").asText());
        when(mcp.call("wiki_read_note", Map.of("page_id", "A", "published_only", true))).thenReturn("{\"ok\":true,\"data\":{\"page_id\":\"A\",\"revision_id\":\"v1\",\"links\":[{\"target\":\"B\",\"resolved\":false}],\"backlinks\":[]}}");
        read("A", null); assertEquals("invalid_link", read("B", "A").path("reason").asText());
    }

    @Test void aFailedRemoteReadDoesNotBecomeVisited() throws Exception {
        seeds("A");
        when(mcp.call("wiki_read_note", Map.of("page_id", "A", "published_only", true)))
                .thenReturn("{\"ok\":false,\"error\":{\"message\":\"temporarily unavailable\"}}", "{\"ok\":true,\"data\":{\"page_id\":\"A\",\"revision_id\":\"v1\"}}");
        assertThrows(IllegalStateException.class, () -> read("A", null));
        assertEquals(1, read("A", null).at("/traversal/pages_read").asInt());
    }

    @Test void concurrentReadsShareOneAtomicBudgetAndOnlyFetchEachPageOnce() throws Exception {
        guard = new WikiTraversalGuard(mcp, mapper, 2, 3); seeds("A", "B", "C", "D", "E");
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (String id : List.of("A", "A", "B", "B", "C", "D", "E")) results.add(executor.submit(() -> read(id, null)));
            long accepted = 0;
            for (var result : results) if ("read".equals(result.get(5, TimeUnit.SECONDS).path("status").asText())) accepted++;
            assertEquals(3, accepted);
        }
        verify(mcp, times(3)).call(eq("wiki_read_note"), anyMap());
    }

    @Test void stepReplanningAndToolScopesShareStateButANewTurnStartsFresh() throws Exception {
        guard = new WikiTraversalGuard(mcp, mapper, 2, 1);
        var root = new ExecutionContext.Trace("task-1", "user", "chat-1", null, null, null);
        when(mcp.call(eq("wiki_search"), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"pages\":[{\"page_id\":\"A\"},{\"page_id\":\"B\"}]}}");
        try (var scope = ExecutionContext.open(root)) { guard.search("q", "", null); }
        try (var scope = ExecutionContext.open(root.step("step-1", 1).tool("call-1"))) { guard.read("A", null, null); }
        try (var scope = ExecutionContext.open(root.step("step-2", 2).tool("call-2"))) {
            assertEquals("max_pages", mapper.readTree(guard.read("B", null, null)).path("reason").asText());
            assertEquals("cached", mapper.readTree(guard.read("A", null, null)).path("status").asText());
        }
        try (var scope = ExecutionContext.open(new ExecutionContext.Trace("task-2", "user", "chat-1", null, null, null))) {
            guard.search("q", "", null); assertEquals("read", mapper.readTree(guard.read("A", null, null)).path("status").asText());
        }
    }

    @Test void missingExecutionScopeCannotStartUnlimitedUntrackedReads() throws Exception {
        assertEquals("missing_execution_scope", mapper.readTree(guard.search("q", "", null)).path("reason").asText());
        assertEquals("missing_execution_scope", mapper.readTree(guard.read("A", null, null)).path("reason").asText());
        verifyNoInteractions(mcp);
    }

    @Test void siblingAgentsCanReuseCachedEvidenceOnceWithoutRemoteReadOrExtraPageBudget() throws Exception {
        var state = new WikiTraversalState();
        context = new ToolContext(Map.of(WikiTraversalState.CONTEXT_KEY, state, WikiTraversalState.CONSUMER_KEY, "agent-1"));
        seeds("A"); read("A", null);
        var sibling = new ToolContext(Map.of(WikiTraversalState.CONTEXT_KEY, state, WikiTraversalState.CONSUMER_KEY, "agent-2"));
        var cached = mapper.readTree(guard.read("A", null, sibling));
        assertEquals("cached", cached.path("status").asText()); assertEquals("# A", cached.at("/data/markdown").asText());
        assertEquals(1, cached.at("/traversal/pages_read").asInt());
        assertEquals("already_visited", mapper.readTree(guard.read("A", null, sibling)).path("status").asText());
        verify(mcp, times(1)).call(eq("wiki_read_note"), anyMap());
    }
}
