package com.tianaiagent.domain.conversation.adapter.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SemanticMemoryGateway {
    void upsert(String ownerId, String memoryId, String text, Map<String, Object> metadata);

    List<Match> search(String ownerId, String query, int limit);

    Optional<Match> find(String ownerId, String memoryId);

    void delete(String ownerId, String memoryId);

    /** Returns only after confirming deletion, including the case where no memory exists. */
    void delete(String ownerId);

    record Match(String memoryId, String ownerId, String text, Map<String, Object> metadata, double score) {}
}
