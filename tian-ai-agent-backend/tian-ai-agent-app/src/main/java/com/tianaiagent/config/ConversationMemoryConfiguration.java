package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway;
import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.LocalVectorStore;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MemoryVectorStorage;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MilvusMemoryVectorStorage;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MybatisKnowledgeRepository;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.VectorSemanticMemoryGateway;
import com.tianaiagent.infrastructure.gateway.model.EmbeddingService;
import com.tianaiagent.types.config.DocMcpProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class ConversationMemoryConfiguration {
    static final String MILVUS_SELECTED = "'${doc-mcp.vector-store:sqlite}'.trim().equalsIgnoreCase('milvus')";

    @Bean
    @ConfigurationProperties("doc-mcp")
    DocMcpProperties docMcpProperties() {
        return new DocMcpProperties();
    }

    @Bean
    @ConditionalOnExpression("${agent.memory.long-term.enabled:false} && " + MILVUS_SELECTED)
    MilvusMemoryVectorStorage milvusMemoryVectorStorage(DocMcpProperties properties, ObjectMapper mapper) {
        return new MilvusMemoryVectorStorage(properties, mapper);
    }

    @Bean
    @ConditionalOnExpression("${agent.memory.long-term.enabled:false} && " + MILVUS_SELECTED)
    SemanticMemoryGateway milvusMemoryGateway(MilvusMemoryVectorStorage storage, DocMcpProperties properties,
                                              ObjectProvider<TextEmbedding> embeddings, ObjectMapper mapper) {
        return memoryGateway(storage, properties, embeddings, mapper);
    }

    @Bean
    @ConditionalOnExpression("(${doc-mcp.enabled:true} || ${agent.memory.long-term.enabled:false}) && !" + MILVUS_SELECTED)
    SemanticMemoryGateway sqliteMemoryGateway(MybatisKnowledgeRepository repository, DocMcpProperties properties,
                                              ObjectProvider<TextEmbedding> embeddings, ObjectMapper mapper) {
        return memoryGateway(new LocalVectorStore(repository), properties, embeddings, mapper);
    }

    private SemanticMemoryGateway memoryGateway(MemoryVectorStorage storage, DocMcpProperties properties,
                                                ObjectProvider<TextEmbedding> embeddings, ObjectMapper mapper) {
        TextEmbedding embedding = embeddings.getIfAvailable(() -> new EmbeddingService(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), mapper));
        return new VectorSemanticMemoryGateway(storage, embedding);
    }
}
