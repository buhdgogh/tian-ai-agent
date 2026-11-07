package com.tianaiagent.tools;

import com.tianaiagent.mcp.McpToolClient;
import com.tianaiagent.service.KnowledgeBaseService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import com.tianaiagent.wiki.WikiTraversalGuard;

/** 通过 Java 文档 MCP 查询已经审核且仍有效的医疗知识库证据。 */
public class MedicalKnowledgeTool {
    private final KnowledgeBaseService knowledgeBaseService;
    private final McpToolClient mcpToolClient;
    private final WikiTraversalGuard wikiTraversal;

    public MedicalKnowledgeTool(KnowledgeBaseService knowledgeBaseService, McpToolClient mcpToolClient, WikiTraversalGuard wikiTraversal) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.mcpToolClient = mcpToolClient;
        this.wikiTraversal = wikiTraversal;
    }

    @Tool(description = "查询本地医疗知识库。返回答案、证据片段、发布机构、版本、发布日期、适用人群和审核状态；医疗事实应优先调用此工具。")
    public String queryApprovedMedicalKnowledge(
            @ToolParam(description = "需要检索的医疗问题，不得包含姓名、身份证号、完整住址或病历号") String question,
            @ToolParam(description = "返回证据数量，建议 5 到 8，范围 1 到 20") Integer topK, ToolContext context) {
        var result = knowledgeBaseService.queryWithSources(question, topK);
        wikiTraversal.registerSeeds(result, context);
        return result.toString();
    }

    @Tool(description = "根据知识库召回结果中的chunkId获取4096/1024/256三级父子上下文，用于核对片段语境，不能使用任意自造ID。")
    public String getKnowledgeChunkContext(
            @ToolParam(description = "queryApprovedMedicalKnowledge返回的64位十六进制chunkId") String chunkId) {
        return mcpToolClient.call("get_chunk_context", java.util.Map.of("chunk_id", chunkId));
    }

    @Tool(description = "搜索已审核发布且来源有效的Wiki知识页面，可按关键词和标签查找，返回页面ID供读取；不会搜索草稿。", resultConverter = ToolResultTool.RawJsonResultConverter.class)
    public String searchPublishedWikiPages(
            @ToolParam(description = "知识关键词，不得包含个人身份信息") String query,
            @ToolParam(description = "可选标签，不限制时传空字符串") String tag, ToolContext context) {
        return wikiTraversal.search(query, tag, context);
    }

    @Tool(description = "读取已发布Wiki。你应根据问题和证据缺口判断是否继续沿入链/出链读取，不自动遍历；服务端限制深度、总页数并去重。收到limit_reached或already_visited时复用已有证据，不重复调用。医疗结论仍需核对原文。", resultConverter = ToolResultTool.RawJsonResultConverter.class)
    public String readPublishedWikiPage(
            @ToolParam(description = "搜索、知识库问答或已读页面链接返回的真实页面ID；不要传标题或构造ID") String pageId,
            @ToolParam(description = "跳转来源的已读页面ID；起始页可为空，沿入链或出链继续读取时必须填写", required = false) String fromPageId,
            ToolContext context) {
        return wikiTraversal.read(pageId, fromPageId, context);
    }
}
