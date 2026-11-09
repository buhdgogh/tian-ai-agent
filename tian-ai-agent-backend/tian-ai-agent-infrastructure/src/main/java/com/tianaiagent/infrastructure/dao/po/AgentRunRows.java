package com.tianaiagent.infrastructure.dao.po;

import java.time.LocalDateTime;

public final class AgentRunRows {
    private AgentRunRows() {}

    public record Summary(String id, String ownerId, String kind, String taskId, String parentRunId,
                          String status, LocalDateTime startedAt, long elapsedMs, int eventCount,
                          Long inputTokens, Long outputTokens, Long totalTokens) {}

    public record Event(String runId, int eventNo, String kind, String name, String providerId, String modelName,
                        String status, LocalDateTime startedAt, long elapsedMs, Integer inputTokens,
                        Integer outputTokens, Integer totalTokens, String reason) {}
}
