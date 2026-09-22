package com.tianaiagent.docmcp.index;

import com.tianaiagent.docmcp.storage.KnowledgeRepository.ChunkRecord;

import java.util.List;
import java.util.Map;

/** 叶子 Chunk 向量的持久化与候选召回接口。 */
public interface VectorStore {
    void replaceDocument(String documentId, List<ChunkRecord> previous, List<ChunkRecord> current);

    List<VectorCandidate> search(float[] queryVector, int limit);

    void deleteDocument(String documentId);

    void upsertMemory(MemoryVector memory);

    List<MemoryCandidate> searchMemories(String ownerId, float[] queryVector, int limit);

    void deleteMemories(String ownerId);

    /** 外部向量库启用时，H2 不再重复保存向量 BLOB。 */
    boolean external();

    String name();

    default Map<String, Object> status() {
        return Map.of("name", name());
    }

    record VectorCandidate(ChunkRecord chunk, double denseScore) {}
    record MemoryVector(String memoryId, String ownerId, String text,
                        Map<String, Object> metadata, float[] vector) {}
    record MemoryCandidate(String memoryId, String ownerId, String text,
                           Map<String, Object> metadata, double score) {}
}
