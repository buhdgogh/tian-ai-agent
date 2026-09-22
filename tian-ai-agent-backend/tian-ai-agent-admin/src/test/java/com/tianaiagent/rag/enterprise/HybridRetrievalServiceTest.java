package com.tianaiagent.rag.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final HybridRetrievalService service = new HybridRetrievalService(embeddingModel, new ObjectMapper());
    private final List<String> documentIds = new ArrayList<>();

    @AfterEach
    void cleanup() { documentIds.forEach(service::delete); }

    @Test
    void combinesDenseAndKeywordSignals() throws Exception {
        service.load();
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(this::vectorFor).toList();
        });
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> vectorFor(invocation.getArgument(0)));
        String appleDoc = uniqueId();
        String geologyDoc = uniqueId();
        service.index(appleDoc, "fruit.md", "# 水果\n苹果富含膳食纤维和维生素。\n");
        service.index(geologyDoc, "geology.md", "# 地质\n钻孔揭示地下地层结构。\n");

        List<HybridRetrievalService.Evidence> evidence = service.search("苹果有什么营养", 2);

        assertFalse(evidence.isEmpty());
        assertEquals(appleDoc, evidence.getFirst().documentId());
    }

    private String uniqueId() {
        String id = "test" + UUID.randomUUID().toString().replace("-", "");
        documentIds.add(id);
        return id;
    }

    private float[] vectorFor(String text) {
        return text.contains("苹果") ? new float[]{1f, 0f} : new float[]{0f, 1f};
    }
}
