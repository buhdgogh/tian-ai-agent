package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.medical.MedicalEvidenceClient;
import com.tianaiagent.mcp.McpToolClient;
import com.tianaiagent.tools.*;
import com.tianaiagent.toolgateway.ToolGateway;
import com.tianaiagent.service.KnowledgeBaseService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 描述：集中的工具注册类
 * 将Tool注册为可供LLM调用的 ToolCallback 工具类
 */
@Configuration
public class ToolRegistration {
    @Value("${search-api.api-key}")
    private String searchApiKey;

    private final ToolGateway toolGateway;
    private final KnowledgeBaseService knowledgeBaseService;
    private final MedicalEvidenceClient medicalEvidenceClient;
    private final ObjectMapper objectMapper;
    private final McpToolClient mcpToolClient;

    public ToolRegistration(ToolGateway toolGateway, KnowledgeBaseService knowledgeBaseService,
                            MedicalEvidenceClient medicalEvidenceClient, ObjectMapper objectMapper,
                            McpToolClient mcpToolClient) {
        this.toolGateway = toolGateway;
        this.knowledgeBaseService = knowledgeBaseService;
        this.medicalEvidenceClient = medicalEvidenceClient;
        this.objectMapper = objectMapper;
        this.mcpToolClient = mcpToolClient;
    }

    @Bean
    public ToolCallback[] allTools() {
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        MedicalKnowledgeTool medicalKnowledgeTool = new MedicalKnowledgeTool(knowledgeBaseService, mcpToolClient);
        MedicalEvidenceTool medicalEvidenceTool = new MedicalEvidenceTool(medicalEvidenceClient, objectMapper);
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();

        // 将 Tool 注册为 ToolCallback 供 LLM 调用的
        ToolCallback[] callbacks = ToolCallbacks.from(
                webSearchTool,
                webScrapingTool,
                medicalKnowledgeTool,
                medicalEvidenceTool,
                pdfGenerationTool,
                terminateTool
        );
        return toolGateway.guard(callbacks);
    }
}
