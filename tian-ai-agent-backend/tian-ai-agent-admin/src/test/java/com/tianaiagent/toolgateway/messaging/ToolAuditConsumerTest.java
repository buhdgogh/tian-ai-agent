package com.tianaiagent.toolgateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.tianaiagent.toolgateway.MysqlToolAuditRepository;
import com.tianaiagent.toolgateway.ToolAuditRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolAuditConsumerTest {
    private final MysqlToolAuditRepository repository = mock(MysqlToolAuditRepository.class);
    private final RabbitToolAuditPublisher publisher = mock(RabbitToolAuditPublisher.class);
    private final Channel channel = mock(Channel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolAuditConsumer consumer = new ToolAuditConsumer(repository, publisher, mapper, properties());

    static ToolAuditRabbitProperties properties() {
        return new ToolAuditRabbitProperties(null, null, null, null, null, null, null, null, 3, 1000, 100);
    }

    static ToolAuditRecord audit(String status) {
        return new ToolAuditRecord("a".repeat(32), "searchPubMed", status, 123, "digest",
                "FAILED".equals(status) ? "tool unavailable" : null, "2026-10-29T00:00:00Z",
                "task1", "user1", "chat1", "step1", 1, "call1");
    }

    Message message(int retries) throws Exception {
        var result = MessageBuilder.withBody(mapper.writeValueAsBytes(ToolAuditEvent.of(audit("FAILED"))))
                .setMessageId(audit("FAILED").id()).setHeader(RabbitToolAuditPublisher.RETRY_HEADER, retries).build();
        result.getMessageProperties().setDeliveryTag(7);
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "BLOCKED", "REJECTED"})
    void commitsEveryToolOutcomeBeforeAck(String status) throws Exception {
        var record = audit(status);
        var message = MessageBuilder.withBody(mapper.writeValueAsBytes(ToolAuditEvent.of(record))).setMessageId(record.id()).build();
        message.getMessageProperties().setDeliveryTag(7);
        consumer.consume(message, channel);
        var order = inOrder(repository, channel);
        order.verify(repository).save(record);
        order.verify(channel).basicAck(7, false);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void databaseFailureTransfersToConfirmedRetryBeforeAck(int retries) throws Exception {
        var message = message(retries);
        doThrow(new IllegalStateException("mysql unavailable")).when(repository).save(any());
        consumer.consume(message, channel);
        var order = inOrder(publisher, channel);
        order.verify(publisher).retry(message, retries + 1);
        order.verify(channel).basicAck(7, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void fourthFailedInsertRejectsToDeadLetterQueueInsteadOfRequeue() throws Exception {
        doThrow(new IllegalStateException("mysql unavailable")).when(repository).save(any());
        consumer.consume(message(3), channel);
        verify(repository, times(1)).save(any());
        verify(channel).basicReject(7, false);
        verifyNoInteractions(publisher);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void unconfirmedRetryDoesNotAckOriginalMessage() throws Exception {
        doThrow(new IllegalStateException("mysql unavailable")).when(repository).save(any());
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).retry(any(), anyInt());
        consumer.consume(message(0), channel);
        verify(channel).basicNack(7, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void malformedPayloadAndUnsupportedVersionGoToDeadLetterWithoutWriting() throws Exception {
        for (String text : new String[]{"not json", "{\"schemaVersion\":2}", "null"}) {
            var malformed = MessageBuilder.withBody(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).build();
            malformed.getMessageProperties().setDeliveryTag(7);
            consumer.consume(malformed, channel);
        }
        verify(channel, times(3)).basicReject(7, false);
        verifyNoInteractions(repository, publisher);
    }

    @Test
    void invalidRetryCountCannotTriggerUnlimitedRetry() throws Exception {
        consumer.consume(message(4), channel);
        verify(channel).basicReject(7, false);
        verifyNoInteractions(repository, publisher);
    }

    @Test
    void ackFailureAfterCommitMustNotBeTreatedAsDatabaseFailure() throws Exception {
        doThrow(new IOException("channel closed")).when(channel).basicAck(7, false);
        assertThrows(IOException.class, () -> consumer.consume(message(0), channel));
        verify(repository).save(any());
        verifyNoInteractions(publisher);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }
}
