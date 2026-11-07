package com.tianaiagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;

class LlamaIndexRagClientTest {
    @Test void javaDocumentDeletionDoesNotRequireLegacyRedisFields() {
        when(mcpToolClient.call(any(), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"doc_id\":\"a\",\"deleted\":true,\"verified\":true,\"storage\":\"java-document-index\",\"remaining_nodes\":0}}");
        assertTrue(client.delete("a").verified());
    }

    @Test void wikiPublicationErrorsRemainActionableAndToolNamesAreRestricted() {
        when(mcpToolClient.call(eq("wiki_publish"), anyMap())).thenReturn("{\"ok\":false,\"error\":{\"code\":\"invalid_argument\",\"message\":\"版本已失效\"}}");
        assertThrows(IllegalArgumentException.class, () -> client.wiki("wiki_publish", java.util.Map.of()));
        assertThrows(IllegalArgumentException.class, () -> client.wiki("delete_document", java.util.Map.of()));
    }

    @Test void forwardsIndependentKeywordSearchModeToMcp() {
        when(mcpToolClient.getRetrieveTool()).thenReturn("retrieve_knowledge");
        when(mcpToolClient.call(any(), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"retrieval_mode\":\"milvus-bm25-mmr\",\"sources\":[]}}");
        assertEquals("milvus-bm25-mmr", client.retrieve("HbA1c", 5, "keyword").path("retrieval_mode").asText());
        verify(mcpToolClient).call(eq("retrieve_knowledge"), argThat(args -> args.get("retrieval_mode").equals("keyword") && args.get("question").equals("HbA1c")));
        assertThrows(IllegalArgumentException.class, () -> client.retrieve("HbA1c", 5, "invalid"));
    }

    private final McpToolClient mcpToolClient = mock(McpToolClient.class);
    private final LlamaIndexRagClient client =
            new LlamaIndexRagClient(mcpToolClient, new ObjectMapper());

    @Test
    void unwrapsQueryDataFromUnifiedPythonResponse() {
        when(mcpToolClient.call(any(), anyMap())).thenReturn("""
                {
                  "ok": true,
                  "data": {"answer": "召回成功", "sources": [{"documentId": "doc-1"}]},
                  "error": null
                }
                """);

        JsonNode result = client.queryWithSources("问题", 8);

        assertEquals("召回成功", result.path("answer").asText());
        assertEquals("doc-1", result.path("sources").get(0).path("documentId").asText());
    }

    @Test
    void unwrapsRetrieveDataFromUnifiedPythonResponse() {
        when(mcpToolClient.call(any(), anyMap())).thenReturn("""
                {"ok": true, "data": {"count": 1, "sources": [{"doc_id": "doc-1"}]}, "error": null}
                """);

        JsonNode result = client.retrieve("问题", 8);

        assertEquals(1, result.path("count").asInt());
        assertEquals("doc-1", result.path("sources").get(0).path("doc_id").asText());
    }

    @Test
    void propagatesPythonBusinessError() {
        when(mcpToolClient.call(any(), anyMap())).thenReturn("""
                {"ok": false, "data": null, "error": {"code": "query_failed", "message": "向量查询失败"}}
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.queryWithSources("问题", 8));

        assertTrue(exception.getMessage().contains("向量查询失败"));
    }

    @Test
    void keepsLegacyResponseCompatible() {
        when(mcpToolClient.call(any(), anyMap())).thenReturn("""
                {"answer": "旧协议回答", "sources": []}
                """);

        assertEquals("旧协议回答", client.query("问题"));
    }

    @Test
    void acceptsOnlyVerifiedRedisDeletion() {
        when(mcpToolClient.call(any(), anyMap())).thenReturn("""
                {"ok": true, "data": {"doc_id": "doc-1", "deleted": true,
                 "verified": true, "redis_nodes_found": 3,
                 "redis_nodes_deleted": 3, "redis_nodes_remaining": 0}, "error": null}
                """);

        LlamaIndexRagClient.DeleteResult result = client.delete("doc-1");

        assertTrue(result.verified());
        assertEquals(3, result.redisNodesDeleted());
        assertEquals(0, result.redisNodesRemaining());
    }

    @Test
    void rejectsDeletionWhenRedisStillContainsNodes() {
        when(mcpToolClient.call(any(), anyMap())).thenReturn("""
                {"ok": true, "data": {"doc_id": "doc-1", "deleted": true,
                 "verified": false, "redis_nodes_found": 3,
                 "redis_nodes_deleted": 2, "redis_nodes_remaining": 1}, "error": null}
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.delete("doc-1"));

        assertTrue(exception.getMessage().contains("未通过校验"));
    }
}
