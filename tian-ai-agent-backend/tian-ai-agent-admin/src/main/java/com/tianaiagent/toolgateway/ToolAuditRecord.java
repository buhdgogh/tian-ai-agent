package com.tianaiagent.toolgateway;

public record ToolAuditRecord(
        String id,
        String toolName,
        String status,
        long durationMs,
        String argumentDigest,
        String error,
        String createdAt,
        String taskId,
        String ownerId,
        String chatId,
        String stepId,
        Integer iteration,
        String toolCallId
) {}
