package com.tianaiagent.config;

import com.tianaiagent.domain.knowledge.service.IndexService;
import com.tianaiagent.domain.knowledge.service.SpaceEmbeddings;
import com.tianaiagent.domain.agent.service.ModelProviderSettingsService;
import com.tianaiagent.infrastructure.gateway.model.CompatibleTextEmbedding;
import com.tianaiagent.domain.knowledge.service.WikiGraphService;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.FileWikiStore;
import com.tianaiagent.domain.knowledge.service.KnowledgeAnswerService;
import com.tianaiagent.domain.knowledge.service.document.DocumentIngestionService;
import com.tianaiagent.domain.knowledge.service.document.DocumentParseService;
import com.tianaiagent.domain.knowledge.service.EvidenceAssessmentService;
import com.tianaiagent.domain.knowledge.service.HierarchicalChunker;
import com.tianaiagent.infrastructure.gateway.document.MineruClient;
import com.tianaiagent.infrastructure.gateway.document.SourceLoader;
import com.tianaiagent.infrastructure.gateway.document.TikaDocumentParser;
import com.tianaiagent.infrastructure.gateway.model.DashScopeAnswerModel;
import com.tianaiagent.infrastructure.gateway.model.RoutedChatModel;
import com.tianaiagent.domain.knowledge.adapter.port.AnswerModel;
import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;
import com.tianaiagent.domain.knowledge.adapter.port.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.infrastructure.gateway.model.EmbeddingService;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MybatisKnowledgeRepository;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.LocalVectorStore;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MilvusVectorStore;
import com.tianaiagent.trigger.mcp.McpToolService;
import com.tianaiagent.types.config.DocMcpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "doc-mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({DocumentParseService.class, DocumentIngestionService.class, IndexService.class,
        KnowledgeAnswerService.class, WikiGraphService.class, FileWikiStore.class, McpToolService.class,
        SourceLoader.class, TikaDocumentParser.class,
        EmbeddingService.class})
class EmbeddedMcpConfiguration {
    @Bean
    VectorStore knowledgeVectorStore(DocMcpProperties properties, ObjectMapper mapper,
                                     MybatisKnowledgeRepository repository) {
        return "milvus".equals(properties.getVectorStore())
                ? new MilvusVectorStore(properties, mapper)
                : new LocalVectorStore(repository);
    }

    @Bean
    MineruClient mineruClient(DocMcpProperties properties, HttpClient httpClient, ObjectMapper mapper) {
        return new MineruClient(properties, httpClient, mapper);
    }

    @Bean
    @Primary
    AnswerModel answerModel(DocMcpProperties properties, HttpClient httpClient, ObjectMapper mapper,
                            AgentModelProperties models, RoutedChatModel routedChatModel) {
        AnswerModel legacy = new DashScopeAnswerModel(properties, httpClient, mapper);
        AnswerModel routed = wikiAnswerModel(routedChatModel);
        return (system, question) -> (models.providers().isEmpty() && !routedChatModel.hasPersonalRoutes() ? legacy : routed)
                .generate(system, question);
    }

    @Bean
    AnswerModel wikiAnswerModel(RoutedChatModel routedChatModel) {
        return (system, question) -> java.util.Optional.ofNullable(routedChatModel.call(
                        new org.springframework.ai.chat.prompt.Prompt(java.util.List.of(
                                new org.springframework.ai.chat.messages.SystemMessage(system),
                                new org.springframework.ai.chat.messages.UserMessage(question))))
                .getResult().getOutput().getText());
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Bean
    HierarchicalChunker hierarchicalChunker(DocMcpProperties properties) {
        return new HierarchicalChunker(properties.getChunkSizes(), properties.getChunkOverlap());
    }

    @Bean
    SpaceEmbeddings spaceEmbeddings(TextEmbedding defaults, ModelProviderSettingsService settings,
                                    AgentModelProperties models, ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(models.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new SpaceEmbeddings(defaults, settings,
                provider -> new CompatibleTextEmbedding(http, mapper, provider, models.timeout()));
    }

    @Bean
    EvidenceAssessmentService evidenceAssessmentService(DocMcpProperties properties) {
        var evidence = properties.getEvidence();
        return new EvidenceAssessmentService(evidence.getMinimumRelevantScore(),
                evidence.getMinimumSufficientScore(), evidence.getMinimumSources());
    }
}
