package com.tianaiagent.domain.agent.model.entity;

import java.time.Instant;
import java.util.List;

public record AgentRun(Summary summary, List<Event> events) {
    public record Summary(String id, String ownerId, String kind, String taskId, String parentRunId,
                          String status, Instant startedAt, long elapsedMs, int eventCount,
                          Long inputTokens, Long outputTokens, Long totalTokens, String traceId) {
        public Summary {
            if (traceId == null) traceId = parentRunId == null ? id : parentRunId;
        }

        public Summary(String id, String ownerId, String kind, String taskId, String parentRunId,
                       String status, Instant startedAt, long elapsedMs, int eventCount,
                       Long inputTokens, Long outputTokens, Long totalTokens) {
            this(id, ownerId, kind, taskId, parentRunId, status, startedAt, elapsedMs, eventCount,
                    inputTokens, outputTokens, totalTokens, null);
        }
    }

    public record Event(String kind, String name, String provider, String model, String status,
                        Instant startedAt, long elapsedMs, Integer inputTokens, Integer outputTokens,
                        Integer totalTokens, String reason, String callId, String argumentDigest) {
        public Event(String kind, String name, String provider, String model, String status,
                     Instant startedAt, long elapsedMs, Integer inputTokens, Integer outputTokens,
                     Integer totalTokens, String reason) {
            this(kind, name, provider, model, status, startedAt, elapsedMs, inputTokens, outputTokens,
                    totalTokens, reason, null, null);
        }
    }
}
