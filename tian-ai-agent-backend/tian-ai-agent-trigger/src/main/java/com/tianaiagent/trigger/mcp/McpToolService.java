package com.tianaiagent.trigger.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.api.mcp.ApiResponse;
import com.tianaiagent.domain.knowledge.service.IndexService;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;

import java.util.List;
import java.util.Map;

public class McpToolService {
    private static final Map<String, Object> QUESTION_SCHEMA = schema(
            Map.of("question", Map.of("type", "string"), "space_id", Map.of("type", "string"),
                    "top_k", Map.of("type", "integer", "minimum", 1, "maximum", 20)), List.of("space_id", "question"));
    private static final List<Map<String, Object>> TOOLS = List.of(
            tool("list_knowledge_spaces", "列出可检索的知识库空间", schema(Map.of(), List.of())),
            tool("retrieve_knowledge", "执行向量与关键词混合召回，返回原文证据", QUESTION_SCHEMA),
            tool("get_chunk_context", "根据召回的 Chunk ID 返回父子上下文链",
                    schema(Map.of("chunk_id", Map.of("type", "string"), "space_id", Map.of("type", "string")),
                            List.of("space_id", "chunk_id"))),
            tool("query_knowledge", "基于知识库证据生成带来源回答", QUESTION_SCHEMA),
            tool("knowledge_base_status", "查看指定空间的文档、分块和向量数量",
                    schema(Map.of("space_id", Map.of("type", "string")), List.of("space_id"))));

    private final IndexService indexing;
    private final KnowledgeIndex knowledgeIndex;
    private final ObjectMapper mapper;

    public McpToolService(IndexService indexing, KnowledgeIndex knowledgeIndex, ObjectMapper mapper) {
        this.indexing = indexing;
        this.knowledgeIndex = knowledgeIndex;
        this.mapper = mapper;
    }

    List<Map<String, Object>> tools() {
        return TOOLS;
    }

    boolean supports(String name) {
        return TOOLS.stream().anyMatch(tool -> name.equals(tool.get("name")));
    }

    String callAsText(String name, JsonNode arguments) throws JsonProcessingException {
        return mapper.writeValueAsString(call(name, arguments));
    }

    private ApiResponse<?> call(String name, JsonNode args) {
        try {
            return switch (name) {
                case "list_knowledge_spaces" -> ApiResponse.ok(Map.of("spaces", knowledgeIndex.listSpaces()));
                case "retrieve_knowledge" -> ApiResponse.ok(retrieve(args));
                case "query_knowledge" -> ApiResponse.ok(answer(args));
                case "get_chunk_context" -> {
                    String id = requiredText(args, "chunk_id");
                    var context = indexing.getChunkContext(requiredText(args, "space_id"), id);
                    yield ApiResponse.ok(Map.of("chunk_id", id, "levels", context.size(), "context", context));
                }
                case "knowledge_base_status" -> ApiResponse.ok(indexing.status(requiredText(args, "space_id")));
                default -> ApiResponse.error("tool_not_found", "未知工具");
            };
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("invalid_argument", e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error("internal_error", "知识库工具执行失败");
        }
    }

    private Map<String, Object> retrieve(JsonNode args) {
        var result = knowledgeIndex.retrieve(requiredText(args, "space_id"), requiredText(args, "question"), args.path("top_k").asInt(8));
        return Map.of("question", result.question(), "count", result.sources().size(),
                "retrieval_top_k", result.topK(), "rerank_top_n", result.rerankTopN(), "rerank_model", result.rerankModel(),
                "retrieval_mode", result.mode(), "queries", result.queries(), "sources", result.sources());
    }

    private Map<String, Object> answer(JsonNode args) {
        var result = knowledgeIndex.queryWithSources(requiredText(args, "space_id"), requiredText(args, "question"), args.path("top_k").asInt(8));
        return Map.of("question", result.question(), "answer", result.answer(), "sources", result.sources(),
                "retrieval_mode", result.retrievalMode(), "evidence_level", result.evidenceLevel(),
                "evidence_reason", result.evidenceReason(), "requires_human_review", result.requiresHumanReview());
    }

    private static String requiredText(JsonNode args, String field) {
        String value = args.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return value;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
