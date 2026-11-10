package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.infrastructure.gateway.medical.MedicalEvidenceClient;
import com.tianaiagent.domain.knowledge.service.IndexService;
import com.tianaiagent.infrastructure.tool.*;
import com.tianaiagent.domain.agent.service.ToolGateway;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.agent.service.LocalWorkspaceReads;
import com.tianaiagent.domain.agent.service.McpServerSettingsService;
import com.tianaiagent.domain.agent.service.ToolCredentialService;
import com.tianaiagent.domain.agent.model.valobj.PersonalTool;
import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import com.tianaiagent.trigger.tool.MedicalKnowledgeTool;
import com.tianaiagent.domain.agent.adapter.port.AgentTools;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
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
    private static final Set<String> MEDICAL_TOOLS = Set.of(
            "searchPubMed", "getPubMedArticle", "verifyPubMedCitation", "searchClinicalTrials");
    private final ToolGateway toolGateway;
    private final KnowledgeBaseService knowledgeBaseService;
    private final MedicalEvidenceClient medicalEvidenceClient;
    private final ObjectMapper objectMapper;
    private final Optional<IndexService> knowledgeIndex;
    private final ToolCredentialService credentials;

    public ToolRegistration(ToolGateway toolGateway, KnowledgeBaseService knowledgeBaseService,
                            MedicalEvidenceClient medicalEvidenceClient, ObjectMapper objectMapper,
                            Optional<IndexService> knowledgeIndex, ToolCredentialService credentials) {
        this.toolGateway = toolGateway;
        this.knowledgeBaseService = knowledgeBaseService;
        this.medicalEvidenceClient = medicalEvidenceClient;
        this.objectMapper = objectMapper;
        this.knowledgeIndex = knowledgeIndex;
        this.credentials = credentials;
    }

    @Bean
    public ToolCallback[] allTools() {
        WebSearchTool webSearchTool = new WebSearchTool(() -> {
            var run = AgentRunContext.current();
            return credentials.resolve(run == null ? null : run.ownerId(), PersonalTool.SEARCH);
        });
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
    public AgentTools agentTools(Optional<McpServerSettingsService> mcpSettings, LocalWorkspaceReads workspace) {
        ToolCallback[] base = allTools();
        return (spaceId, medical) -> {
            var run = AgentRunContext.current();
            if (run != null && workspace.active(run.id())) {
                var localTools = toolGateway.guard(ToolCallbacks.from(new LocalWorkspaceReadTool(workspace, run.id())));
                var terminate = Arrays.stream(base)
                        .filter(tool -> "doTerminate".equals(tool.getToolDefinition().name()));
                return Stream.concat(Arrays.stream(localTools), terminate).map(AgentTools.RegisteredTool::builtIn).toList();
            }
            Stream<ToolCallback> tools = Arrays.stream(base)
                    .filter(tool -> medical || !MEDICAL_TOOLS.contains(tool.getToolDefinition().name()));
            if (spaceId != null) {
                knowledgeBaseService.space(spaceId);
                var knowledge = new MedicalKnowledgeTool(knowledgeBaseService, knowledgeIndex.orElseThrow(), objectMapper, spaceId);
                tools = Stream.concat(tools, Arrays.stream(toolGateway.guard(ToolCallbacks.from(knowledge))));
            }
            if (!medical && run != null && mcpSettings.isPresent()) {
                var registeredMcp = mcpSettings.get().toolsForOwner(run.ownerId());
                if (!registeredMcp.isEmpty()) {
                    run.event(new AgentRun.Event("STAGE", "已加载 " + registeredMcp.size() + " 个个人 MCP 工具",
                            null, null, "COMPLETED", Instant.now(), 0, null, null, null, null));
                    return Stream.concat(tools.map(AgentTools.RegisteredTool::builtIn), registeredMcp.stream()).toList();
                }
            }
            return tools.map(AgentTools.RegisteredTool::builtIn).toList();
        };
    }
}
