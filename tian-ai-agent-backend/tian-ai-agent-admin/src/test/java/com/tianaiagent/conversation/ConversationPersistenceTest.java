package com.tianaiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import com.tianaiagent.user.*;
import com.tianaiagent.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConversationPersistenceTest {
    @Test void runningTaskIdIsPersistedForReconnectWithoutOverwritingTerminalReplies() {
        var turn = repository.begin("zhangsan", "reconnect", "agent", "检索后整理报告");
        var task = new TaskRecord("workflow-live", TaskType.AGENT_WORKFLOW, "zhangsan", Map.of("conversationReplyId", turn.replyMessageId()));
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(30); task.setStage("证据检索");
        service.taskPersisted(task);
        var pending = repository.messages("zhangsan", "reconnect", "agent").get(1);
        assertEquals("RUNNING", pending.status());
        assertEquals("workflow-live", pending.metadata().get("taskId"));
        repository.finish(turn.replyMessageId(), "最终报告", "COMPLETED", Map.of());
        service.taskPersisted(task);
        var completed = repository.messages("zhangsan", "reconnect", "agent").get(1);
        assertEquals("COMPLETED", completed.status());
        assertEquals("最终报告", completed.text());
        assertFalse(completed.metadata().containsKey("workflowProgress"));
    }
    private AgentDatabase database;
    private UserRepository users;
    private ConversationRepository repository;
    private ConversationService service;
    private final ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() {
        database = new AgentDatabase("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.initialize();
        users = new UserRepository(database, "zhangsan", "123456");
        users.initialize();
        repository = new ConversationRepository(database, mapper);
        service = new ConversationService(repository, mapper, users);
    }

    @Test void seedsDefaultUserWithSaltedHashAndDoesNotResetPasswordOrProfileOnRestart() throws Exception {
        assertEquals("zhangsan", users.defaultUser().userId());
        assertTrue(users.authenticate("zhangsan", "123456").isPresent());
        assertTrue(users.authenticate("zhangsan", "wrong").isEmpty());
        try (var connection = database.connection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT password_hash FROM agent_user WHERE id='zhangsan'")) {
            assertTrue(rows.next());
            assertNotEquals("123456", rows.getString(1));
            assertTrue(rows.getString(1).startsWith("pbkdf2_sha256$"));
        }
        new UserRepository(database, "zhangsan", "changed-config-does-not-reset").initialize();
        assertTrue(users.authenticate("zhangsan", "123456").isPresent());
        var profile = new com.tianaiagent.profile.UserProfileRepository(database, mapper).find("zhangsan").orElseThrow();
        assertEquals("zhangsan", profile.displayName());
    }

    @Test void separatesUsersSessionsAndModesAndKeepsFullHistoryAfterRestart() {
        users.createIfAbsent("lisi", "123456");
        var first = service.begin("zhangsan", "chat1", "medical", "问题一");
        var second = service.begin("zhangsan", "chat2", "medical", "问题二");
        var otherUser = service.begin("lisi", "chat1", "medical", "李四的问题");
        var rag = service.begin("zhangsan", "chat1", "rag", "知识库问题");
        service.complete(first, "回答一"); service.complete(second, "回答二"); service.complete(otherUser, "李四的回答"); service.complete(rag, "知识库回答");
        assertNotEquals(first.session().sessionId(), otherUser.session().sessionId());
        assertNotEquals(first.session().sessionId(), rag.session().sessionId());
        var restarted = new ConversationRepository(database, mapper);
        assertEquals(2, restarted.list("zhangsan", "medical").size());
        assertEquals(1, restarted.list("lisi", "medical").size());
        assertEquals("回答一", restarted.messages("zhangsan", "chat1", "medical").get(1).text());
        assertEquals(2, restarted.find("zhangsan", "chat1", "medical").messageCount());
    }

    @Test void storesPendingReplyThenCompletedFailedAndCancelledStream() {
        var complete = service.begin("zhangsan", "chat1", "medical", "问题");
        assertEquals("RUNNING", repository.messages("zhangsan", "chat1", "medical").get(1).status());
        service.recordStream(complete, Flux.just("第一", "第二")).blockLast();
        assertEquals("第一第二", repository.messages("zhangsan", "chat1", "medical").get(1).text());
        var failed = service.begin("zhangsan", "chat1", "medical", "继续");
        assertThrows(IllegalStateException.class, () -> service.recordStream(failed,
                Flux.concat(Flux.just("部分内容"), Flux.error(new IllegalStateException("模型不可用")))).blockLast());
        var reply = repository.messages("zhangsan", "chat1", "medical").get(3);
        assertEquals("FAILED", reply.status()); assertEquals("部分内容", reply.text()); assertEquals("模型不可用", reply.metadata().get("error"));
        var cancelled = service.begin("zhangsan", "chat1", "medical", "取消测试");
        service.recordStream(cancelled, Flux.just("片段").concatWith(Flux.never())).take(1).blockLast();
        assertEquals("CANCELLED", repository.messages("zhangsan", "chat1", "medical").get(5).status());
        service.complete(cancelled, "迟到的成功不能覆盖取消");
        assertEquals("CANCELLED", repository.messages("zhangsan", "chat1", "medical").get(5).status());
    }

    @Test void deletingSessionCascadesMessagesAndLateCallbacksCannotRecreateIt() throws Exception {
        var turn = service.begin("zhangsan", "chat1", "medical", "问题");
        repository.delete("zhangsan", "chat1", "medical");
        service.complete(turn, "迟到的回复");
        assertTrue(repository.list("zhangsan", "medical").isEmpty());
        try (var connection = database.connection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM agent_chat_message")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
        var replacement = service.begin("zhangsan", "chat1", "medical", "重新开始");
        assertNotEquals(turn.session().sessionId(), replacement.session().sessionId());
    }

    @Test void recordsWorkflowCompletionAndQueuedCancellationThroughTaskLifecycle() {
        var center = new AsyncTaskCenter(mapper, Runnable::run, new MysqlTaskRepository(database, mapper));
        ReflectionTestUtils.setField(center, "conversations", service);
        var turn = service.begin("zhangsan", "workflow", "agent", "整理报告");
        var task = center.submit(TaskType.AGENT_WORKFLOW, "zhangsan", Map.of("chatId", "workflow", "conversationReplyId", turn.replyMessageId()),
                ignored -> Map.of("executionResult", "报告正文", "review", Map.of("passed", true)));
        var reply = repository.messages("zhangsan", "workflow", "agent").get(1);
        assertEquals("COMPLETED", reply.status()); assertEquals("报告正文", reply.text()); assertEquals(task.getId(), reply.metadata().get("taskId"));
        var queued = new AsyncTaskCenter(mapper, ignored -> {}, new MysqlTaskRepository(database, mapper));
        ReflectionTestUtils.setField(queued, "conversations", service);
        var cancelled = service.begin("zhangsan", "cancel", "agent", "取消工作流");
        var pending = queued.submit(TaskType.AGENT_WORKFLOW, "zhangsan", Map.of("conversationReplyId", cancelled.replyMessageId()), ignored -> "never run");
        queued.cancel(pending.getId());
        assertEquals("CANCELLED", repository.messages("zhangsan", "cancel", "agent").get(1).status());
    }

    @Test void restartMarksInterruptedRepliesFailedWithoutLosingUserQuestions() {
        service.begin("zhangsan", "chat1", "medical", "未完成的问题");
        service.recover();
        var messages = repository.messages("zhangsan", "chat1", "medical");
        assertEquals("未完成的问题", messages.getFirst().text());
        assertEquals("FAILED", messages.get(1).status());
    }
}
