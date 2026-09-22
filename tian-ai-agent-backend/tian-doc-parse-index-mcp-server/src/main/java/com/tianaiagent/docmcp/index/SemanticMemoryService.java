package com.tianaiagent.docmcp.index;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 使用独立 Milvus Collection（本地模式使用 H2）保存会话长期语义记忆。 */
@Service
public class SemanticMemoryService {
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;

    public SemanticMemoryService(VectorStore vectorStore, EmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
    }

    public void upsert(String ownerId, String memoryId, String text, Map<String, Object> metadata) {
        String safeOwner = identifier(ownerId, "owner_id", 300);
        String safeId = identifier(memoryId, "memory_id", 128);
        String safeText = required(text, "text", 20_000);
        vectorStore.upsertMemory(new VectorStore.MemoryVector(safeId, safeOwner, safeText,
                metadata == null ? Map.of() : metadata, embeddingService.embedDocuments(List.of(safeText)).getFirst()));
    }

    public List<VectorStore.MemoryCandidate> search(String ownerId, String query, int limit) {
        String safeOwner = identifier(ownerId, "owner_id", 300);
        String safeQuery = required(query, "query", 2_000);
        return vectorStore.searchMemories(safeOwner, embeddingService.embedQuery(safeQuery),
                Math.max(1, Math.min(limit, 10)));
    }

    public void delete(String ownerId) {
        vectorStore.deleteMemories(identifier(ownerId, "owner_id", 300));
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
