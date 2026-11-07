package com.tianaiagent.toolgateway.messaging;

import com.tianaiagent.toolgateway.ToolAuditRecord;

import java.time.Instant;
import java.util.Set;

/** Versioned payload; never deserialize arbitrary class names from message headers. */
public record ToolAuditEvent(int schemaVersion, ToolAuditRecord audit) {
    public static ToolAuditEvent of(ToolAuditRecord record) { return new ToolAuditEvent(1, record); }

    public void validate() {
        if (schemaVersion != 1 || audit == null || audit.id() == null || !audit.id().matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Invalid audit event/version/id");
        }
        if (audit.toolName() == null || audit.toolName().isBlank() || audit.toolName().length() > 255
                || audit.status() == null || !Set.of("SUCCESS", "FAILED", "BLOCKED", "REJECTED").contains(audit.status())
                || audit.durationMs() < 0 || audit.createdAt() == null) {
            throw new IllegalArgumentException("Invalid audit event fields");
        }
        Instant.parse(audit.createdAt());
    }
}
