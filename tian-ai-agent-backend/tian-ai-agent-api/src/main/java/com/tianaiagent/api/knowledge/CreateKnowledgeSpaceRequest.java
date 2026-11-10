package com.tianaiagent.api.knowledge;

public record CreateKnowledgeSpaceRequest(String name, boolean medical, String embeddingProviderId) {}
