package com.tianaiagent.toolgateway;

/** Capture the full audit, including execution context, before crossing the asynchronous boundary. */
@FunctionalInterface
public interface ToolAuditPublisher {
    void publish(ToolAuditRecord audit);
}
