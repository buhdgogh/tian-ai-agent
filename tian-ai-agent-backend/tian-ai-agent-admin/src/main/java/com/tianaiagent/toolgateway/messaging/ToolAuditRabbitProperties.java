package com.tianaiagent.toolgateway.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.tool-gateway.audit-rabbit")
public record ToolAuditRabbitProperties(String exchange, String queue, String routingKey,
                                        String retryExchange, String retryQueuePrefix,
                                        String deadLetterExchange, String deadLetterQueue, String deadLetterRoutingKey,
                                        @DefaultValue("3") int maxRetries,
                                        @DefaultValue("1000") long retryDelayMs,
                                        @DefaultValue("5000") long confirmTimeoutMs) {
    public ToolAuditRabbitProperties {
        exchange = name(exchange, "tian.tool-audit.v1");
        queue = name(queue, "tian.tool-audit.write.v1");
        routingKey = name(routingKey, "write");
        retryExchange = name(retryExchange, "tian.tool-audit.retry.v1");
        retryQueuePrefix = name(retryQueuePrefix, "tian.tool-audit.retry.v1");
        deadLetterExchange = name(deadLetterExchange, "tian.tool-audit.dead.v1");
        deadLetterQueue = name(deadLetterQueue, "tian.tool-audit.dead.v1");
        deadLetterRoutingKey = name(deadLetterRoutingKey, "dead");
        // "3 retries" means the initial write plus up to three further writes.
        if (maxRetries < 0 || maxRetries > 3 || retryDelayMs < 1 || retryDelayMs > 3_600_000
                || confirmTimeoutMs < 1 || confirmTimeoutMs > 60_000) {
            throw new IllegalArgumentException("Invalid tool-audit RabbitMQ retry/confirm settings");
        }
    }

    public String retryQueue(int retry) { return retryQueuePrefix + "." + retry; }
    public String retryRoutingKey(int retry) { return "retry." + retry; }
    public int retryTtl(int retry) { return Math.toIntExact(retryDelayMs * (1L << (retry - 1))); }
    private static String name(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
