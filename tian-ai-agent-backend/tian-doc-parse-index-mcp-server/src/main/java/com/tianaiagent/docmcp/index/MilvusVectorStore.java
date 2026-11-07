package com.tianaiagent.docmcp.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.storage.KnowledgeRepository.ChunkRecord;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Milvus 叶子 Chunk 向量存储，实现文档级增量覆盖和向量候选召回。 */
@Component
@ConditionalOnProperty(prefix = "doc-mcp", name = "vector-store", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStore implements VectorStore {
    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);
    private static final String NODE_ID = "node_id";
    private static final String DOC_ID = "doc_id";
    private static final String PARENT_ID = "parent_id";
    private static final String LEVEL = "level_no";
    private static final String ORDINAL = "ordinal_no";
    private static final String TITLE = "title";
    private static final String SOURCE_URI = "source_uri";
    private static final String CONTENT_SHA256 = "content_sha256";
    private static final String CHUNK_TEXT = "chunk_text";
    private static final String METADATA_JSON = "metadata_json";
    private static final String VECTOR = "embedding";
    private static final String MEMORY_ID = "memory_id";
    private static final String OWNER_ID = "owner_id";
    private static final String MEMORY_TEXT = "memory_text";

    private final DocMcpProperties properties;
    private final ObjectMapper mapper;
    private MilvusClientV2 client;
    private final KnowledgeRepository repository;
    private MilvusKeywordIndex keywordIndex;

    public MilvusVectorStore(DocMcpProperties properties, ObjectMapper mapper, KnowledgeRepository repository) {
        this.properties = properties;
        this.mapper = mapper;
        this.repository = repository;
    }

    @PostConstruct
    void initialize() {
        DocMcpProperties.Milvus config = properties.getMilvus();
        if (config.getUri() == null || config.getUri().isBlank()) {
            throw new IllegalStateException("VECTOR_STORE=milvus 时必须配置 MILVUS_URI");
        }
        var builder = ConnectConfig.builder().uri(config.getUri());
        if (config.getToken() != null && !config.getToken().isBlank()) builder.token(config.getToken());
        client = new MilvusClientV2(builder.build());
        ensureCollection();
        ensureMemoryCollection();
        keywordIndex = new MilvusKeywordIndex(client, properties, repository);
        keywordIndex.initialize();
        log.info("Milvus向量库已连接: database={}, collection={}, dimensions={}",
                database(), collection(), dimensions());
    }

    private void ensureCollection() {
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .databaseName(database()).collectionName(collection()).build());
        if (!exists) {
            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            schema.addField(varchar(NODE_ID, 64, true));
            schema.addField(varchar(DOC_ID, 300, false));
            schema.addField(varchar(PARENT_ID, 64, false));
            schema.addField(AddFieldReq.builder().fieldName(LEVEL).dataType(DataType.Int32).build());
            schema.addField(AddFieldReq.builder().fieldName(ORDINAL).dataType(DataType.Int32).build());
            schema.addField(varchar(TITLE, 4096, false));
            schema.addField(varchar(SOURCE_URI, 8192, false));
            schema.addField(varchar(CONTENT_SHA256, 64, false));
            schema.addField(varchar(CHUNK_TEXT, 65535, false));
            schema.addField(varchar(METADATA_JSON, 65535, false));
            schema.addField(AddFieldReq.builder().fieldName(VECTOR).dataType(DataType.FloatVector)
                    .dimension(dimensions()).build());

            IndexParam vectorIndex = IndexParam.builder()
                    .fieldName(VECTOR)
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build();
            client.createCollection(CreateCollectionReq.builder()
                    .databaseName(database())
                    .collectionName(collection())
                    .description("Tian AI Agent knowledge-base leaf chunks")
                    .collectionSchema(schema)
                    .indexParams(List.of(vectorIndex))
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
        }
        client.loadCollection(LoadCollectionReq.builder()
                .databaseName(database()).collectionName(collection()).build());
    }

    private void ensureMemoryCollection() {
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .databaseName(database()).collectionName(memoryCollection()).build());
        if (!exists) {
            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            schema.addField(varchar(MEMORY_ID, 128, true));
            schema.addField(varchar(OWNER_ID, 300, false));
            schema.addField(varchar(MEMORY_TEXT, 65535, false));
            schema.addField(varchar(METADATA_JSON, 65535, false));
            schema.addField(AddFieldReq.builder().fieldName(VECTOR).dataType(DataType.FloatVector)
                    .dimension(dimensions()).build());
            IndexParam vectorIndex = IndexParam.builder().fieldName(VECTOR)
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.COSINE).build();
            client.createCollection(CreateCollectionReq.builder().databaseName(database())
                    .collectionName(memoryCollection())
                    .description("Tian AI Agent isolated long-term semantic memories")
                    .collectionSchema(schema).indexParams(List.of(vectorIndex))
                    .consistencyLevel(ConsistencyLevel.STRONG).build());
        }
        client.loadCollection(LoadCollectionReq.builder().databaseName(database())
                .collectionName(memoryCollection()).build());
    }

    private AddFieldReq varchar(String name, int maxLength, boolean primary) {
        var builder = AddFieldReq.builder().fieldName(name).dataType(DataType.VarChar)
                .maxLength(maxLength).isPrimaryKey(primary);
        if (primary) builder.autoID(false);
        return builder.build();
    }

    @Override
    public void replaceDocument(String documentId, List<ChunkRecord> previous, List<ChunkRecord> current) {
        List<ChunkRecord> leaves = current.stream().filter(ChunkRecord::leaf).toList();
        int batchSize = Math.max(1, properties.getMilvus().getBatchSize());
        for (int from = 0; from < leaves.size(); from += batchSize) {
            List<JsonObject> rows = leaves.subList(from, Math.min(leaves.size(), from + batchSize))
                    .stream().map(this::toRow).toList();
            client.upsert(UpsertReq.builder()
                    .databaseName(database()).collectionName(collection()).data(rows).build());
        }
        keywordIndex.upsert(leaves);

        Set<String> currentIds = new HashSet<>();
        leaves.forEach(chunk -> currentIds.add(chunk.nodeId()));
        List<Object> staleIds = previous.stream().filter(ChunkRecord::leaf)
                .map(ChunkRecord::nodeId).filter(id -> !currentIds.contains(id))
                .map(id -> (Object) id).toList();
        if (!staleIds.isEmpty()) {
            client.delete(DeleteReq.builder().databaseName(database())
                    .collectionName(collection()).ids(staleIds).build());
            keywordIndex.deleteIds(staleIds);
        }
    }

    @Override
    public List<VectorCandidate> search(float[] queryVector, int limit) {
        SearchResp response = client.search(SearchReq.builder()
                .databaseName(database())
                .collectionName(collection())
                .annsField(VECTOR)
                .data(List.of(new FloatVec(queryVector)))
                .topK(Math.max(1, limit))
                .limit(Math.max(1, limit))
                .outputFields(List.of(NODE_ID))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        if (response.getSearchResults().isEmpty()) return List.of();
        List<VectorCandidate> candidates = new ArrayList<>();
        for (SearchResp.SearchResult result : response.getSearchResults().getFirst()) {
            // H2 is authoritative even if an external upsert succeeded before a failed SQL commit.
            repository.findChunk(String.valueOf(result.getId())).filter(ChunkRecord::leaf)
                    .ifPresent(chunk -> candidates.add(new VectorCandidate(chunk, Math.max(0, result.getScore()))));
        }
        return candidates;
    }

    @Override
    public void deleteDocument(String documentId) {
        keywordIndex.deleteDocument(documentId);
        client.delete(DeleteReq.builder().databaseName(database()).collectionName(collection())
                .filter(DOC_ID + " == " + jsonString(documentId)).build());
    }

    @Override public boolean supportsKeywordSearch() { return true; }
    @Override public List<KeywordCandidate> searchKeywords(String query, int limit) { return keywordIndex.search(query, limit); }

    @Override
    public void upsertMemory(MemoryVector memory) {
        JsonObject row = new JsonObject();
        row.addProperty(MEMORY_ID, memory.memoryId());
        row.addProperty(OWNER_ID, memory.ownerId());
        row.addProperty(MEMORY_TEXT, memory.text());
        row.addProperty(METADATA_JSON, writeMetadata(memory.metadata()));
        JsonArray vector = new JsonArray(memory.vector().length);
        for (float value : memory.vector()) vector.add(value);
        row.add(VECTOR, vector);
        client.upsert(UpsertReq.builder().databaseName(database())
                .collectionName(memoryCollection()).data(List.of(row)).build());
    }

    @Override
    public List<MemoryCandidate> searchMemories(String ownerId, float[] queryVector, int limit) {
        SearchResp response = client.search(SearchReq.builder().databaseName(database())
                .collectionName(memoryCollection()).annsField(VECTOR)
                .data(List.of(new FloatVec(queryVector))).topK(Math.max(1, limit))
                .limit(Math.max(1, limit)).filter(OWNER_ID + " == " + jsonString(ownerId))
                .outputFields(List.of(MEMORY_ID, OWNER_ID, MEMORY_TEXT, METADATA_JSON))
                .consistencyLevel(ConsistencyLevel.STRONG).build());
        if (response.getSearchResults().isEmpty()) return List.of();
        List<MemoryCandidate> candidates = new ArrayList<>();
        for (SearchResp.SearchResult result : response.getSearchResults().getFirst()) {
            Map<String, Object> entity = result.getEntity();
            candidates.add(new MemoryCandidate(text(entity, MEMORY_ID), text(entity, OWNER_ID),
                    text(entity, MEMORY_TEXT), readMetadata(text(entity, METADATA_JSON)),
                    Math.max(0, result.getScore())));
        }
        return candidates;
    }

    @Override
    public void deleteMemories(String ownerId) {
        client.delete(DeleteReq.builder().databaseName(database()).collectionName(memoryCollection())
                .filter(OWNER_ID + " == " + jsonString(ownerId)).build());
    }

    private JsonObject toRow(ChunkRecord chunk) {
        JsonObject row = new JsonObject();
        row.addProperty(NODE_ID, chunk.nodeId());
        row.addProperty(DOC_ID, chunk.docId());
        row.addProperty(PARENT_ID, safe(chunk.parentId()));
        row.addProperty(LEVEL, chunk.level());
        row.addProperty(ORDINAL, chunk.ordinal());
        row.addProperty(TITLE, safe(chunk.title()));
        row.addProperty(SOURCE_URI, safe(chunk.sourceUri()));
        row.addProperty(CONTENT_SHA256, safe(chunk.contentSha256()));
        row.addProperty(CHUNK_TEXT, chunk.text());
        row.addProperty(METADATA_JSON, writeMetadata(chunk.metadata()));
        JsonArray vector = new JsonArray(chunk.vector().length);
        for (float value : chunk.vector()) vector.add(value);
        row.add(VECTOR, vector);
        return row;
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try { return mapper.writeValueAsString(metadata == null ? Map.of() : metadata); }
        catch (Exception e) { throw new IllegalStateException("Milvus元数据序列化失败", e); }
    }

    private Map<String, Object> readMetadata(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Milvus元数据反序列化失败", e); }
    }

    private static String text(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        if (value instanceof JsonElement json) return json.isJsonNull() ? "" : json.getAsString();
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String jsonString(String value) { return new com.google.gson.Gson().toJson(value); }
    private int dimensions() { return Math.max(64, properties.getEmbedding().getDimensions()); }
    private String database() { return properties.getMilvus().getDatabaseName(); }
    private String collection() { return properties.getMilvus().getCollectionName(); }
    private String memoryCollection() { return properties.getMilvus().getMemoryCollectionName(); }

    @Override
    public boolean external() { return true; }

    @Override
    public String name() { return "milvus"; }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name());
        value.put("database", database());
        value.put("collection", collection());
        value.put("memory_collection", memoryCollection());
        value.put("keyword_collection", keywordIndex.collection());
        value.put("keyword_search", "milvus-native-bm25");
        value.put("analyzer", "jieba+lowercase+removepunct");
        value.put("dimensions", dimensions());
        value.put("vectors", client.getCollectionStats(GetCollectionStatsReq.builder()
                .databaseName(database()).collectionName(collection()).build()).getNumOfEntities());
        return value;
    }

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}
