package com.tianaiagent.toolgateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitToolAuditPublisherTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RabbitToolAuditPublisher publisher = new RabbitToolAuditPublisher(template, mapper, ToolAuditConsumerTest.properties());

    @Test
    void sendsPersistentJsonWithCapturedContextAndUniqueConfirmIds() throws Exception {
        List<CorrelationData> confirms = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            messages.add(message);
            confirms.add(correlation);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var audit = ToolAuditConsumerTest.audit("SUCCESS");
        publisher.publish(audit);
        publisher.publish(audit);
        assertNotEquals(confirms.get(0).getId(), confirms.get(1).getId());
        var message = messages.getFirst();
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
        assertEquals(audit.id(), message.getMessageProperties().getMessageId());
        assertEquals(audit, mapper.readValue(message.getBody(), ToolAuditEvent.class).audit());
        assertEquals(0, (Integer) message.getMessageProperties().getHeader(RabbitToolAuditPublisher.RETRY_HEADER));
    }

    @Test
    void brokerNackIsReportedInsteadOfSilentlyDroppingAudit() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(RabbitToolAuditPublisher.AuditPublishException.class, () -> publisher.publish(ToolAuditConsumerTest.audit("FAILED")));
    }

    @Test
    void mandatoryReturnFailsEvenIfExchangeAcknowledgesPublication() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", "audit", "wrong"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(RabbitToolAuditPublisher.AuditPublishException.class, () -> publisher.publish(ToolAuditConsumerTest.audit("SUCCESS")));
    }

    @Test
    void confirmTimeoutIsReported() {
        assertThrows(RabbitToolAuditPublisher.AuditPublishException.class, () -> publisher.publish(ToolAuditConsumerTest.audit("SUCCESS")));
    }

    @Test
    void persistentRetryKeepsPayloadIdentityAndAdvancesCounter() {
        var original = MessageBuilder.withBody("original audit payload".getBytes()).setMessageId("audit1")
                .setHeader(RabbitToolAuditPublisher.RETRY_HEADER, 0).build();
        doAnswer(invocation -> {
            Message retry = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            assertEquals("tian.tool-audit.retry.v1", invocation.getArgument(0));
            assertEquals("retry.1", invocation.getArgument(1));
            assertArrayEquals(original.getBody(), retry.getBody());
            assertEquals(original.getMessageProperties().getMessageId(), retry.getMessageProperties().getMessageId());
            assertEquals(MessageDeliveryMode.PERSISTENT, retry.getMessageProperties().getDeliveryMode());
            assertEquals(1, (Integer) retry.getMessageProperties().getHeader(RabbitToolAuditPublisher.RETRY_HEADER));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.retry(original, 1);
        assertEquals(0, (Integer) original.getMessageProperties().getHeader(RabbitToolAuditPublisher.RETRY_HEADER));
        assertThrows(IllegalArgumentException.class, () -> publisher.retry(original, 4));
    }
}
