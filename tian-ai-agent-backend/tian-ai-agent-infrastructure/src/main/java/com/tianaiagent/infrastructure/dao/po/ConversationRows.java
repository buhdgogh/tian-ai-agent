package com.tianaiagent.infrastructure.dao.po;

import java.time.LocalDateTime;

public final class ConversationRows {
    private ConversationRows() {}

    public record Summary(String storageId, String title, LocalDateTime updatedAt) {}

    public record Message(String role, String content, String toolCalls, String toolResponses) {}
}
