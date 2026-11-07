package com.tianaiagent.toolgateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.toolgateway.ToolAuditPublisher;
import com.tianaiagent.toolgateway.ToolAuditRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class RabbitToolAuditPublisher implements ToolAuditPublisher {
    public static final String RETRY_HEADER = "audit-retry-count";
    private final RabbitTemplate template;
    private final ObjectMapper mapper;
    private final ToolAuditRabbitProperties properties;

    public RabbitToolAuditPublisher(@Qualifier("toolAuditRabbitTemplate") RabbitTemplate template,
                                   ObjectMapper mapper, ToolAuditRabbitProperties properties) {
        this.template = template;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void publish(ToolAuditRecord audit) {
        ToolAuditEvent event = ToolAuditEvent.of(audit);
        event.validate();
        try {
            Message message = MessageBuilder.withBody(mapper.writeValueAsBytes(event))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding("UTF-8")
                    .setMessageId(audit.id())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setHeader(RETRY_HEADER, 0).build();
            sendConfirmed(properties.exchange(), properties.routingKey(), message);
        } catch (AuditPublishException error) { throw error; }
        catch (Exception error) { throw new AuditPublishException(audit.id(), error); }
    }

    public void retry(Message original, int retry) {
        if (retry < 1 || retry > properties.maxRetries()) throw new IllegalArgumentException("Invalid audit retry number");
        Message message = MessageBuilder.fromClonedMessage(original)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setHeader(RETRY_HEADER, retry).build();
        // Copy business payload/identity, not the previous delivery's consumer state or expiry.
        message.getMessageProperties().setExpiration(null);
        sendConfirmed(properties.retryExchange(), properties.retryRoutingKey(retry), message);
    }

    private void sendConfirmed(String exchange, String key, Message message) {
        String auditId = message.getMessageProperties().getMessageId();
        // Correlation IDs must be unique even when a delivery with the same event ID is retried.
        var correlation = new CorrelationData(auditId + ":" + UUID.randomUUID());
        try {
            template.send(exchange, key, message, correlation);
            var confirm = correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException(correlation.getReturned() != null ? "Audit message unroutable" : "Broker NACK");
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("工具审计消息发送未确认 auditId={}, exchange={}, routingKey={}", auditId, exchange, key);
            throw new AuditPublishException(auditId, error);
        }
    }

    public static class AuditPublishException extends IllegalStateException {
        public AuditPublishException(String id, Throwable cause) {
            super("工具已执行，但审计消息发送未确认，auditId=" + id + "；请补偿审计，不要据此重复执行工具", cause);
        }
    }
}
