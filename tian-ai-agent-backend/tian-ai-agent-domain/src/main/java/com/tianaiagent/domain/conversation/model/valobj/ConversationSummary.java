package com.tianaiagent.domain.conversation.model.valobj;

import java.time.Instant;

public record ConversationSummary(String id, String title, Instant updatedAt) {}
