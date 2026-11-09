package com.tianaiagent.infrastructure.dao.po;

public record ModelProviderRow(String ownerId, String providerId, int sortOrder, String protocol, String baseUrl,
                               String modelName, byte[] apiKeyCiphertext, int priority, boolean enabled,
                               String workspaceId, boolean streamUsage, String reasoningEffort) {}
