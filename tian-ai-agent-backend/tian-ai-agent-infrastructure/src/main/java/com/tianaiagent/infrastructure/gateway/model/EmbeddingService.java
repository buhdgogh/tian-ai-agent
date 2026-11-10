package com.tianaiagent.infrastructure.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.types.config.DocMcpProperties;
import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmbeddingService implements TextEmbedding {
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_.+-]+|[\\p{IsHan}]");
    private final DocMcpProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public EmbeddingService(DocMcpProperties properties, HttpClient http, ObjectMapper mapper) {
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
    }

    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if ("dashscope".equalsIgnoreCase(properties.getEmbedding().getProvider())) {
            return embedDashScope(texts, "document");
        }
        return texts.stream().map(this::localEmbedding).toList();
    }

    public float[] embedQuery(String text) {
        if ("dashscope".equalsIgnoreCase(properties.getEmbedding().getProvider())) {
            return embedDashScope(List.of(text), "query").getFirst();
        }
        return localEmbedding(text);
    }

    private List<float[]> embedDashScope(List<String> texts, String textType) {
        var config = properties.getEmbedding();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("EMBEDDING_PROVIDER=dashscope 时必须配置 DASHSCOPE_API_KEY");
        }
        List<float[]> all = new ArrayList<>();
        int batchSize = Math.max(1, config.getBatchSize());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + batchSize));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("input", Map.of("texts", batch));
            body.put("parameters", Map.of("text_type", textType, "dimension", config.getDimensions()));
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.getEndpoint()))
                        .timeout(Duration.ofMinutes(2))
                        .header("Authorization", "Bearer " + config.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("DashScope Embedding HTTP " + response.statusCode() + ": " + response.body());
                }
                JsonNode root = mapper.readTree(response.body());
                JsonNode embeddings = root.path("output").path("embeddings");
                if (!embeddings.isArray() || embeddings.size() != batch.size()) {
                    throw new IllegalStateException("DashScope Embedding 响应数量不匹配: " + response.body());
                }
                for (JsonNode item : embeddings) {
                    JsonNode vector = item.path("embedding");
                    float[] values = new float[vector.size()];
                    for (int i = 0; i < vector.size(); i++) values[i] = (float) vector.get(i).asDouble();
                    all.add(normalize(values));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Embedding 请求被中断", e);
            } catch (Exception e) {
                if (e instanceof IllegalStateException state) throw state;
                throw new IllegalStateException("Embedding 请求失败: " + e.getMessage(), e);
            }
        }
        return all;
    }

    private float[] localEmbedding(String text) {
        int dimensions = Math.max(64, properties.getEmbedding().getDimensions());
        float[] vector = new float[dimensions];
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        String previous = null;
        while (matcher.find()) {
            String token = matcher.group();
            addFeature(vector, token, 1.0f);
            if (previous != null) addFeature(vector, previous + "\u0001" + token, 0.6f);
            previous = token;
        }
        return normalize(vector);
    }

    private void addFeature(float[] vector, String feature, float weight) {
        int hash = feature.hashCode();
        int index = Math.floorMod(hash, vector.length);
        vector[index] += ((hash & 1) == 0 ? weight : -weight);
    }

    static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) norm += (double) value * value;
        if (norm == 0) return vector;
        double divisor = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) vector[i] /= divisor;
        return vector;
    }

    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) return 0;
        double value = 0;
        for (int i = 0; i < left.length; i++) value += left[i] * right[i];
        return value;
    }
}
