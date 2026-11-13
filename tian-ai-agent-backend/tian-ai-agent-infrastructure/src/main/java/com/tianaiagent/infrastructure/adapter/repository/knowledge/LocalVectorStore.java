package com.tianaiagent.infrastructure.adapter.repository.knowledge;

import com.tianaiagent.domain.knowledge.adapter.port.VectorStore;
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway.Match;
import com.tianaiagent.infrastructure.gateway.model.EmbeddingService;

import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;

import java.util.List;
import java.util.Comparator;

/** 默认单机实现：向量保存在 SQLite，检索时在 JVM 中计算余弦相似度。 */
public class LocalVectorStore implements VectorStore, MemoryVectorStorage {
    private final MybatisKnowledgeRepository repository;

    public LocalVectorStore(MybatisKnowledgeRepository repository) {
        this.repository = repository;
    }

    @Override
    public void replaceDocument(KnowledgeSpace space, List<ChunkRecord> previous, List<ChunkRecord> current) {
        // SQLite 的文档与 Chunk 由 MybatisKnowledgeRepository 在同一事务中替换。
    }

    @Override
    public List<VectorCandidate> search(KnowledgeSpace space, float[] queryVector, int limit) {
        return repository.loadLeafChunks(space.id()).stream()
                .map(chunk -> new VectorCandidate(chunk,
                        Math.max(0, EmbeddingService.cosine(queryVector, chunk.vector()))))
                .toList();
    }

    @Override
    public void deleteDocument(KnowledgeSpace space, String documentId) {
        // SQLite 删除由 MybatisKnowledgeRepository 负责。
    }

    @Override
    public void deleteSpace(KnowledgeSpace space) {
        // SQLite 删除由 MybatisKnowledgeRepository 负责。
    }

    @Override
    public void upsertMemory(MemoryVector memory) {
        repository.upsertMemory(new MybatisKnowledgeRepository.MemoryRecord(memory.memoryId(), memory.ownerId(),
                memory.text(), memory.metadata(), memory.vector()));
    }

    @Override
    public List<Match> searchMemories(String ownerId, float[] queryVector, int limit) {
        return repository.loadMemories(ownerId).stream()
                .map(memory -> new Match(memory.memoryId(), memory.ownerId(), memory.text(),
                        memory.metadata(), Math.max(0, EmbeddingService.cosine(queryVector, memory.vector()))))
                .sorted(Comparator.comparingDouble(Match::score).reversed())
                .limit(Math.max(1, limit)).toList();
    }

    @Override
    public java.util.Optional<Match> findMemory(String ownerId, String memoryId) {
        return repository.loadMemories(ownerId).stream().filter(memory -> memoryId.equals(memory.memoryId()))
                .map(memory -> new Match(memory.memoryId(), memory.ownerId(), memory.text(), memory.metadata(), 1))
                .findFirst();
    }

    @Override
    public void deleteMemory(String ownerId, String memoryId) { repository.deleteMemory(ownerId, memoryId); }

    @Override
    public void deleteMemories(String ownerId) { repository.deleteMemories(ownerId); }

    @Override
    public boolean external() { return false; }

    @Override
    public String name() { return "sqlite-float32"; }
}
