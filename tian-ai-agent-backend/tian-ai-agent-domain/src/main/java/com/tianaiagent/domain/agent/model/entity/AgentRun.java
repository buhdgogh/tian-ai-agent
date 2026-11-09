package com.tianaiagent.domain.agent.model.entity;

import java.time.Instant;
import java.util.List;

public record AgentRun(Summary summary, List<Event> events) {
    public record Summary(String id, String ownerId, String kind, String taskId, String parentRunId,
                          String status, Instant startedAt, long elapsedMs, int eventCount,
                          Long inputTokens, Long outputTokens, Long totalTokens) {}

    public record Event(String kind, String name, String provider, String model, String status,
                        Instant startedAt, long elapsedMs, Integer inputTokens, Integer outputTokens,
                        Integer totalTokens, String reason) {}
}
