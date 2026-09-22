package com.tianaiagent.docmcp.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingServiceTest {
    @Test
    void localEmbeddingIsDeterministicAndNormalized() {
        DocMcpProperties properties = new DocMcpProperties();
        properties.getEmbedding().setDimensions(128);
        EmbeddingService service = new EmbeddingService(properties, HttpClient.newHttpClient(), new ObjectMapper());

        float[] left = service.embedQuery("花岗岩 地质构造");
        float[] same = service.embedQuery("花岗岩 地质构造");
        float[] other = service.embedQuery("企业财务制度");

        assertEquals(1.0, EmbeddingService.cosine(left, same), 0.00001);
        assertTrue(EmbeddingService.cosine(left, same) > EmbeddingService.cosine(left, other));
    }
}
