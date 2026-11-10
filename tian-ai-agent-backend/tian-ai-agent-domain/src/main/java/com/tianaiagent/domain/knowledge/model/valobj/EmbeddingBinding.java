package com.tianaiagent.domain.knowledge.model.valobj;

public record EmbeddingBinding(String ownerId, String providerId, String baseUrl, String model, int dimensions) {}
