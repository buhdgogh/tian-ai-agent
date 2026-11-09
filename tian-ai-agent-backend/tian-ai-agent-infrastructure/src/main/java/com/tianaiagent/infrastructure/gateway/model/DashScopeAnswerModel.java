package com.tianaiagent.infrastructure.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.knowledge.adapter.port.AnswerModel;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.types.config.DocMcpProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DashScopeAnswerModel implements AnswerModel {
    private final DocMcpProperties.Chat chat;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public DashScopeAnswerModel(DocMcpProperties properties, HttpClient http, ObjectMapper mapper) {
        this.chat = properties.getChat();
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public Optional<String> generate(String system, String user) {
        if (chat.getApiKey() == null || chat.getApiKey().isBlank()) return Optional.empty();
        Instant started = Instant.now();
        long nanos = System.nanoTime();
        Map<String, Object> payload = Map.of(
                "model", chat.getModel(),
                "input", Map.of("messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user))),
                "parameters", Map.of("result_format", "message", "max_tokens", chat.getMaxOutputTokens()));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(chat.getEndpoint()))
                    .timeout(Duration.ofMinutes(3))
                    .header("Authorization", "Bearer " + chat.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                audit(started, nanos, null, "FAILED", "HTTP_" + response.statusCode());
                throw new ProviderFailure("知识问答模型调用失败 (HTTP_" + response.statusCode() + ")");
            }
            JsonNode root = mapper.readTree(response.body());
            String answer = root.path("output").path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) answer = root.path("output").path("text").asText("");
            if (answer.isBlank()) throw new IllegalStateException("DashScope Chat响应缺少答案");
            audit(started, nanos, root.path("usage"), "SUCCESS", null);
            return Optional.of(answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audit(started, nanos, null, "CANCELLED", "INTERRUPTED");
            throw new IllegalStateException("问答请求被中断");
        } catch (ProviderFailure e) {
            throw e;
        } catch (Exception e) {
            audit(started, nanos, null, "FAILED", "MODEL_ERROR");
            throw new IllegalStateException("知识问答模型调用失败");
        }
    }

    private void audit(Instant started, long nanos, JsonNode usage, String status, String reason) {
        var run = AgentRunContext.current();
        if (run == null) return;
        Integer input = token(usage, "input_tokens");
        Integer output = token(usage, "output_tokens");
        Integer total = token(usage, "total_tokens");
        if (total == null && input != null && output != null) total = input + output;
        run.event(new AgentRun.Event("MODEL", "knowledge-answer", "dashscope", chat.getModel(), status, started,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos), input, output, total, reason));
    }

    private static Integer token(JsonNode usage, String field) {
        return usage != null && usage.path(field).isIntegralNumber() ? usage.path(field).intValue() : null;
    }

    private static final class ProviderFailure extends IllegalStateException {
        ProviderFailure(String message) { super(message); }
    }
}
