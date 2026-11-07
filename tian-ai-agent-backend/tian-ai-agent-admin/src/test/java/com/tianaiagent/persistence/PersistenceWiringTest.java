package com.tianaiagent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.task.*;
import com.tianaiagent.profile.UserProfileRepository;
import com.tianaiagent.toolgateway.MysqlToolAuditRepository;
import com.tianaiagent.user.UserRepository;
import com.tianaiagent.conversation.ConversationRepository;
import com.tianaiagent.conversation.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(AgentDatabase.class, MysqlTaskRepository.class, FileTaskRepository.class,
                    UserProfileRepository.class, MysqlToolAuditRepository.class,
                    UserRepository.class, ConversationRepository.class, ConversationService.class);

    @Test
    void mysqlBeansAndSchemaAreReadyBeforeTaskStartup() {
        runner.withUserConfiguration(AsyncTaskCenter.class)
                .withBean("aiTaskExecutor", Executor.class, () -> Runnable::run)
                .withPropertyValues("agent.persistence.backend=mysql",
                        "agent.persistence.mysql.jdbc-url=jdbc:h2:mem:wiring;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "agent.persistence.mysql.username=sa", "agent.persistence.mysql.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TaskRepository.class)
                            .hasSingleBean(MysqlToolAuditRepository.class).hasSingleBean(UserProfileRepository.class)
                            .hasSingleBean(ConversationService.class);
                    assertThat(context.getBean(UserRepository.class).defaultUser().userId()).isEqualTo("zhangsan");
                    var task = context.getBean(AsyncTaskCenter.class).submit(TaskType.AGENT_WORKFLOW, "user1",
                            Map.of(), ignored -> Map.of("answer", "ok"));
                    assertThat(context.getBean(TaskRepository.class).find(task.getId())).isPresent();
                });
    }

    @Test
    void explicitFileModeDoesNotConnectToMysqlOrExposeVolatileProfiles() {
        runner.withPropertyValues("agent.persistence.backend=file").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FileTaskRepository.class)
                    .doesNotHaveBean(AgentDatabase.class).doesNotHaveBean(UserProfileRepository.class)
                    .doesNotHaveBean(MysqlToolAuditRepository.class);
        });
    }
}
