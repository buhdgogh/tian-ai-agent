package com.tianaiagent.api.medical;

import java.util.List;

public record ChatRequest(String message, String chatId, String spaceId, String modelProviderId, List<String> skillIds,
                          boolean localWorkspace) {
    public ChatRequest(String message, String chatId, String spaceId) {
        this(message, chatId, spaceId, null);
    }

    public ChatRequest(String message, String chatId, String spaceId, String modelProviderId) {
        this(message, chatId, spaceId, modelProviderId, null);
    }

    public ChatRequest(String message, String chatId, String spaceId, String modelProviderId, List<String> skillIds) {
        this(message, chatId, spaceId, modelProviderId, skillIds, false);
    }

    public void validate() {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
        if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("会话 ID 只能包含字母、数字、下划线和连字符");
        }
        if (modelProviderId != null && !modelProviderId.matches("[A-Za-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("模型提供商 ID 无效");
        }
    }
}
