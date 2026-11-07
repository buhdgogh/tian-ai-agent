package com.tianaiagent.toolgateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import com.tianaiagent.toolgateway.MysqlToolAuditRepository;
import com.tianaiagent.toolgateway.ToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAuditWiringTest {
    @Test
    void mysqlGatewayPublishesThroughRabbitAndRegistersManualConsumer() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("toolGatewayExecutor", AsyncTaskExecutor.class, () -> new TaskExecutorAdapter(Runnable::run))
                .withUserConfiguration(AgentDatabase.class, MysqlToolAuditRepository.class, ToolGateway.class,
                        ToolAuditRabbitConfiguration.class, RabbitToolAuditPublisher.class, ToolAuditConsumer.class)
                .withPropertyValues("agent.persistence.backend=mysql",
                        "agent.persistence.mysql.jdbc-url=jdbc:h2:mem:audit_wiring;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "agent.persistence.mysql.username=sa", "agent.persistence.mysql.password=",
                        "agent.tool-gateway.audit-rabbit.consumer-auto-startup=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RabbitToolAuditPublisher.class).hasSingleBean(ToolAuditConsumer.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(ToolGateway.class), "publisher"))
                            .isSameAs(context.getBean(RabbitToolAuditPublisher.class));
                    var registry = context.getBean(RabbitListenerEndpointRegistry.class);
                    assertThat(registry.getListenerContainers()).hasSize(1);
                    assertThat(registry.getListenerContainers()).allMatch(c -> !c.isRunning());
                });
    }
}
