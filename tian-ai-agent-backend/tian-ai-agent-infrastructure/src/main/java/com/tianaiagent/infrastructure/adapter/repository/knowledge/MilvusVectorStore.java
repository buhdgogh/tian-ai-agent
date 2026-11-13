package com.tianaiagent.infrastructure.adapter.repository.knowledge;

import com.tianaiagent.domain.knowledge.adapter.port.VectorStore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tianaiagent.types.config.DocMcpProperties;
import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.DropPartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.partition.request.LoadPartitionsReq;
import io.milvus.v2.service.partition.request.ReleasePartitionsReq;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Milvus 叶子 Chunk 向量存储，实现文档级增量覆盖和向量候选召回。 */
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
    private static final List<String> OUTPUT_FIELDS = List.of(
            NODE_ID, DOC_ID, PARENT_ID, LEVEL, ORDINAL, TITLE, SOURCE_URI,
            CONTENT_SHA256, CHUNK_TEXT, METADATA_JSON);

    private final DocMcpProperties properties;
    private final ObjectMapper mapper;
    private final Set<String> loadedPartitions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> loadedCollections = new HashSet<>();
    private MilvusClientV2 client;

    public MilvusVectorStore(DocMcpProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostConstruct
    void initialize() {
        DocMcpProperties.Milvus config = properties.getMilvus();
        if (config.getUri() == null || config.getUri().isBlank()) {
            throw new IllegalStateException("选择 Milvus 时必须配置 doc-mcp.milvus.uri");
        }
        var builder = ConnectConfig.builder().uri(config.getUri());
        if (config.getToken() != null && !config.getToken().isBlank()) builder.token(config.getToken());
        client = new MilvusClientV2(builder.build());
        ensureCollection(collection(KnowledgeSpace.MEDICAL), dimensions(KnowledgeSpace.MEDICAL), true);
        log.info("Milvus向量库已连接: database={}, collection={}, dimensions={}",
                database(), collection(KnowledgeSpace.MEDICAL), dimensions(KnowledgeSpace.MEDICAL));
    }

    private synchronized boolean ensureCollection(String collection, int dimensions, boolean create) {
        if (loadedCollections.contains(collection)) return true;
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .databaseName(database()).collectionName(collection).build());
        if (!exists) {
            if (!create) return false;
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
                    .dimension(dimensions).build());

            IndexParam vectorIndex = IndexParam.builder()
                    .fieldName(VECTOR)
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build();
            client.createCollection(CreateCollectionReq.builder()
                    .databaseName(database())
                    .collectionName(collection)
                    .description("Tian AI Agent knowledge-base leaf chunks")
                    .collectionSchema(schema)
                    .indexParams(List.of(vectorIndex))
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
        }
        client.loadCollection(LoadCollectionReq.builder()
                .databaseName(database()).collectionName(collection).build());
        loadedCollections.add(collection);
        return true;
    }

    private AddFieldReq varchar(String name, int maxLength, boolean primary) {
        var builder = AddFieldReq.builder().fieldName(name).dataType(DataType.VarChar)
                .maxLength(maxLength).isPrimaryKey(primary);
        if (primary) builder.autoID(false);
        return builder.build();
    }

    @Override
    public void replaceDocument(KnowledgeSpace space, List<ChunkRecord> previous, List<ChunkRecord> current) {
        String collection = collection(space);
        String partition = partition(space, collection, true);
        List<ChunkRecord> leaves = current.stream().filter(ChunkRecord::leaf).toList();
        int batchSize = Math.max(1, properties.getMilvus().getBatchSize());
        for (int from = 0; from < leaves.size(); from += batchSize) {
            List<JsonObject> rows = leaves.subList(from, Math.min(leaves.size(), from + batchSize))
                    .stream().map(this::toRow).toList();
            client.upsert(UpsertReq.builder()
                    .databaseName(database()).collectionName(collection).partitionName(partition).data(rows).build());
        }

        Set<String> currentIds = new HashSet<>();
        leaves.forEach(chunk -> currentIds.add(chunk.nodeId()));
        List<Object> staleIds = previous.stream().filter(ChunkRecord::leaf)
                .map(ChunkRecord::nodeId).filter(id -> !currentIds.contains(id))
                .map(id -> (Object) id).toList();
        if (!staleIds.isEmpty()) {
            client.delete(DeleteReq.builder().databaseName(database())
                    .collectionName(collection).partitionName(partition).ids(staleIds).build());
        }
    }

    @Override
    public List<VectorCandidate> search(KnowledgeSpace space, float[] queryVector, int limit) {
        String collection = collection(space);
        String partition = partition(space, collection, false);
        if (partition == null) return List.of();
        SearchResp response = client.search(SearchReq.builder()
                .databaseName(database())
                .collectionName(collection)
                .partitionNames(List.of(partition))
                .annsField(VECTOR)
                .data(List.of(new FloatVec(queryVector)))
                .topK(Math.max(1, limit))
                .limit(Math.max(1, limit))
                .outputFields(OUTPUT_FIELDS)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        if (response.getSearchResults().isEmpty()) return List.of();
        List<VectorCandidate> candidates = new ArrayList<>();
        for (SearchResp.SearchResult result : response.getSearchResults().getFirst()) {
            Map<String, Object> entity = result.getEntity();
            candidates.add(new VectorCandidate(toChunk(entity), Math.max(0, result.getScore())));
        }
        return candidates;
    }

    @Override
    public void deleteDocument(KnowledgeSpace space, String documentId) {
        String collection = collection(space);
        String partition = partition(space, collection, false);
        if (partition == null) return;
        client.delete(DeleteReq.builder().databaseName(database()).collectionName(collection)
                .partitionName(partition)
                .filter(DOC_ID + " == " + jsonString(documentId)).build());
    }

    @Override
    public void deleteSpace(KnowledgeSpace space) {
        if (KnowledgeSpace.MEDICAL_ID.equals(KnowledgeSpace.requireId(space.id()))) {
            throw new IllegalArgumentException("不能删除默认向量分区");
        }
        String collection = collection(space);
        if (!ensureCollection(collection, dimensions(space), false)) return;
        String name = "space_" + space.id();
        synchronized (loadedPartitions) {
            loadedPartitions.remove(name);
            if (!client.hasPartition(HasPartitionReq.builder().databaseName(database())
                    .collectionName(collection).partitionName(name).build())) return;
            client.releasePartitions(ReleasePartitionsReq.builder().databaseName(database())
                    .collectionName(collection).partitionNames(List.of(name)).build());
            client.dropPartition(DropPartitionReq.builder().databaseName(database())
                    .collectionName(collection).partitionName(name).build());
        }
    }

    private String partition(KnowledgeSpace space, String collection, boolean create) {
        String spaceId = space.id();
        if (KnowledgeSpace.MEDICAL_ID.equals(KnowledgeSpace.requireId(spaceId))) return "_default";
        String name = "space_" + spaceId;
        if (loadedPartitions.contains(name)) return name;
        if (!ensureCollection(collection, dimensions(space), create)) return null;
        synchronized (loadedPartitions) {
            if (loadedPartitions.contains(name)) return name;
            if (!client.hasPartition(HasPartitionReq.builder().databaseName(database())
                    .collectionName(collection).partitionName(name).build())) {
                if (!create) return null;
                client.createPartition(CreatePartitionReq.builder().databaseName(database())
                        .collectionName(collection).partitionName(name).build());
            }
            client.loadPartitions(LoadPartitionsReq.builder().databaseName(database())
                    .collectionName(collection).partitionNames(List.of(name)).build());
            loadedPartitions.add(name);
        }
        return name;
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

    private ChunkRecord toChunk(Map<String, Object> entity) {
        return new ChunkRecord(text(entity, NODE_ID), text(entity, DOC_ID), text(entity, PARENT_ID),
                integer(entity, LEVEL), true, integer(entity, ORDINAL), text(entity, TITLE),
                text(entity, SOURCE_URI), text(entity, CONTENT_SHA256), text(entity, CHUNK_TEXT),
                readMetadata(text(entity, METADATA_JSON)), null);
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

    private static int integer(Map<String, Object> entity, String field) {
        Object value = entity.get(field);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof JsonElement json) return json.getAsInt();
        return Integer.parseInt(String.valueOf(value));
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String jsonString(String value) { return new com.google.gson.Gson().toJson(value); }
    private int dimensions(KnowledgeSpace space) {
        return space.embedding() == null ? Math.max(64, properties.getEmbedding().getDimensions()) : space.embedding().dimensions();
    }
    private String database() { return properties.getMilvus().getDatabaseName(); }
    private String collection(KnowledgeSpace space) {
        String base = properties.getMilvus().getCollectionName();
        return space.embedding() == null ? base : base + "_embedding_" + dimensions(space);
    }

    @Override
    public boolean external() { return true; }

    @Override
    public String name() { return "milvus"; }

    @Override
    public Map<String, Object> status(KnowledgeSpace space) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name());
        value.put("database", database());
        value.put("collection", collection(space));
        value.put("dimensions", dimensions(space));
        return value;
    }

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}
