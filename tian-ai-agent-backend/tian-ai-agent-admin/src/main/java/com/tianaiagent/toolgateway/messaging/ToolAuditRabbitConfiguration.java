package com.tianaiagent.toolgateway.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableRabbit
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
@EnableConfigurationProperties(ToolAuditRabbitProperties.class)
public class ToolAuditRabbitConfiguration {
    @Bean
    public Declarables toolAuditTopology(ToolAuditRabbitProperties p) {
        var workExchange = new DirectExchange(p.exchange(), true, false);
        var retryExchange = new DirectExchange(p.retryExchange(), true, false);
        var deadExchange = new DirectExchange(p.deadLetterExchange(), true, false);
        // Quorum at-least-once DLX retains the source message until the target queue confirms receipt.
        var work = reliableQueue(p.queue(), p.deadLetterExchange(), p.deadLetterRoutingKey())
                .deliveryLimit(4).build();
        var dead = QueueBuilder.durable(p.deadLetterQueue()).quorum().build();
        List<Declarable> declarations = new ArrayList<>(List.of(workExchange, retryExchange, deadExchange, work, dead,
                BindingBuilder.bind(work).to(workExchange).with(p.routingKey()),
                BindingBuilder.bind(dead).to(deadExchange).with(p.deadLetterRoutingKey())));
        for (int retry = 1; retry <= 3; retry++) {
            var queue = reliableQueue(p.retryQueue(retry), p.exchange(), p.routingKey()).ttl(p.retryTtl(retry)).build();
            declarations.add(queue);
            declarations.add(BindingBuilder.bind(queue).to(retryExchange).with(p.retryRoutingKey(retry)));
        }
        return new Declarables(declarations);
    }

    private QueueBuilder reliableQueue(String name, String exchange, String key) {
        return QueueBuilder.durable(name).quorum().deadLetterExchange(exchange).deadLetterRoutingKey(key)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish);
    }

    @Bean("toolAuditRabbitTemplate")
    public RabbitTemplate toolAuditRabbitTemplate(ConnectionFactory factory) {
        if (!(factory instanceof CachingConnectionFactory caching)) {
            throw new IllegalStateException("Tool audit requires a confirm-capable CachingConnectionFactory");
        }
        caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        caching.setPublisherReturns(true);
        var template = new RabbitTemplate(factory);
        template.setMandatory(true);
        // A separate publisher connection avoids blocked consumers when the broker applies flow control.
        template.setUsePublisherConnection(true);
        return template;
    }

    @Bean("toolAuditListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory toolAuditListenerContainerFactory(ConnectionFactory factory) {
        var listener = new SimpleRabbitListenerContainerFactory();
        listener.setConnectionFactory(factory);
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setPrefetchCount(20);
        listener.setConcurrentConsumers(1);
        listener.setMaxConcurrentConsumers(4);
        listener.setDefaultRequeueRejected(true); // Channel/connection failures are redelivered; SQL failures handled explicitly.
        return listener;
    }
}
