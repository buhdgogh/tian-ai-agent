package com.tianaiagent.api.medical;

public record ChatRequest(String message, String chatId, String spaceId, String modelProviderId) {
    public ChatRequest(String message, String chatId, String spaceId) {
        this(message, chatId, spaceId, null);
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
