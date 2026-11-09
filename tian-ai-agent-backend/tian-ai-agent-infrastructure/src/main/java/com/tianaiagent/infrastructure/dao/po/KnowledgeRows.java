package com.tianaiagent.infrastructure.dao.po;

public final class KnowledgeRows {
    private KnowledgeRows() {}

    public record Space(String spaceId, String name, boolean medical, boolean deleting) {}

    public record Source(String docId, String title, String updatedAt, String metadataJson) {}

    public record Document(String docId, String title, String sourceUri, String contentSha256,
                           String metadataJson, String updatedAt, String spaceId) {}

    public record Chunk(String nodeId, String docId, String parentId, int levelNo, boolean leaf, int ordinalNo,
                        String title, String sourceUri, String contentSha256, String chunkText, String metadataJson,
                        byte[] vector, String spaceId) {}

    public record Memory(String memoryId, String ownerId, String memoryText, String metadataJson,
                         byte[] vector, String updatedAt) {}
}
