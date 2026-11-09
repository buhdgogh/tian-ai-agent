package com.tianaiagent.domain.knowledge.model.entity;

import java.util.Map;

public record ChunkRecord(String nodeId, String docId, String parentId, int level,
                          boolean leaf, int ordinal, String title, String sourceUri,
                          String contentSha256, String text, Map<String, Object> metadata,
                          float[] vector) {}
