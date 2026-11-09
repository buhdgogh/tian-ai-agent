package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway;
import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MilvusMemoryVectorStorage;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.VectorSemanticMemoryGateway;
import com.tianaiagent.infrastructure.gateway.model.EmbeddingService;
import com.tianaiagent.types.config.DocMcpProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class ConversationMemoryConfiguration {
    @Bean
    @ConfigurationProperties("doc-mcp")
    DocMcpProperties docMcpProperties() {
        return new DocMcpProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.memory.long-term.enabled", havingValue = "true")
    MilvusMemoryVectorStorage milvusMemoryVectorStorage(DocMcpProperties properties, ObjectMapper mapper) {
        return new MilvusMemoryVectorStorage(properties, mapper);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "agent.memory.long-term.enabled", havingValue = "true")
    SemanticMemoryGateway milvusMemoryGateway(MilvusMemoryVectorStorage storage, DocMcpProperties properties,
                                              ObjectProvider<TextEmbedding> embeddings, ObjectMapper mapper) {
        TextEmbedding embedding = embeddings.getIfAvailable(() -> new EmbeddingService(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), mapper));
        return new VectorSemanticMemoryGateway(storage, embedding);
    }
}
