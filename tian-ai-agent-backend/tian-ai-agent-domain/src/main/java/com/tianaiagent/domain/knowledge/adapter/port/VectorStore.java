package com.tianaiagent.domain.knowledge.adapter.port;

import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;

import java.util.List;
import java.util.Map;

/** 叶子 Chunk 向量的持久化与候选召回接口。 */
public interface VectorStore {
    void replaceDocument(KnowledgeSpace space, List<ChunkRecord> previous, List<ChunkRecord> current);

    List<VectorCandidate> search(KnowledgeSpace space, float[] queryVector, int limit);

    void deleteDocument(KnowledgeSpace space, String documentId);
    void deleteSpace(KnowledgeSpace space);

    /** 外部向量库启用时，SQLite 不再重复保存向量 BLOB。 */
    boolean external();

    String name();

    default Map<String, Object> status(KnowledgeSpace space) {
        return Map.of("name", name());
    }

    record VectorCandidate(ChunkRecord chunk, double denseScore) {}
}
