package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway;
import com.tianaiagent.domain.knowledge.adapter.port.VectorStore;
import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MybatisKnowledgeRepository;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MilvusMemoryVectorStorage;
import com.tianaiagent.types.config.DocMcpProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmbeddedMcpConfigurationTest {
    private final EmbeddedMcpConfiguration configuration = new EmbeddedMcpConfiguration();

    @Test
    void sqliteIsTheDefaultForBothKnowledgeAndMemory() {
        storageContext().withUserConfiguration(KnowledgeSqliteConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SemanticMemoryGateway.class);
                    assertThat(configuration.knowledgeVectorStore(context.getBean(DocMcpProperties.class),
                            context.getBean(ObjectMapper.class), context.getBean(MybatisKnowledgeRepository.class)))
                            .extracting(VectorStore::name).isEqualTo("sqlite-float32");
                    assertMemoryRoundTrip(context.getBean(SemanticMemoryGateway.class), context.getBean(DataSource.class));
                });
    }

    @Test
    void sqliteMemoryCanRunWithoutDocumentMcpUsingTheLegacyLocalValue() {
        storageContext().withUserConfiguration(KnowledgeSqliteConfiguration.class)
                .withPropertyValues("doc-mcp.enabled=false", "doc-mcp.vector-store=local", "agent.memory.long-term.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SemanticMemoryGateway.class);
                    assertMemoryRoundTrip(context.getBean(SemanticMemoryGateway.class), context.getBean(DataSource.class));
                });
    }

    @Test
    @EnabledIfSystemProperty(named = "tian.milvus-test.uri", matches = "https?://.+")
    void milvusStoresChunkPayloadsAndLongTermMemory() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        String chunks = "test_chunks_" + suffix;
        String memories = "test_memories_" + suffix;
        String uri = System.getProperty("tian.milvus-test.uri");
        try {
            storageContext().withUserConfiguration(KnowledgeSqliteConfiguration.class)
                .withPropertyValues("doc-mcp.vector-store=milvus", "agent.memory.long-term.enabled=true",
                        "doc-mcp.milvus.uri=" + uri, "doc-mcp.milvus.token=",
                        "doc-mcp.milvus.collection-name=" + chunks, "doc-mcp.milvus.memory-collection-name=" + memories,
                        "doc-mcp.embedding.dimensions=64")
                .withInitializer(context -> ((GenericApplicationContext) context).registerBean(VectorStore.class,
                        () -> configuration.knowledgeVectorStore(context.getBean(DocMcpProperties.class),
                                context.getBean(ObjectMapper.class), context.getBean(MybatisKnowledgeRepository.class))))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SemanticMemoryGateway.class)
                            .hasSingleBean(MilvusMemoryVectorStorage.class);
                    var store = context.getBean(VectorStore.class);
                    assertThat(store.name()).isEqualTo("milvus");
                    float[] vector = new float[64];
                    vector[0] = 1;
                    var chunk = new ChunkRecord("a".repeat(64), "test-doc", null, 0, true, 0,
                            "测试文档", null, null, "测试正文", Map.of("source", "test"), vector);
                    store.replaceDocument(KnowledgeSpace.MEDICAL, List.of(), List.of(chunk));
                    var matches = store.search(KnowledgeSpace.MEDICAL, vector, 1);
                    assertThat(matches).hasSize(1);
                    assertThat(matches.getFirst().chunk().text()).isEqualTo("测试正文");
                    assertThat(matches.getFirst().chunk().metadata()).containsEntry("source", "test");
                    var memory = context.getBean(SemanticMemoryGateway.class);
                    memory.upsert("owner", "test-memory", "Milvus memory", Map.of("source", "test"));
                    assertThat(memory.search("owner", "Milvus memory", 1)).extracting(SemanticMemoryGateway.Match::text)
                            .containsExactly("Milvus memory");
                    var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM semantic_memories", Integer.class)).isZero();
                });
        } finally {
            var client = new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
            try {
                for (String collection : List.of(chunks, memories)) {
                    if (client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
                        client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
                    }
                }
            } finally {
                client.close();
            }
        }
    }

    private ApplicationContextRunner storageContext() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ConversationMemoryConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("spring.datasource.url=jdbc:sqlite::memory:");
    }

    private static void assertMemoryRoundTrip(SemanticMemoryGateway memory, DataSource dataSource) {
        memory.upsert("owner", "test-memory", "SQLite memory", Map.of("source", "test"));
        assertThat(memory.search("owner", "SQLite memory", 1)).extracting(SemanticMemoryGateway.Match::text)
                .containsExactly("SQLite memory");
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM semantic_memories WHERE length(vector)>0", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void supportsAnExplicitAccountTokenAndRespectsTheFeatureFlag() {
        var properties = new DocMcpProperties();
        var client = configuration.mineruClient(properties, mock(HttpClient.class), new ObjectMapper())
                .withToken("account-mineru-token");

        assertThat(client.isAvailable()).isTrue();
        assertThat(properties.getMineru().getToken()).isEmpty();
        properties.getMineru().setEnabled(false);
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void permitsAnAccountWithoutTokenEvenWhenTheServerHasOne() {
        var properties = new DocMcpProperties();
        properties.getMineru().setToken("test-direct-token");
        var client = configuration.mineruClient(properties, mock(HttpClient.class), new ObjectMapper());
        assertThat(client.withToken("").isAvailable()).isFalse();
        assertThat(client.withToken("account-mineru-token").isAvailable()).isTrue();
        assertThat(client.withToken("").isAvailable()).isFalse();
    }
}
