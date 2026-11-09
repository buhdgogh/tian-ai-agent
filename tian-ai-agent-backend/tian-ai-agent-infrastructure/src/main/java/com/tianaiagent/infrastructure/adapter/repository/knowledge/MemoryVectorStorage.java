package com.tianaiagent.infrastructure.adapter.repository.knowledge;

import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway.Match;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MemoryVectorStorage {
    void upsertMemory(MemoryVector memory);

    List<Match> searchMemories(String ownerId, float[] queryVector, int limit);

    Optional<Match> findMemory(String ownerId, String memoryId);

    void deleteMemory(String ownerId, String memoryId);

    void deleteMemories(String ownerId);

    record MemoryVector(String memoryId, String ownerId, String text,
                        Map<String, Object> metadata, float[] vector) {}
}
