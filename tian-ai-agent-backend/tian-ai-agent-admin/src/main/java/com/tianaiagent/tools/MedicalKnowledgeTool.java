package com.tianaiagent.tools;

import com.tianaiagent.mcp.McpToolClient;
import com.tianaiagent.service.KnowledgeBaseService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 通过 Java 文档 MCP 查询已经审核且仍有效的医疗知识库证据。 */
public class MedicalKnowledgeTool {
    private final KnowledgeBaseService knowledgeBaseService;
    private final McpToolClient mcpToolClient;

    public MedicalKnowledgeTool(KnowledgeBaseService knowledgeBaseService, McpToolClient mcpToolClient) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.mcpToolClient = mcpToolClient;
    }

    @Tool(description = "查询本地医疗知识库。返回答案、证据片段、发布机构、版本、发布日期、适用人群和审核状态；医疗事实应优先调用此工具。")
    public String queryApprovedMedicalKnowledge(
            @ToolParam(description = "需要检索的医疗问题，不得包含姓名、身份证号、完整住址或病历号") String question,
            @ToolParam(description = "返回证据数量，建议 5 到 8，范围 1 到 20") Integer topK) {
        return knowledgeBaseService.queryWithSources(question, topK).toString();
    }

    @Tool(description = "根据知识库召回结果中的chunkId获取4096/1024/256三级父子上下文，用于核对片段语境，不能使用任意自造ID。")
    public String getKnowledgeChunkContext(
            @ToolParam(description = "queryApprovedMedicalKnowledge返回的64位十六进制chunkId") String chunkId) {
        return mcpToolClient.call("get_chunk_context", java.util.Map.of("chunk_id", chunkId));
    }
}
