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
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.tianaiagent.infrastructure.gateway.model.EmbeddingService;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MybatisKnowledgeRepository;
import com.tianaiagent.infrastructure.dao.MybatisSessions;
import com.tianaiagent.infrastructure.dao.knowledge.KnowledgeMapper;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.LocalVectorStore;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MilvusVectorStore;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.VectorSemanticMemoryGateway;
import com.tianaiagent.trigger.mcp.McpToolService;
import com.tianaiagent.types.config.DocMcpProperties;
import org.apache.ibatis.type.ByteArrayTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "doc-mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DataSourceProperties.class)
@Import({DocumentParseService.class, DocumentIngestionService.class, IndexService.class,
        KnowledgeAnswerService.class, WikiGraphService.class, FileWikiStore.class, McpToolService.class,
        SourceLoader.class, TikaDocumentParser.class,
        EmbeddingService.class, LocalVectorStore.class, MilvusVectorStore.class})
class EmbeddedMcpConfiguration {
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
    HikariDataSource dataSource(DataSourceProperties properties) throws IOException {
        String url = properties.getUrl();
        String prefix = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefix) || url.length() == prefix.length()) {
            throw new IllegalArgumentException("知识库数据源必须配置 SQLite 文件或内存地址");
        }
        String database = url.substring(prefix.length());
        if (!":memory:".equals(database) && !database.startsWith("file:")) {
            Files.createDirectories(Path.of(database).toAbsolutePath().getParent());
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName("knowledge-sqlite");
        config.setMaximumPoolSize(1);
        config.setMaxLifetime(0);
        config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
        return new HikariDataSource(config);
    }

    @Bean
    MybatisKnowledgeRepository knowledgeRepository(DataSource dataSource, ObjectMapper mapper) {
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite/knowledge.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        if (!jdbc.queryForList("SELECT name FROM pragma_table_info('knowledge_spaces')", String.class)
                .contains("embedding_binding")) {
            jdbc.execute("ALTER TABLE knowledge_spaces ADD COLUMN embedding_binding TEXT");
        }
        // SQLite JDBC supports getBytes for BLOB values, rather than java.sql.Blob.
        var sessions = MybatisSessions.create(dataSource, configuration -> configuration.getTypeHandlerRegistry()
                .register(byte[].class, JdbcType.BLOB, ByteArrayTypeHandler.class), KnowledgeMapper.class);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new MybatisKnowledgeRepository(sessions.getMapper(KnowledgeMapper.class), transactions, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "doc-mcp", name = "vector-store", havingValue = "local", matchIfMissing = true)
    SemanticMemoryGateway localMemoryGateway(LocalVectorStore storage, TextEmbedding embedding) {
        return new VectorSemanticMemoryGateway(storage, embedding);
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
