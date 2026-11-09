package com.tianaiagent.domain.knowledge.adapter.port;


import java.util.Optional;

public interface KnowledgeAnswerCache {
    Optional<Hit> get(String spaceId, String question, int topK, String version);

    void put(String spaceId, String question, int topK, String version, KnowledgeIndex.Answer result);
    void evictSpace(String spaceId);

    record Hit(KnowledgeIndex.Answer answer, Mode mode) {}
    enum Mode { EXACT, SEMANTIC }
}
