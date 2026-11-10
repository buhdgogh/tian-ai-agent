package com.tianaiagent.domain.knowledge.adapter.port;


import java.util.Optional;

public interface KnowledgeAnswerCache {
    default Optional<Hit> get(String spaceId, String question, int topK, String version) {
        return get(spaceId, question, topK, version, true);
    }

    Optional<Hit> get(String spaceId, String question, int topK, String version, boolean semantic);

    default void put(String spaceId, String question, int topK, String version, KnowledgeIndex.Answer result) {
        put(spaceId, question, topK, version, result, true);
    }

    void put(String spaceId, String question, int topK, String version, KnowledgeIndex.Answer result, boolean semantic);
    void evictSpace(String spaceId);

    record Hit(KnowledgeIndex.Answer answer, Mode mode) {}
    enum Mode { EXACT, SEMANTIC }
}
