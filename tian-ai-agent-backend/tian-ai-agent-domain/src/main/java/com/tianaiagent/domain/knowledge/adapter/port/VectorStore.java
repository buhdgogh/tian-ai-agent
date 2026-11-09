package com.tianaiagent.domain.knowledge.adapter.port;

import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;

import java.util.List;
import java.util.Map;

/** 叶子 Chunk 向量的持久化与候选召回接口。 */
public interface VectorStore {
    void replaceDocument(String spaceId, List<ChunkRecord> previous, List<ChunkRecord> current);

    List<VectorCandidate> search(String spaceId, float[] queryVector, int limit);

    void deleteDocument(String spaceId, String documentId);
    void deleteSpace(String spaceId);

    /** 外部向量库启用时，H2 不再重复保存向量 BLOB。 */
    boolean external();

    String name();

    default Map<String, Object> status(String spaceId) {
        return Map.of("name", name());
    }

    record VectorCandidate(ChunkRecord chunk, double denseScore) {}
}
