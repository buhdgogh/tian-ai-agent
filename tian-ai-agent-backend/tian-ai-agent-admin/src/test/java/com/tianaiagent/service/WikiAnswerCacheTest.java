package com.tianaiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.mcp.LlamaIndexRagClient;
import com.tianaiagent.rag.SemanticRagCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WikiAnswerCacheTest {
    @Test void aPublicationOrInvalidationChangesTheAnswerCacheVersion() throws Exception {
        var client = mock(LlamaIndexRagClient.class); var cache = mock(SemanticRagCache.class);
        var service = mock(KnowledgeBaseService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "llamaIndexRagClient", client);
        ReflectionTestUtils.setField(service, "semanticRagCache", cache);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "nameMap", Map.of());
        ReflectionTestUtils.setField(service, "indexMetaMap", Map.of());
        var mapper = new ObjectMapper();
        when(client.wiki("wiki_status", Map.of())).thenReturn(mapper.readTree("{\"enabled\":true,\"version\":10}"), mapper.readTree("{\"enabled\":true,\"version\":11}"));
        when(cache.get(anyString(), anyInt(), anyString())).thenReturn(Optional.empty());
        when(client.queryWithSources(anyString(), anyInt())).thenReturn(mapper.readTree("{\"answer\":\"回答\",\"sources\":[]}"));
        service.queryWithSources("随访", 5); service.queryWithSources("随访", 5);
        var versions = ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).get(eq("随访"), eq(5), versions.capture());
        assertNotEquals(versions.getAllValues().get(0), versions.getAllValues().get(1));
    }
}
