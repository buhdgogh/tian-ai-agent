package com.tianaiagent.toolgateway.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

import static org.junit.jupiter.api.Assertions.*;

class ToolAuditTopologyTest {
    @Test
    void queuesExchangesRetriesAndDeadLettersAreDurable() {
        var p = ToolAuditConsumerTest.properties();
        var declarations = new ToolAuditRabbitConfiguration().toolAuditTopology(p);
        var queues = declarations.getDeclarablesByType(Queue.class);
        assertEquals(5, queues.size());
        for (var queue : queues) {
            assertTrue(queue.isDurable());
            assertFalse(queue.isAutoDelete());
            assertEquals("quorum", queue.getArguments().get("x-queue-type"));
            if (!queue.getName().equals(p.deadLetterQueue())) {
                assertEquals("at-least-once", queue.getArguments().get("x-dead-letter-strategy"));
                assertEquals("reject-publish", queue.getArguments().get("x-overflow"));
            }
        }
        var work = queues.stream().filter(q -> q.getName().equals(p.queue())).findFirst().orElseThrow();
        assertEquals(p.deadLetterExchange(), work.getArguments().get("x-dead-letter-exchange"));
        assertEquals(p.deadLetterRoutingKey(), work.getArguments().get("x-dead-letter-routing-key"));
        for (int retry = 1; retry <= 3; retry++) {
            String queueName = p.retryQueue(retry);
            var queue = queues.stream().filter(q -> q.getName().equals(queueName)).findFirst().orElseThrow();
            assertEquals(p.retryTtl(retry), queue.getArguments().get("x-message-ttl"));
            assertEquals(p.exchange(), queue.getArguments().get("x-dead-letter-exchange"));
        }
        assertTrue(declarations.getDeclarablesByType(DirectExchange.class).stream().allMatch(DirectExchange::isDurable));
        assertThrows(IllegalArgumentException.class, () -> new ToolAuditRabbitProperties(null,null,null,null,null,null,null,null,4,1000,100));
    }

    @Test
    void producerRequiresCorrelatedConfirmsReturnsAndMandatoryRouting() {
        var connection = new CachingConnectionFactory();
        try {
            var template = new ToolAuditRabbitConfiguration().toolAuditRabbitTemplate(connection);
            assertTrue(connection.isPublisherConfirms());
            assertTrue(connection.isPublisherReturns());
            assertTrue(template.isMandatoryFor(new org.springframework.amqp.core.Message(new byte[0])));
        } finally { connection.destroy(); }
    }
}
