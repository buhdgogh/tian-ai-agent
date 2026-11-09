package com.tianaiagent.infrastructure.adapter.repository.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway.Match;
import com.tianaiagent.types.config.DocMcpProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 长期会话记忆使用独立的 Milvus 集合。 */
public class MilvusMemoryVectorStorage implements MemoryVectorStorage {
    private static final String ID = "memory_id";
    private static final String OWNER = "owner_id";
    private static final String TEXT = "memory_text";
    private static final String META = "metadata_json";
    private static final String VECTOR = "embedding";
    private static final List<String> FIELDS = List.of(ID, OWNER, TEXT, META);
    private static final Gson JSON = new Gson();

    private final DocMcpProperties properties;
    private final ObjectMapper mapper;
    private MilvusClientV2 client;

    public MilvusMemoryVectorStorage(DocMcpProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostConstruct
    void initialize() {
        var config = properties.getMilvus();
        if (config.getUri() == null || config.getUri().isBlank()) {
            throw new IllegalStateException("启用长期记忆时必须配置 doc-mcp.milvus.uri");
        }
        var connection = ConnectConfig.builder().uri(config.getUri());
        if (config.getToken() != null && !config.getToken().isBlank()) connection.token(config.getToken());
        client = new MilvusClientV2(connection.build());
        try {
            if (!client.hasCollection(HasCollectionReq.builder().databaseName(database())
                    .collectionName(collection()).build())) {
                var schema = client.createSchema();
                schema.addField(AddFieldReq.builder().fieldName(ID).dataType(DataType.VarChar)
                        .maxLength(128).isPrimaryKey(true).autoID(false).build());
                schema.addField(varchar(OWNER, 300));
                schema.addField(varchar(TEXT, 65535));
                schema.addField(varchar(META, 65535));
                schema.addField(AddFieldReq.builder().fieldName(VECTOR).dataType(DataType.FloatVector)
                        .dimension(Math.max(64, properties.getEmbedding().getDimensions())).build());
                var index = IndexParam.builder().fieldName(VECTOR).indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(IndexParam.MetricType.COSINE).build();
                client.createCollection(CreateCollectionReq.builder().databaseName(database()).collectionName(collection())
                        .description("Tian long-term conversation memories").collectionSchema(schema)
                        .indexParams(List.of(index)).consistencyLevel(ConsistencyLevel.STRONG).build());
            }
            client.loadCollection(LoadCollectionReq.builder().databaseName(database()).collectionName(collection()).build());
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    @Override
    public void upsertMemory(MemoryVector memory) {
        var row = new JsonObject();
        row.addProperty(ID, memory.memoryId());
        row.addProperty(OWNER, memory.ownerId());
        row.addProperty(TEXT, memory.text());
        row.addProperty(META, write(memory.metadata()));
        var vector = new JsonArray(memory.vector().length);
        for (float value : memory.vector()) vector.add(value);
        row.add(VECTOR, vector);
        client.upsert(UpsertReq.builder().databaseName(database()).collectionName(collection()).data(List.of(row)).build());
    }

    @Override
    public List<Match> searchMemories(String ownerId, float[] vector, int limit) {
        var response = client.search(SearchReq.builder().databaseName(database()).collectionName(collection())
                .annsField(VECTOR).data(List.of(new FloatVec(vector))).topK(limit).limit(limit)
                .filter(ownerFilter(ownerId)).outputFields(FIELDS).consistencyLevel(ConsistencyLevel.STRONG).build());
        if (response.getSearchResults().isEmpty()) return List.of();
        return response.getSearchResults().getFirst().stream()
                .map(row -> match(row.getEntity(), row.getScore())).toList();
    }

    @Override
    public Optional<Match> findMemory(String ownerId, String memoryId) {
        return client.query(QueryReq.builder().databaseName(database()).collectionName(collection())
                        .filter(recordFilter(ownerId, memoryId)).outputFields(FIELDS).limit(1)
                        .consistencyLevel(ConsistencyLevel.STRONG).build())
                .getQueryResults().stream().findFirst().map(row -> match(row.getEntity(), 1));
    }

    @Override
    public void deleteMemory(String ownerId, String memoryId) {
        delete(recordFilter(ownerId, memoryId));
    }

    @Override
    public void deleteMemories(String ownerId) {
        delete(ownerFilter(ownerId));
    }

    private void delete(String filter) {
        client.delete(DeleteReq.builder().databaseName(database()).collectionName(collection()).filter(filter).build());
    }

    private Match match(Map<String, Object> fields, double score) {
        try {
            Map<String, Object> metadata = mapper.readValue(text(fields.get(META)), new TypeReference<>() {});
            return new Match(text(fields.get(ID)), text(fields.get(OWNER)),
                    text(fields.get(TEXT)), metadata, Math.max(0, score));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("读取 Milvus 记忆元数据失败", e);
        }
    }

    private static String text(Object value) {
        return value instanceof JsonPrimitive primitive ? primitive.getAsString() : String.valueOf(value);
    }

    private String write(Map<String, Object> metadata) {
        try { return mapper.writeValueAsString(metadata); }
        catch (JsonProcessingException e) { throw new IllegalStateException("序列化 Milvus 记忆元数据失败", e); }
    }

    private static AddFieldReq varchar(String name, int maxLength) {
        return AddFieldReq.builder().fieldName(name).dataType(DataType.VarChar).maxLength(maxLength).build();
    }

    private static String ownerFilter(String ownerId) { return OWNER + " == " + JSON.toJson(ownerId); }
    private static String recordFilter(String ownerId, String id) {
        return ownerFilter(ownerId) + " and " + ID + " == " + JSON.toJson(id);
    }

    private String database() { return properties.getMilvus().getDatabaseName(); }
    private String collection() { return properties.getMilvus().getMemoryCollectionName(); }

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}
