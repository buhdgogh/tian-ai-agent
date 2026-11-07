package com.tianaiagent.docmcp.index;

import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import com.tianaiagent.docmcp.storage.KnowledgeRepository.ChunkRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Comparator;

/** 默认单机实现：向量保存在 H2，检索时在 JVM 中计算余弦相似度。 */
@Component
@ConditionalOnProperty(prefix = "doc-mcp", name = "vector-store", havingValue = "local")
public class LocalVectorStore implements VectorStore {
    private final KnowledgeRepository repository;

    public LocalVectorStore(KnowledgeRepository repository) {
        this.repository = repository;
    }

    @Override
    public void replaceDocument(String documentId, List<ChunkRecord> previous, List<ChunkRecord> current) {
        // H2 的文档与 Chunk 由 KnowledgeRepository 在同一事务中替换。
    }

    @Override
    public List<VectorCandidate> search(float[] queryVector, int limit) {
        return repository.loadLeafChunks().stream()
                .map(chunk -> new VectorCandidate(chunk,
                        Math.max(0, EmbeddingService.cosine(queryVector, chunk.vector()))))
                .toList();
    }

    @Override
    public void deleteDocument(String documentId) {
        // H2 删除由 KnowledgeRepository 负责。
    }

    @Override
    public void upsertMemory(MemoryVector memory) {
        repository.upsertMemory(new KnowledgeRepository.MemoryRecord(memory.memoryId(), memory.ownerId(),
                memory.text(), memory.metadata(), memory.vector()));
    }

    @Override
    public List<MemoryCandidate> searchMemories(String ownerId, float[] queryVector, int limit) {
        return repository.loadMemories(ownerId).stream()
                .map(memory -> new MemoryCandidate(memory.memoryId(), memory.ownerId(), memory.text(),
                        memory.metadata(), Math.max(0, EmbeddingService.cosine(queryVector, memory.vector()))))
                .sorted(Comparator.comparingDouble(MemoryCandidate::score).reversed())
                .limit(Math.max(1, limit)).toList();
    }

    @Override
    public void deleteMemories(String ownerId) { repository.deleteMemories(ownerId); }

    @Override
    public boolean external() { return false; }

    @Override
    public String name() { return "h2-float32"; }
}
