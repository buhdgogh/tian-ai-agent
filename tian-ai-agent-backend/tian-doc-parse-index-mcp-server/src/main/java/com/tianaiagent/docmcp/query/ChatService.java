package com.tianaiagent.docmcp.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.index.IndexService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {
    private final IndexService indexService;
    private final DocMcpProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final EvidenceAssessmentService evidenceAssessment;

    public ChatService(IndexService indexService, DocMcpProperties properties,
                       HttpClient http, ObjectMapper mapper,
                       EvidenceAssessmentService evidenceAssessment) {
        this.indexService = indexService;
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
        this.evidenceAssessment = evidenceAssessment;
    }

    public Map<String, Object> answer(String question, int topK) {
        IndexService.RetrievalResult retrieval = indexService.retrieve(question, topK);
        List<Map<String, Object>> sources = retrieval.sources();
        EvidenceAssessmentService.Assessment assessment = evidenceAssessment.assess(sources);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", assessment.allowGeneration()
                ? generate(question, sources, assessment) : assessment.response());
        result.put("sources", sources);
        result.put("retrieval_mode", retrieval.mode());
        result.put("evidence_level", assessment.level().name());
        result.put("evidence_reason", assessment.reason());
        result.put("requires_human_review",
                assessment.level() != EvidenceAssessmentService.Level.SUFFICIENT_EVIDENCE);
        return result;
    }

    private String generate(String question, List<Map<String, Object>> sources,
                            EvidenceAssessmentService.Assessment assessment) {
        var chat = properties.getChat();
        if (chat.getApiKey() == null || chat.getApiKey().isBlank()) {
            return "未配置DASHSCOPE_API_KEY，以下为相关原文：\n\n" +
                    sources.stream().limit(3)
                            .map(source -> "[" + source.get("index") + "] " + source.get("text"))
                            .reduce((left, right) -> left + "\n\n" + right).orElse("");
        }
        StringBuilder context = new StringBuilder();
        for (Map<String, Object> source : sources) {
            context.append('[').append(source.get("index")).append("] ")
                    .append(source.get("title")).append('\n')
                    .append(source.get("text")).append("\n\n");
        }
        String system = "你是企业知识库助手。只能根据给出的证据回答；每个关键结论使用[数字]标注来源。"
                + "证据不足时明确说明，不得编造。" + assessment.response();
        String user = "问题：" + question + "\n\n证据：\n" + context;
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
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("DashScope Chat HTTP " + response.statusCode() + ": " + response.body());
            JsonNode root = mapper.readTree(response.body());
            String answer = root.path("output").path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) answer = root.path("output").path("text").asText("");
            if (answer.isBlank()) throw new IllegalStateException("DashScope Chat响应缺少答案");
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("问答请求被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("问答请求失败: " + e.getMessage(), e);
        }
    }
}
