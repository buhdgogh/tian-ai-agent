package com.tianaiagent.infrastructure.adapter.repository.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tianaiagent.domain.knowledge.model.aggregate.ParseTask;
import com.tianaiagent.domain.knowledge.adapter.repository.ParseTaskRepository;
import com.tianaiagent.domain.knowledge.adapter.repository.KnowledgeRepository;
import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import jakarta.annotation.PostConstruct;
import com.tianaiagent.infrastructure.dao.knowledge.KnowledgeMapper;
import com.tianaiagent.infrastructure.dao.po.KnowledgeRows;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MybatisKnowledgeRepository implements KnowledgeRepository, ParseTaskRepository {
    private final KnowledgeMapper knowledge;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public MybatisKnowledgeRepository(KnowledgeMapper knowledge, TransactionTemplate transactions, ObjectMapper mapper) {
        this.knowledge = knowledge;
        this.transactions = transactions;
        this.mapper = mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @PostConstruct
    void seedMedicalSpace() {
        var space = KnowledgeSpace.MEDICAL;
        knowledge.seedSpace(space.id(), space.name(), space.medical());
    }

    public synchronized void saveTask(ParseTask task) {
        task.setUpdatedAt(Instant.now().toString());
        try {
            knowledge.saveTask(task.getTaskId(), mapper.writeValueAsString(task), task.getUpdatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析任务序列化失败", e);
        }
    }

    public Optional<ParseTask> findTask(String taskId) {
        return Optional.ofNullable(knowledge.task(taskId)).map(this::readParseTask);
    }

    @Override
    public List<String> findCacheKeysBySpace(String spaceId) {
        return knowledge.tasksMatching("%\"space_id\":\"" + spaceId + "\"%")
                .stream().map(this::readParseTask).flatMap(task -> task.getItems().stream())
                .filter(item -> spaceId.equals(item.getSpaceId()))
                .map(ParseTask.ParseItem::getCacheKey).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private ParseTask readParseTask(String payload) {
        try {
            return mapper.readValue(payload, ParseTask.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析任务数据损坏", e);
        }
    }

    public void addSpace(KnowledgeSpace space) {
        knowledge.insertSpace(space.id(), space.name(), space.medical());
    }

    public Optional<KnowledgeSpace> findSpace(String spaceId) {
        return Optional.ofNullable(knowledge.space(spaceId)).map(this::mapSpace);
    }

    public List<KnowledgeSpace> listSpaces() {
        return knowledge.spaces().stream().map(this::mapSpace).toList();
    }

    private KnowledgeSpace mapSpace(KnowledgeRows.Space row) {
        return new KnowledgeSpace(row.spaceId(), row.name(), row.medical(), row.deleting());
    }

    @Override
    public void markSpaceDeleting(String spaceId) {
        knowledge.markSpaceDeleting(spaceId);
    }

    @Override
    public void deleteSpace(String spaceId) {
        transactions.executeWithoutResult(status -> {
            knowledge.deleteSpaceChunks(spaceId);
            knowledge.deleteSpaceDocuments(spaceId);
            knowledge.deleteSpaceMarkers(spaceId);
            knowledge.deleteSpace(spaceId);
        });
    }

    public List<WikiSource> wikiSources(String spaceId, int limit) {
        return knowledge.wikiSources(spaceId, limit).stream()
                .map(row -> new WikiSource(row.docId(), row.title(), row.updatedAt(), readMap(row.metadataJson()))).toList();
    }

    public Optional<String> findDocumentSpace(String docId) {
        return knowledge.documentSpaces(docId).stream().findFirst();
    }

    public void replaceDocument(String spaceId, DocumentRecord document, List<ChunkRecord> chunks) {
        transactions.executeWithoutResult(status -> {
            knowledge.deleteChunks(spaceId, document.docId());
            knowledge.deleteDocument(spaceId, document.docId());
            knowledge.insertDocument(new KnowledgeRows.Document(document.docId(), document.title(), document.sourceUri(),
                    document.contentSha256(), writeJson(document.metadata()), Instant.now().toString(), spaceId));
            for (ChunkRecord chunk : chunks) {
                knowledge.insertChunk(new KnowledgeRows.Chunk(chunk.nodeId(), chunk.docId(), chunk.parentId(),
                        chunk.level(), chunk.leaf(), chunk.ordinal(), chunk.title(), chunk.sourceUri(), chunk.contentSha256(),
                        chunk.text(), writeJson(chunk.metadata()), floatsToBytes(chunk.vector()), spaceId));
            }
        });
    }

    public void markDeleted(String spaceId, String docId) {
        knowledge.markDeleted(spaceId, docId);
    }

    @Override
    public boolean isDeleted(String spaceId, String docId) {
        return knowledge.countDeleted(spaceId, docId) > 0;
    }

    public boolean deleteDocument(String spaceId, String docId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            knowledge.deleteChunks(spaceId, docId);
            return knowledge.deleteDocument(spaceId, docId) > 0;
        }));
    }

    public List<ChunkRecord> loadLeafChunks(String spaceId) {
        return knowledge.leafChunks(spaceId).stream().map(this::mapChunk).toList();
    }

    public List<ChunkRecord> loadDocumentChunks(String spaceId, String docId) {
        return knowledge.documentChunks(spaceId, docId).stream().map(this::mapChunk).toList();
    }

    public Optional<ChunkRecord> findChunk(String spaceId, String nodeId) {
        return Optional.ofNullable(knowledge.chunk(spaceId, nodeId)).map(this::mapChunk);
    }

    public void upsertMemory(MemoryRecord memory) {
        knowledge.saveMemory(new KnowledgeRows.Memory(memory.memoryId(), memory.ownerId(), memory.text(),
                writeJson(memory.metadata()), floatsToBytes(memory.vector()), Instant.now().toString()));
    }

    public List<MemoryRecord> loadMemories(String ownerId) {
        return knowledge.memories(ownerId).stream().map(row -> new MemoryRecord(row.memoryId(), row.ownerId(),
                row.memoryText(), readMap(row.metadataJson()), bytesToFloats(row.vector()))).toList();
    }

    public void deleteMemory(String ownerId, String memoryId) {
        knowledge.deleteMemory(ownerId, memoryId);
    }

    public void deleteMemories(String ownerId) {
        knowledge.deleteMemories(ownerId);
    }

    public Map<String, Object> status(String spaceId) {
        return Map.of("documents", knowledge.countDocuments(spaceId), "chunks", knowledge.countChunks(spaceId, false),
                "vectors", knowledge.countChunks(spaceId, true));
    }

    private ChunkRecord mapChunk(KnowledgeRows.Chunk row) {
        return new ChunkRecord(row.nodeId(), row.docId(), row.parentId(), row.levelNo(), row.leaf(), row.ordinalNo(),
                row.title(), row.sourceUri(), row.contentSha256(), row.chunkText(), readMap(row.metadataJson()),
                bytesToFloats(row.vector()));
    }

    private String writeJson(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try { return mapper.readValue(value, Map.class); }
        catch (JsonProcessingException e) { return Map.of(); }
    }

    static byte[] floatsToBytes(float[] values) {
        if (values == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }

    static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < result.length; i++) result[i] = buffer.getFloat();
        return result;
    }

    public record MemoryRecord(String memoryId, String ownerId, String text,
                               Map<String, Object> metadata, float[] vector) {}
}
