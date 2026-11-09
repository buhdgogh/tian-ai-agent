package com.tianaiagent.infrastructure.adapter.repository.knowledge;

import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 使用独立 Milvus Collection（本地模式使用 H2）保存会话长期语义记忆。 */
public class VectorSemanticMemoryGateway implements SemanticMemoryGateway {
    private final MemoryVectorStorage storage;
    private final TextEmbedding embeddingService;

    public VectorSemanticMemoryGateway(MemoryVectorStorage storage, TextEmbedding embeddingService) {
        this.storage = storage;
        this.embeddingService = embeddingService;
    }

    @Override
    public void upsert(String ownerId, String memoryId, String text, Map<String, Object> metadata) {
        String safeOwner = identifier(ownerId, "owner_id", 300);
        String safeId = identifier(memoryId, "memory_id", 128);
        String safeText = required(text, "text", 20_000);
        storage.upsertMemory(new MemoryVectorStorage.MemoryVector(safeId, safeOwner, safeText,
                metadata == null ? Map.of() : metadata, embeddingService.embedDocuments(List.of(safeText)).getFirst()));
    }

    @Override
    public List<Match> search(String ownerId, String query, int limit) {
        String safeOwner = identifier(ownerId, "owner_id", 300);
        String safeQuery = required(query, "query", 2_000);
        return storage.searchMemories(safeOwner, embeddingService.embedQuery(safeQuery),
                Math.max(1, Math.min(limit, 10)));
    }

    @Override
    public Optional<Match> find(String ownerId, String memoryId) {
        return storage.findMemory(identifier(ownerId, "owner_id", 300), identifier(memoryId, "memory_id", 128));
    }

    @Override
    public void delete(String ownerId, String memoryId) {
        storage.deleteMemory(identifier(ownerId, "owner_id", 300), identifier(memoryId, "memory_id", 128));
    }

    @Override
    public void delete(String ownerId) {
        storage.deleteMemories(identifier(ownerId, "owner_id", 300));
    }

    private static String identifier(String value, String name, int max) {
        String safe = required(value, name, max);
        if (!safe.matches("[A-Za-z0-9:_-]+")) throw new IllegalArgumentException(name + "格式非法");
        return safe;
    }
    private static String required(String value, String name, int max) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        if (safe.length() > max) throw new IllegalArgumentException(name + "长度不能超过" + max);
        return safe;
    }
}
