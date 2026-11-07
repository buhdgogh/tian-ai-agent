package com.tianaiagent.docmcp.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import com.tianaiagent.docmcp.storage.KnowledgeRepository.DocumentRecord;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: uses unique Collections only; no existing Collection is deleted or rewritten. */
@EnabledIfSystemProperty(named = "milvus.integration", matches = "true")
class MilvusBm25IntegrationTest {
    @Test void realMilvusTokenizesBackfillsUpdatesDeletesAndFusesCandidates() {
        var properties = new DocMcpProperties();
        String prefix = "codex_bm25_it_" + UUID.randomUUID().toString().replace("-", "");
        properties.getMilvus().setUri(System.getProperty("milvus.test.uri", "http://127.0.0.1:29530"));
        properties.getMilvus().setToken("");
        properties.getMilvus().setCollectionName(prefix);
        properties.getMilvus().setKeywordCollectionName(prefix + "_bm25");
        properties.getMilvus().setMemoryCollectionName(prefix + "_memory");
        properties.getEmbedding().setProvider("local"); properties.getEmbedding().setDimensions(128);
        var mapper = new ObjectMapper();
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + prefix + ";DB_CLOSE_DELAY=-1", "sa", "");
        var repository = new KnowledgeRepository(new JdbcTemplate(datasource), new TransactionTemplate(new DataSourceTransactionManager(datasource)), mapper);
        ReflectionTestUtils.invokeMethod(repository, "initialize");
        // Represents an already indexed external chunk:正文在H2，Embedding不在H2。
        var oldChunk = IndexServiceHybridTest.chunk("a".repeat(64), "阿司匹林资料条目，仅用于检索测试。");
        repository.replaceDocument(new DocumentRecord(oldChunk.docId(), oldChunk.title(), "", "", Map.of()), List.of(oldChunk));
        var store = new MilvusVectorStore(properties, mapper, repository);
        var admin = new MilvusClientV2(ConnectConfig.builder().uri(properties.getMilvus().getUri()).build());
        try {
            try {
                store.initialize();
                assertEquals(oldChunk.nodeId(), store.searchKeywords("阿司匹林", 5).getFirst().chunk().nodeId());
                assertTrue(store.searchKeywords("ZXQUNKNOWN123XYZ", 5).isEmpty());
                var embeddings = new EmbeddingService(properties, HttpClient.newHttpClient(), mapper);
                var index = new IndexService(repository, new HierarchicalChunker(properties), embeddings, properties, store);
                index.upsert("medical-test", "# HbA1c 检测资料\n\nVXOLDMARKER 为测试用词，记录高血压随访资料。", Map.of("title", "HbA1c 检测资料"));
                assertTrue(index.retrieve("hba1c", 3, "keyword").sources().stream().anyMatch(source -> source.get("documentId").equals("medical-test")));
                assertTrue(index.retrieve("VXOLDMARKER", 3, "keyword").sources().size() > 0);
                var hybrid = index.retrieve("高血压 HbA1c", 5);
                assertEquals("hybrid-milvus-bm25-rrf-mmr", hybrid.mode());
                assertTrue(hybrid.sources().stream().anyMatch(source -> ((Number) source.get("bm25Score")).doubleValue() > 0));
                index.upsert("medical-test", "# HbA1c 检测资料\n\nVXNEWMARKER 是更新后的测试用词。", Map.of("title", "HbA1c 检测资料"));
                assertTrue(index.retrieve("VXOLDMARKER", 3, "keyword").sources().isEmpty());
                assertFalse(index.retrieve("VXNEWMARKER", 3, "keyword").sources().isEmpty());
                assertTrue(index.deleteDocument("medical-test"));
                assertTrue(index.retrieve("VXNEWMARKER", 3, "keyword").sources().isEmpty());
                // Re-running backfill must remain idempotent.
                new MilvusKeywordIndex(admin, properties, repository).initialize();
                assertEquals(1, store.searchKeywords("阿司匹林", 5).size());
            } finally {
                store.close();
                for (String name : List.of(prefix + "_bm25", prefix + "_memory", prefix)) {
                    if (!name.startsWith("codex_bm25_it_")) throw new IllegalStateException("Refusing to drop non-test Collection");
                    if (admin.hasCollection(HasCollectionReq.builder().collectionName(name).build())) admin.dropCollection(DropCollectionReq.builder().collectionName(name).build());
                }
            }
        } finally { admin.close(); }
    }
}
