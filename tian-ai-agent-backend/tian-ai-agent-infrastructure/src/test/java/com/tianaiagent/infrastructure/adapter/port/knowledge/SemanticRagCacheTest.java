package com.tianaiagent.infrastructure.adapter.port.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.repository.AgentRunRepository;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.medical.service.MedicalSafetyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class SemanticRagCacheTest {
    @Test
    void rejectsSemanticallySimilarQuestionWhenCriticalPopulationDiffers() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(values.get(anyString())).thenReturn(null);
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenReturn(new float[]{1, 0});
        ObjectMapper mapper = new ObjectMapper();
        SemanticRagCache.CacheEntry entry = new SemanticRagCache.CacheEntry(
                "孕妇高血压有哪些风险", "PREVENTION", "孕妇", 8, "v1",
                new float[]{1, 0}, "{\"answer\":\"cached\"}");
        when(zsets.reverseRange("rag:cache:semantic:medical", 0, 99))
                .thenReturn(Set.of(mapper.writeValueAsString(entry)));
        SemanticRagCache cache = new SemanticRagCache(redis, mapper, java.util.Optional.of(embedding),
                new MedicalSafetyService(), true, 0.98, 0.1, 100, 12);

        assertTrue(cache.get("medical", "哺乳期高血压有哪些风险", 8, "v1").isEmpty());
    }

    @Test
    void separatesExactAndSemanticLookupsBySpace() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        String first = "a".repeat(32);
        String second = "b".repeat(32);
        when(values.get(startsWith("rag:cache:exact:" + first + ":"))).thenReturn("{\"answer\":\"第一空间\"}");
        var cache = new SemanticRagCache(redis, new ObjectMapper(), java.util.Optional.of(mock(EmbeddingModel.class)),
                new MedicalSafetyService(), true, 0.98, 0.1, 100, 12);

        assertTrue(cache.get(first, "压缩机检修", 8, "v1").isPresent());
        assertTrue(cache.get(second, "压缩机检修", 8, "v1").isEmpty());
        verify(zsets).reverseRange("rag:cache:semantic:" + second, 0, 99);
    }

    @Test
    void recordsCacheOutcomesInTheCurrentRun() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(values.get(anyString())).thenReturn("{\"answer\":\"cached\"}", null, null)
                .thenThrow(new IllegalStateException("Redis unavailable"));
        var mapper = new ObjectMapper();
        var embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenReturn(new float[]{1, 0});
        var entry = new SemanticRagCache.CacheEntry("压缩机检修", "GENERAL", "", 8, "v1",
                new float[]{1, 0}, "{\"answer\":\"cached\"}");
        when(zsets.reverseRange("rag:cache:semantic:medical", 0, 99))
                .thenReturn(Set.of(mapper.writeValueAsString(entry)), Set.of());
        var safety = new MedicalSafetyService();
        var cache = new SemanticRagCache(redis, mapper, Optional.of(embedding), safety, true, 0.98, 0.1, 100, 12);
        var disabled = new SemanticRagCache(redis, mapper, Optional.empty(), safety, false, 0.98, 0.1, 100, 12);
        var repository = mock(AgentRunRepository.class);

        new AgentRunAudit(repository).call("owner", "RAG_CHAT", () -> {
            assertEquals("EXACT", cache.get("medical", "压缩机检修", 8, "v1").orElseThrow().mode().name());
            assertEquals("SEMANTIC", cache.get("medical", "压缩机检修步骤", 8, "v1").orElseThrow().mode().name());
            assertTrue(cache.get("medical", "压缩机检修", 8, "v1").isEmpty());
            assertTrue(cache.get("medical", "我的检查值怎么办", 8, "v1").isEmpty());
            assertTrue(disabled.get("medical", "压缩机检修", 8, "v1").isEmpty());
            assertTrue(cache.get("medical", "压缩机检修", 8, "v1").isEmpty());
            return null;
        });

        var saved = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository).save(saved.capture());
        var run = saved.getValue();
        assertEquals("COMPLETED", run.summary().status());
        assertTrue(run.events().stream().allMatch(event -> "CACHE".equals(event.kind()) && event.elapsedMs() >= 0));
        assertEquals(List.of("HIT:EXACT", "HIT:SEMANTIC", "MISS:null", "SKIPPED:INELIGIBLE",
                        "SKIPPED:DISABLED", "FAILED:CACHE_READ_FAILED"),
                run.events().stream().map(event -> event.status() + ":" + event.reason()).toList());
    }
}
