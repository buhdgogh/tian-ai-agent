package com.tianaiagent.docmcp.index;

import com.google.gson.JsonObject;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import com.tianaiagent.docmcp.storage.KnowledgeRepository.ChunkRecord;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import java.util.*;

/** BM25 is generated and indexed by Milvus; H2 remains the canonical chunk metadata store. */
final class MilvusKeywordIndex {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MilvusKeywordIndex.class);
    static final String NODE_ID = "node_id", DOC_ID = "doc_id", TEXT = "search_text", SPARSE = "bm25_sparse";
    private final MilvusClientV2 client;
    private final DocMcpProperties properties;
    private final KnowledgeRepository repository;

    MilvusKeywordIndex(MilvusClientV2 client, DocMcpProperties properties, KnowledgeRepository repository) {
        this.client = client; this.properties = properties; this.repository = repository;
    }

    String collection() {
        String configured = properties.getMilvus().getKeywordCollectionName();
        return configured == null || configured.isBlank() ? properties.getMilvus().getCollectionName() + "_bm25_v1" : configured;
    }
    private String database() { return properties.getMilvus().getDatabaseName(); }
    private int batchSize() { return Math.max(1, properties.getMilvus().getBatchSize()); }

    void initialize() {
        if (collection().equals(properties.getMilvus().getCollectionName()) || collection().equals(properties.getMilvus().getMemoryCollectionName())) {
            throw new IllegalArgumentException("BM25 Collection 必须独立于 Dense 和长期记忆 Collection");
        }
        if (!client.hasCollection(HasCollectionReq.builder().databaseName(database()).collectionName(collection()).build())) {
            client.createCollection(createRequest());
        }
        var schema = client.describeCollection(DescribeCollectionReq.builder().databaseName(database()).collectionName(collection()).build()).getCollectionSchema();
        if (schema.getField(SPARSE) == null || schema.getFunctionList() == null
                || schema.getFunctionList().stream().noneMatch(function -> function.getFunctionType() == FunctionType.BM25
                && function.getInputFieldNames().equals(List.of(TEXT)) && function.getOutputFieldNames().equals(List.of(SPARSE)))) {
            throw new IllegalStateException("BM25 Collection schema 不兼容，请配置新的 keyword-collection-name 后重建");
        }
        client.loadCollection(LoadCollectionReq.builder().databaseName(database()).collectionName(collection()).build());
        // Idempotent paged backfill also resumes a partially completed startup migration.
        String cursor = "";
        long backfilled = 0;
        while (true) {
            List<ChunkRecord> batch = repository.loadLeafChunksAfter(cursor, batchSize());
            if (batch.isEmpty()) break;
            upsert(batch);
            backfilled += batch.size();
            cursor = batch.getLast().nodeId();
        }
        log.info("Milvus BM25索引就绪 collection={}, backfilledChunks={}", collection(), backfilled);
    }

    CreateCollectionReq createRequest() {
        var schema = CreateCollectionReq.CollectionSchema.builder().enableDynamicField(false).build();
        schema.addField(AddFieldReq.builder().fieldName(NODE_ID).dataType(DataType.VarChar).maxLength(64).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder().fieldName(DOC_ID).dataType(DataType.VarChar).maxLength(300).build());
        // Jieba + lowercase supports Chinese words and retains HbA1c / drug abbreviations.
        schema.addField(AddFieldReq.builder().fieldName(TEXT).dataType(DataType.VarChar).maxLength(65535).enableAnalyzer(true)
                .analyzerParams(Map.of("tokenizer", "jieba", "filter", List.of("lowercase", "removepunct"))).build());
        schema.addField(AddFieldReq.builder().fieldName(SPARSE).dataType(DataType.SparseFloatVector).build());
        schema.addFunction(CreateCollectionReq.Function.builder().name("knowledge_bm25")
                .functionType(FunctionType.BM25).inputFieldNames(List.of(TEXT)).outputFieldNames(List.of(SPARSE)).build());
        var index = IndexParam.builder().fieldName(SPARSE).indexName("knowledge_bm25_index")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.BM25)
                .extraParams(Map.of("inverted_index_algo", "DAAT_MAXSCORE", "bm25_k1", "1.2", "bm25_b", "0.75")).build();
        return CreateCollectionReq.builder().databaseName(database()).collectionName(collection())
                .description("Tian BM25 v1: jieba, lowercase, removepunct; canonical metadata in H2")
                .collectionSchema(schema).indexParams(List.of(index)).consistencyLevel(ConsistencyLevel.STRONG).build();
    }

    void upsert(List<ChunkRecord> chunks) {
        List<ChunkRecord> leaves = chunks.stream().filter(ChunkRecord::leaf).toList();
        for (int from = 0; from < leaves.size(); from += batchSize()) {
            var rows = leaves.subList(from, Math.min(leaves.size(), from + batchSize())).stream().map(chunk -> {
                var row = new JsonObject(); row.addProperty(NODE_ID, chunk.nodeId()); row.addProperty(DOC_ID, chunk.docId());
                String text = (chunk.title() == null ? "" : chunk.title()) + "\n" + chunk.text();
                if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535) {
                    throw new IllegalArgumentException("BM25片段（标题+正文）超过65535字节，请减小叶子chunk-sizes");
                }
                row.addProperty(TEXT, text);
                return row;
            }).toList();
            // Do not supply bm25_sparse; Milvus computes it from search_text.
            client.upsert(UpsertReq.builder().databaseName(database()).collectionName(collection()).data(rows).build());
        }
    }

    void deleteIds(List<Object> ids) {
        if (!ids.isEmpty()) client.delete(DeleteReq.builder().databaseName(database()).collectionName(collection()).ids(ids).build());
    }

    void deleteDocument(String docId) {
        client.delete(DeleteReq.builder().databaseName(database()).collectionName(collection())
                .filter(DOC_ID + " == " + new com.google.gson.Gson().toJson(docId)).build());
    }

    List<VectorStore.KeywordCandidate> search(String query, int limit) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("question不能为空");
        var response = client.search(SearchReq.builder().databaseName(database()).collectionName(collection())
                .annsField(SPARSE).data(List.of(new EmbeddedText(query))).metricType(IndexParam.MetricType.BM25)
                .topK(Math.max(1, limit)).limit(Math.max(1, limit)).outputFields(List.of(NODE_ID, DOC_ID))
                .consistencyLevel(ConsistencyLevel.STRONG).build());
        if (response.getSearchResults().isEmpty()) return List.of();
        List<VectorStore.KeywordCandidate> result = new ArrayList<>();
        for (var hit : response.getSearchResults().getFirst()) {
            if (!(hit.getScore() > 0)) continue;
            // Do not surface orphaned rows left by an interrupted cross-store update.
            repository.findChunk(String.valueOf(hit.getId())).filter(ChunkRecord::leaf)
                    .ifPresent(chunk -> result.add(new VectorStore.KeywordCandidate(chunk, hit.getScore())));
        }
        return result;
    }
}
