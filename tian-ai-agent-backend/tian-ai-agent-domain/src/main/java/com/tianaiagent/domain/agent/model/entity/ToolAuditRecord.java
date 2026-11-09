package com.tianaiagent.domain.agent.model.entity;

public record ToolAuditRecord(
        String id,
        String toolName,
        String status,
        long durationMs,
        String argumentDigest,
        String error,
        String createdAt
) {}
