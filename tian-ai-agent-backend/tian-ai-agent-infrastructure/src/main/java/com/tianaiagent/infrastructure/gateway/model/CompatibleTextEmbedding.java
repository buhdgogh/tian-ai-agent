package com.tianaiagent.infrastructure.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.model.valobj.ModelProvider;
import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CompatibleTextEmbedding implements TextEmbedding {
    private static final int BATCH_SIZE = 10;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final ModelProvider provider;
    private final Duration timeout;

    public CompatibleTextEmbedding(HttpClient http, ObjectMapper mapper, ModelProvider provider, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.provider = provider;
        this.timeout = timeout;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            vectors.addAll(embedBatch(texts.subList(from, Math.min(texts.size(), from + BATCH_SIZE))));
        }
        return vectors;
    }

    @Override
    public float[] embedQuery(String text) { return embedBatch(List.of(text)).getFirst(); }

    private List<float[]> embedBatch(List<String> texts) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(provider.baseUrl().replaceFirst("/+$", "") + "/embeddings"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "model", provider.model(), "input", texts, "encoding_format", "float"))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("嵌入接口返回 HTTP " + response.statusCode());
            }
            return readVectors(mapper.readTree(response.body()).path("data"), texts.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("嵌入请求被中断");
        } catch (IOException e) {
            throw new IllegalStateException("无法连接嵌入接口或读取返回结果");
        }
    }

    private static List<float[]> readVectors(JsonNode data, int count) {
        if (!data.isArray() || data.size() != count) throw invalidResponse();
        float[][] vectors = new float[count][];
        for (JsonNode item : data) {
            JsonNode position = item.path("index");
            int index = position.asInt(-1);
            JsonNode vector = item.path("embedding");
            if (!position.isIntegralNumber() || !position.canConvertToInt() || index < 0 || index >= count || vectors[index] != null
                    || !vector.isArray() || vector.isEmpty() || vector.size() > 32768) {
                throw invalidResponse();
            }
            float[] values = new float[vector.size()];
            for (int i = 0; i < values.length; i++) {
                if (!vector.get(i).isNumber() || !Float.isFinite(vector.get(i).floatValue())) throw invalidResponse();
                values[i] = vector.get(i).floatValue();
            }
            vectors[index] = EmbeddingService.normalize(values);
        }
        return Arrays.asList(vectors);
    }

    private static IllegalStateException invalidResponse() {
        return new IllegalStateException("嵌入接口返回了无效的向量");
    }
}
