package com.tianaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.service.MedicalSafetyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        when(zsets.reverseRange("rag:cache:semantic", 0, 99))
                .thenReturn(Set.of(mapper.writeValueAsString(entry)));
        SemanticRagCache cache = new SemanticRagCache(redis, mapper, embedding,
                new MedicalSafetyService(), true, 0.98, 0.1, 100, 12);

        assertTrue(cache.get("哺乳期高血压有哪些风险", 8, "v1").isEmpty());
    }
}
