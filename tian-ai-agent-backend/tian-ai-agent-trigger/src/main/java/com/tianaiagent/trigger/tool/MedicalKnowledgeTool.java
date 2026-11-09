package com.tianaiagent.trigger.tool;

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

    @Tool(description = "查询用户当前选择的知识库空间，返回回答和引用片段。检索范围由服务端绑定。")
    public String queryApprovedMedicalKnowledge(
            @ToolParam(description = "需要在当前空间检索的问题，请勿包含个人身份信息") String question,
            @ToolParam(description = "返回证据数量，建议 5 到 8，范围 1 到 20") Integer topK) {
        return knowledgeBaseService.queryWithSources(spaceId, question, topK).toString();
    }

    @Tool(description = "根据知识库召回结果中的chunkId获取4096/1024/256三级父子上下文，用于核对片段语境，不能使用任意自造ID。")
    public String getKnowledgeChunkContext(
            @ToolParam(description = "queryApprovedMedicalKnowledge返回的64位十六进制chunkId") String chunkId) {
        var context = indexing.getChunkContext(spaceId, chunkId);
        return mapper.valueToTree(ApiResponse.ok(Map.of("chunk_id", chunkId, "levels", context.size(), "context", context)))
                .toString();
    }
}
