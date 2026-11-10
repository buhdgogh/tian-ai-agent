package com.tianaiagent.domain.knowledge.adapter.port;

import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import java.util.List;
import java.util.Map;

public interface KnowledgeIndex {
    KnowledgeSpace createSpace(String name, boolean medical, String ownerId, String embeddingProviderId);

    List<KnowledgeSpace> listSpaces();

    KnowledgeSpace space(String spaceId);
    void deleteSpace(String spaceId, Runnable cleanup);

    Answer queryWithSources(String spaceId, String question, int topK);

    Retrieval retrieve(String spaceId, String question, int topK);

    DeleteResult delete(String spaceId, String docId);

    record DeleteResult(String docId, boolean alreadyAbsent) {}

    record Evidence(int index, String chunkId, String parentId, int level, String documentId, String title,
                    String sourceUri, double score, double denseScore, double keywordScore, String text,
                    Map<String, Object> metadata) {}

    record Answer(String question, String answer, List<Evidence> sources, String retrievalMode,
                  String evidenceLevel, String evidenceReason, Boolean requiresHumanReview) {}

    record Retrieval(String question, List<Evidence> sources, String mode, int topK, int rerankTopN,
                     String rerankModel, List<String> queries) {}
}
