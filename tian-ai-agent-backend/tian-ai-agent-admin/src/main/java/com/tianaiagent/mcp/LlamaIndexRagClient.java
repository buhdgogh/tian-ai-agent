package com.tianaiagent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 描述：LlamaIndex 检索客户端（通过 MCP 调用 Python 侧 LlamaIndex 服务）
 *
 * 文档解析和索引由 Python 的 parse_document 异步任务统一完成；本客户端只负责
 * 查询与删除。多层级 Chunk（4096/1024/256）由 Java 文档 MCP 服务配置。
 */
@Component
@Slf4j
public class LlamaIndexRagClient {

    private final McpToolClient mcpToolClient;
    private final ObjectMapper objectMapper;

    public LlamaIndexRagClient(McpToolClient mcpToolClient, ObjectMapper objectMapper) {
        this.mcpToolClient = mcpToolClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 基于知识库索引进行检索增强问答
     *
     * @param question 用户问题
     * @return 基于知识库的回答
     */
    public String query(String question) {
        JsonNode result = queryWithSources(question, 8);
        return result.path("answer").asText("知识库中没有足够信息。");
    }

    /** 执行检索增强生成，返回回答以及实际参与生成的证据片段。 */
    public JsonNode queryWithSources(String question, int topK) {
        String json = mcpToolClient.call(mcpToolClient.getQueryTool(),
                Map.of("question", question, "top_k", topK));
        return parseBusinessData(json, "问答");
    }

    /** 从Java文档MCP召回证据片段。 */
    public JsonNode retrieve(String question, int topK) {
        String json = mcpToolClient.call(mcpToolClient.getRetrieveTool(),
                Map.of("question", question, "top_k", topK));
        return parseBusinessData(json, "检索");
    }

    public JsonNode retrieve(String question, int topK, String retrievalMode) {
        if (retrievalMode == null || retrievalMode.isBlank()) return retrieve(question, topK);
        if (!java.util.List.of("hybrid", "keyword", "dense").contains(retrievalMode)) throw new IllegalArgumentException("非法retrievalMode");
        String json = mcpToolClient.call(mcpToolClient.getRetrieveTool(),
                Map.of("question", question, "top_k", topK, "retrieval_mode", retrievalMode));
        return parseBusinessData(json, "检索");
    }

    public JsonNode wiki(String tool, Map<String, Object> arguments) {
        if (!java.util.Set.of("wiki_status", "wiki_read_note", "wiki_search", "wiki_source", "wiki_compile", "wiki_jobs", "wiki_pages", "wiki_page", "wiki_publish", "wiki_lint", "wiki_events").contains(tool))
            throw new IllegalArgumentException("不支持的Wiki操作");
        return parseBusinessData(mcpToolClient.call(tool, arguments), "Wiki");
    }

    /**
     * 解包 Python 统一响应 {ok,data,error}，并兼容升级前直接返回业务对象的格式。
     */
    private JsonNode parseBusinessData(String json, String operation) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.has("ok")) {
                return root;
            }
            if (!root.path("ok").asBoolean(false)) {
                JsonNode error = root.path("error");
                String message = error.path("message").asText(error.toString());
                if ("Wiki".equals(operation) && "invalid_argument".equals(error.path("code").asText())) throw new IllegalArgumentException(message);
                throw new IllegalStateException("Python " + operation + "失败: " + message);
            }
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                throw new IllegalStateException("Python " + operation + "响应缺少 data: " + root);
            }
            return data;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Python " + operation + "响应不是有效 JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定文档的索引
     *
     * @param docId 文档 id
     */
    public DeleteResult delete(String docId) {
        String json = mcpToolClient.call(mcpToolClient.getDeleteTool(),
                Map.of("doc_id", docId));
        JsonNode data = parseBusinessData(json, "删除文档索引");
        if ("java-document-index".equals(data.path("storage").asText())) {
            if (!data.path("deleted").asBoolean() || !data.path("verified").asBoolean() || data.path("remaining_nodes").asInt(-1) != 0)
                throw new IllegalStateException("Java文档删除未通过校验");
            return new DeleteResult(data.path("doc_id").asText(docId), true, true, 0, 0, 0);
        }
        DeleteResult result = new DeleteResult(
                data.path("doc_id").asText(docId),
                data.path("deleted").asBoolean(false),
                data.path("verified").asBoolean(false),
                data.path("redis_nodes_found").asInt(0),
                data.path("redis_nodes_deleted").asInt(0),
                data.path("redis_nodes_remaining").asInt(-1));
        if (!result.deleted() || !result.verified() || result.redisNodesRemaining() != 0) {
            throw new IllegalStateException("Python 删除文档索引未通过校验: " + data);
        }
        return result;
    }

    public record DeleteResult(String docId, boolean deleted, boolean verified,
                               int redisNodesFound, int redisNodesDeleted,
                               int redisNodesRemaining) {}
}
