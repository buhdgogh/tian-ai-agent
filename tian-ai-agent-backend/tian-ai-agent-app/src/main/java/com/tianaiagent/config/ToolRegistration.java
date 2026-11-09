package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.infrastructure.gateway.medical.MedicalEvidenceClient;
import com.tianaiagent.domain.knowledge.service.IndexService;
import com.tianaiagent.infrastructure.tool.*;
import com.tianaiagent.domain.agent.service.ToolGateway;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.agent.service.McpServerSettingsService;
import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import com.tianaiagent.trigger.tool.MedicalKnowledgeTool;
import com.tianaiagent.domain.agent.adapter.port.AgentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 描述：集中的工具注册类
 * 将Tool注册为可供LLM调用的 ToolCallback 工具类
 */
@Configuration
public class ToolRegistration {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistration.class);
    private static final Set<String> MEDICAL_TOOLS = Set.of(
            "searchPubMed", "getPubMedArticle", "verifyPubMedCitation", "searchClinicalTrials");
    @Value("${search-api.api-key:${SEARCH_API_KEY:}}")
    private String searchApiKey;

    private final ToolGateway toolGateway;
    private final KnowledgeBaseService knowledgeBaseService;
    private final MedicalEvidenceClient medicalEvidenceClient;
    private final ObjectMapper objectMapper;
    private final Optional<IndexService> knowledgeIndex;

    public ToolRegistration(ToolGateway toolGateway, KnowledgeBaseService knowledgeBaseService,
                            MedicalEvidenceClient medicalEvidenceClient, ObjectMapper objectMapper,
                            Optional<IndexService> knowledgeIndex) {
        this.toolGateway = toolGateway;
        this.knowledgeBaseService = knowledgeBaseService;
        this.medicalEvidenceClient = medicalEvidenceClient;
        this.objectMapper = objectMapper;
        this.knowledgeIndex = knowledgeIndex;
    }

    @Bean
    public ToolCallback[] allTools() {
        if (!searchApiKey.isBlank()) log.info("网页搜索凭证已加载");
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        MedicalEvidenceTool medicalEvidenceTool = new MedicalEvidenceTool(medicalEvidenceClient, objectMapper);
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();

        // 将 Tool 注册为 ToolCallback 供 LLM 调用的
        return toolGateway.guard(ToolCallbacks.from(
                webSearchTool,
                webScrapingTool,
                medicalEvidenceTool,
                pdfGenerationTool,
                terminateTool
        ));
    }

    @Bean
    public AgentTools agentTools(Optional<McpServerSettingsService> mcpSettings) {
        ToolCallback[] base = allTools();
        return (spaceId, medical) -> {
            Stream<ToolCallback> tools = Arrays.stream(base)
                    .filter(tool -> medical || !MEDICAL_TOOLS.contains(tool.getToolDefinition().name()));
            if (spaceId != null) {
                knowledgeBaseService.space(spaceId);
                var knowledge = new MedicalKnowledgeTool(knowledgeBaseService, knowledgeIndex.orElseThrow(), objectMapper, spaceId);
                tools = Stream.concat(tools, Arrays.stream(toolGateway.guard(ToolCallbacks.from(knowledge))));
            }
            var run = AgentRunContext.current();
            if (!medical && run != null && mcpSettings.isPresent()) {
                tools = Stream.concat(tools, Arrays.stream(mcpSettings.get().callbacks(run.ownerId())));
            }
            return tools.toArray(ToolCallback[]::new);
        };
    }
}
