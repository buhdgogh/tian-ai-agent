package com.tianaiagent.domain.knowledge.adapter.repository;

import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeRepository {
    void addSpace(KnowledgeSpace space);

    Optional<KnowledgeSpace> findSpace(String spaceId);

    List<KnowledgeSpace> listSpaces();

    void markSpaceDeleting(String spaceId);

    void deleteSpace(String spaceId);

    List<WikiSource> wikiSources(String spaceId, int limit);

    Optional<String> findDocumentSpace(String docId);

    void replaceDocument(String spaceId, DocumentRecord document, List<ChunkRecord> chunks);

    boolean deleteDocument(String spaceId, String docId);

    void markDeleted(String spaceId, String docId);

    boolean isDeleted(String spaceId, String docId);

    List<ChunkRecord> loadDocumentChunks(String spaceId, String docId);

    Optional<ChunkRecord> findChunk(String spaceId, String nodeId);

    Map<String, Object> status(String spaceId);

    record WikiSource(String docId, String title, String revision, Map<String, Object> metadata) {}

    record DocumentRecord(String docId, String title, String sourceUri,
                          String contentSha256, Map<String, Object> metadata) {}
}
