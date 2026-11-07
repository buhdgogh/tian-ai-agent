package com.tianaiagent.toolgateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import com.tianaiagent.toolgateway.MysqlToolAuditRepository;
import com.tianaiagent.toolgateway.ToolAuditRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real RabbitMQ 4.x test. Declares/deletes only uniquely named test queues/exchanges. */
@EnabledIfEnvironmentVariable(named = "TOOL_AUDIT_RABBIT_INTEGRATION", matches = "true")
class ToolAuditRabbitIntegrationTest {
    @Test
    void persistentQueueRetryAndDeadLetterFlowUsesRealBroker() throws Exception {
        String prefix = "test.tool-audit." + UUID.randomUUID();
        var p = new ToolAuditRabbitProperties(prefix, prefix + ".write", "write", prefix + ".retry",
                prefix + ".retry", prefix + ".dead", prefix + ".dead", "dead", 3, 50, 5000);
        var connection = new CachingConnectionFactory(environment("RABBITMQ_HOST", "localhost"),
                Integer.parseInt(environment("RABBITMQ_PORT", "5672")));
        connection.setUsername(environment("RABBITMQ_USERNAME", "tian_agent"));
        connection.setPassword(environment("RABBITMQ_PASSWORD", ""));
        connection.setVirtualHost(environment("RABBITMQ_VHOST", "/"));
        var config = new ToolAuditRabbitConfiguration();
        var template = config.toolAuditRabbitTemplate(connection);
        var admin = new RabbitAdmin(connection);
        var declarations = config.toolAuditTopology(p);
        var mapper = new ObjectMapper();
        var database = new AgentDatabase("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.initialize();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger failAttempts = new AtomicInteger();
        var repository = new MysqlToolAuditRepository(database) {
            @Override public void save(ToolAuditRecord record) {
                if ("FAILED".equals(record.status())) {
                    failAttempts.incrementAndGet();
                    if (failures.getAndDecrement() > 0) throw new IllegalStateException("simulated insert failure");
                }
                super.save(record);
            }
        };
        var publisher = new RabbitToolAuditPublisher(template, mapper, p);
        var consumer = new ToolAuditConsumer(repository, publisher, mapper, p);
        var container = new SimpleMessageListenerContainer(connection);
        try {
            for (var exchange : declarations.getDeclarablesByType(org.springframework.amqp.core.Exchange.class)) admin.declareExchange(exchange);
            for (var queue : declarations.getDeclarablesByType(org.springframework.amqp.core.Queue.class)) admin.declareQueue(queue);
            for (var binding : declarations.getDeclarablesByType(org.springframework.amqp.core.Binding.class)) admin.declareBinding(binding);
            var success = record("SUCCESS", "a".repeat(32));
            publisher.publish(success); // Producer confirm before any consumer exists.
            assertEquals(1, admin.getQueueInfo(p.queue()).getMessageCount());
            assertTrue(repository.find("task1", null, null, null, 100, 0).isEmpty());

            container.setQueueNames(p.queue());
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setPrefetchCount(1);
            container.setMessageListener((ChannelAwareMessageListener) consumer::consume);
            container.start();
            await(() -> repository.find("task1", null, null, null, 100, 0).size() == 1);
            publisher.publish(success); // Redelivery is idempotent.

            failures.set(2);
            var failedTool = record("FAILED", "b".repeat(32));
            publisher.publish(failedTool);
            await(() -> repository.find("task1", null, null, "FAILED", 100, 0).size() == 1);
            assertEquals(3, failAttempts.get());
            failAttempts.set(0);
            failures.set(100);
            var deadTool = record("FAILED", "c".repeat(32));
            publisher.publish(deadTool);
            await(() -> admin.getQueueInfo(p.deadLetterQueue()).getMessageCount() == 1);
            assertEquals(4, failAttempts.get()); // Initial attempt + three durable retries.
            Message dead = template.receive(p.deadLetterQueue(), 1000);
            assertNotNull(dead);
            assertEquals(deadTool, mapper.readValue(dead.getBody(), ToolAuditEvent.class).audit());
            assertEquals(3, (Integer) dead.getMessageProperties().getHeader(RabbitToolAuditPublisher.RETRY_HEADER));
            assertNotNull(dead.getMessageProperties().getHeader("x-death"));
            assertEquals(2, repository.find("task1", null, null, null, 100, 0).size());
        } finally {
            container.stop();
            for (var queue : declarations.getDeclarablesByType(org.springframework.amqp.core.Queue.class)) admin.deleteQueue(queue.getName());
            for (var exchange : declarations.getDeclarablesByType(org.springframework.amqp.core.Exchange.class)) admin.deleteExchange(exchange.getName());
            connection.destroy();
        }
    }

    private static ToolAuditRecord record(String status, String id) {
        var sample = ToolAuditConsumerTest.audit(status);
        return new ToolAuditRecord(id, sample.toolName(), status, sample.durationMs(), sample.argumentDigest(), sample.error(),
                sample.createdAt(), sample.taskId(), sample.ownerId(), sample.chatId(), sample.stepId(), sample.iteration(), sample.toolCallId());
    }
    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for RabbitMQ audit flow");
            Thread.sleep(50);
        }
    }
}
