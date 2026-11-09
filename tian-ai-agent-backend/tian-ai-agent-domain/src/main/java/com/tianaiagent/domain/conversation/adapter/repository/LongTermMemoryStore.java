package com.tianaiagent.domain.conversation.adapter.repository;

import java.util.List;
import java.util.Optional;

public interface LongTermMemoryStore {
    Optional<StructuredMemory> find(String ownerId);

    int delete(String ownerId);

    record StructuredMemory(String ownerId, String summary, List<String> confirmedFacts,
                            int sourceMessageCount, String updatedAt) {}
}
