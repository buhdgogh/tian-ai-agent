package com.tianaiagent.trigger.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.api.mcp.ApiResponse;
import com.tianaiagent.domain.knowledge.service.IndexService;
import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/** 每次请求固定空间，模型只能提供检索问题和片段 ID。 */
public class MedicalKnowledgeTool {
    private final KnowledgeBaseService knowledgeBaseService;
    private final IndexService indexing;
    private final ObjectMapper mapper;
    private final String spaceId;

    public MedicalKnowledgeTool(KnowledgeBaseService knowledgeBaseService, IndexService indexing,
                                 ObjectMapper mapper, String spaceId) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.indexing = indexing;
        this.mapper = mapper;
        this.spaceId = spaceId;
    }

    @Tool(description = "查询用户当前选择的知识库空间，优先用 Wiki 回答，证据不足则检索原文，返回回答及引用。检索范围由服务端绑定。")
    public String queryApprovedMedicalKnowledge(
            @ToolParam(description = "需要在当前空间检索的问题，请勿包含个人身份信息") String question,
            @ToolParam(description = "返回证据数量，建议 5 到 8，范围 1 到 20") Integer topK) {
        return knowledgeBaseService.queryWithSources(spaceId, question, topK).toString();
    }

    @Tool(description = "RAG 检索当前知识库空间的原文片段，返回片段内容、来源、chunkId 和评分，不生成答案。")
    public JsonNode retrieveKnowledge(
            @ToolParam(description = "需要在当前空间检索的问题，请勿包含个人身份信息") String question,
            @ToolParam(description = "返回片段数量，范围 1 到 20，默认 8", required = false) Integer topK) {
        return knowledgeBaseService.retrieve(spaceId, question, topK);
    }

    @Tool(description = "根据知识库召回结果中的chunkId获取4096/1024/256三级父子上下文，用于核对片段语境，不能使用任意自造ID。")
    public String getKnowledgeChunkContext(
            @ToolParam(description = "原文检索返回的64位十六进制chunkId") String chunkId) {
        var context = indexing.getChunkContext(spaceId, chunkId);
        return mapper.valueToTree(ApiResponse.ok(Map.of("chunk_id", chunkId, "levels", context.size(), "context", context)))
                .toString();
    }
}
