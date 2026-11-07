package com.tianaiagent.toolgateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.tianaiagent.toolgateway.MysqlToolAuditRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class ToolAuditConsumer {
    private final MysqlToolAuditRepository repository;
    private final RabbitToolAuditPublisher publisher;
    private final ObjectMapper mapper;
    private final ToolAuditRabbitProperties properties;

    public ToolAuditConsumer(MysqlToolAuditRepository repository, RabbitToolAuditPublisher publisher,
                             ObjectMapper mapper, ToolAuditRabbitProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.mapper = mapper;
        this.properties = properties;
    }

    @RabbitListener(queues = "${agent.tool-gateway.audit-rabbit.queue:tian.tool-audit.write.v1}",
            containerFactory = "toolAuditListenerContainerFactory",
            autoStartup = "${agent.tool-gateway.audit-rabbit.consumer-auto-startup:true}")
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        ToolAuditEvent event;
        int retries;
        try {
            event = mapper.readValue(message.getBody(), ToolAuditEvent.class);
            event.validate();
            if (!event.audit().id().equals(message.getMessageProperties().getMessageId())) {
                throw new IllegalArgumentException("Audit payload/message ID mismatch");
            }
            Object header = message.getMessageProperties().getHeader(RabbitToolAuditPublisher.RETRY_HEADER);
            retries = header == null ? 0 : Integer.parseInt(header.toString());
            if (retries < 0 || retries > 3) throw new IllegalArgumentException("Invalid audit retry header");
        } catch (Exception malformed) {
            log.error("非法工具审计消息转入死信队列 messageId={}", message.getMessageProperties().getMessageId());
            channel.basicReject(tag, false);
            return;
        }

        try {
            repository.save(event.audit()); // JDBC auto-commit completes before ACK; duplicate event IDs are harmless.
        } catch (RuntimeException databaseFailure) {
            if (retries >= properties.maxRetries()) {
                log.error("工具审计落库重试耗尽，转入死信队列 auditId={}, retries={}", event.audit().id(), retries,
                        databaseFailure);
                channel.basicReject(tag, false);
                return;
            }
            try {
                publisher.retry(message, retries + 1);
            } catch (RuntimeException publishFailure) {
                log.error("审计重试消息发送未确认，保留原消息 auditId={}", event.audit().id());
                channel.basicNack(tag, false, true);
                return;
            }
            // The durable retry queue owns the message only after its publisher confirm.
            channel.basicAck(tag, false);
            log.warn("工具审计写库失败，已安排重试 auditId={}, retry={}", event.audit().id(), retries + 1);
            return;
        }
        channel.basicAck(tag, false);
        log.debug("工具审计已落库并确认 auditId={}", event.audit().id());
    }
}
