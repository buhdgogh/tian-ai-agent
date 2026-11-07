package com.tianaiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import com.tianaiagent.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class ConversationRevisionTest {
    @Test void streamedPartialSurvivesCancellationAndLateChunksCannotOverwriteRevision() {
        var turn = repository.begin("zhangsan", "stream-chat", "agent", "问题", "first", null);
        repository.updateRunningContent(turn.replyMessageId(), "已输出内容", Map.of("streamPhase", "answer"));
        repository.finish(turn.replyMessageId(), null, "CANCELLED", Map.of());
        assertEquals("已输出内容", repository.messages("zhangsan", "stream-chat", "agent").get(1).text());
        repository.begin("zhangsan", "stream-chat", "agent", "修改问题", "second", "first");
        repository.updateRunningContent(turn.replyMessageId(), "迟到增量", Map.of("streamPhase", "answer"));
        assertEquals("已输出内容", repository.messages("zhangsan", "stream-chat", "agent").get(1).text());
    }
    private AgentDatabase database;
    private ConversationRepository repository;
    @BeforeEach void setup() {
        database = new AgentDatabase("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.initialize();
        new UserRepository(database, "zhangsan", "123456").initialize();
        repository = new ConversationRepository(database, new ObjectMapper());
    }

    @Test void interruptAndRevisePreserveOldContentAndFenceLateResults() {
        var old = repository.begin("zhangsan", "chat", "agent", "旧问题", "old", null);
        repository.updateRunningMetadata(old.replyMessageId(), Map.of("taskId", "task-old"));
        var stopped = repository.interrupt("zhangsan", "chat", "agent", "old");
        assertEquals("CANCELLED", stopped.status());
        assertEquals("task-old", stopped.taskId());
        var revised = repository.begin("zhangsan", "chat", "agent", "修改后的问题", "new", "old");
        repository.finish(old.replyMessageId(), "迟到的旧答案", "COMPLETED", Map.of());
        repository.updateRunningMetadata(old.replyMessageId(), Map.of("taskId", "late-task"));
        var rows = repository.messages("zhangsan", "chat", "agent");
        assertEquals(4, rows.size());
        assertEquals("旧问题", rows.get(0).text());
        assertEquals("SUPERSEDED", rows.get(0).status());
        assertEquals("SUPERSEDED", rows.get(1).status());
        assertEquals("CANCELLED", rows.get(1).metadata().get("previousStatus"));
        assertEquals("new", rows.get(1).metadata().get("supersededBy"));
        assertEquals("task-old", rows.get(1).metadata().get("taskId"));
        assertFalse(rows.get(1).text().contains("迟到"));
        repository.updateRunningMetadata(revised.replyMessageId(), Map.of("taskId", "new-task"));
        repository.finish(revised.replyMessageId(), "新答案", "COMPLETED", Map.of());
        var answer = repository.messages("zhangsan", "chat", "agent").get(3);
        assertEquals("old", answer.metadata().get("replacesTurnId"));
        assertEquals("新答案", answer.text());
    }

    @Test void onlyLatestInterruptedTurnCanBeRevised() {
        var completed = repository.begin("zhangsan", "chat", "agent", "完成问题", "done", null);
        repository.finish(completed.replyMessageId(), "完成答案", "COMPLETED", Map.of());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> repository.begin("zhangsan", "chat", "agent", "不应改写", "bad", "done")).getStatusCode().value());
        var newer = repository.begin("zhangsan", "chat", "agent", "新问题", "next", null);
        repository.interrupt("zhangsan", "chat", "agent", "next");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> repository.begin("zhangsan", "chat", "agent", "旧轮次不可改", "bad2", "done")).getStatusCode().value());
        assertEquals(4, repository.messages("zhangsan", "chat", "agent").size());
        assertFalse(repository.isRunning(newer.replyMessageId()));
    }

    @Test void aFailedRevisionTransactionRestoresTheOriginalTurn() throws Exception {
        repository.begin("zhangsan", "chat", "agent", "原始问题", "old", null);
        repository.interrupt("zhangsan", "chat", "agent", "old");
        try (var connection = database.connection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE agent_chat_message ADD CONSTRAINT reject_question CHECK(content<>'blocked')");
        }
        assertThrows(IllegalStateException.class, () -> repository.begin("zhangsan", "chat", "agent", "blocked", "new", "old"));
        var rows = repository.messages("zhangsan", "chat", "agent");
        assertEquals(2, rows.size());
        assertEquals("COMPLETED", rows.get(0).status());
        assertEquals("CANCELLED", rows.get(1).status());
        assertFalse(rows.get(0).metadata().containsKey("supersededBy"));
        assertEquals(2, repository.find("zhangsan", "chat", "agent").messageCount());
    }

    @Test void concurrentRevisionsCannotCreateTwoActiveBranches() throws Exception {
        repository.begin("zhangsan", "chat", "agent", "原始问题", "old", null);
        repository.interrupt("zhangsan", "chat", "agent", "old");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                start.await();
                try { repository.begin("zhangsan", "chat", "agent", "修改" + index, "revision" + index, "old"); return true; }
                catch (ResponseStatusException conflict) { assertEquals(409, conflict.getStatusCode().value()); return false; }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var result : attempts) if (result.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(4, repository.messages("zhangsan", "chat", "agent").size());
    }

    @Test void anotherUserCannotInterruptOrReviseThisConversation() {
        new UserRepository(database, "lisi", "654321").initialize();
        repository.begin("zhangsan", "chat", "agent", "私人问题", "old", null);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> repository.interrupt("lisi", "chat", "agent", "old")).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> repository.begin("lisi", "chat", "agent", "冒充修改", "new", "old"));
        assertTrue(repository.list("lisi", "agent").isEmpty());
    }

    @Test void partialReplyIsKeptInTheSupersededAuditVersion() {
        var old = repository.begin("zhangsan", "partial", "agent", "原问题", "old", null);
        repository.finish(old.replyMessageId(), "已经生成的部分内容", "CANCELLED", Map.of());
        repository.begin("zhangsan", "partial", "agent", "新问题", "new", "old");
        var rows = repository.messages("zhangsan", "partial", "agent");
        assertEquals("SUPERSEDED", rows.get(1).status());
        assertEquals("已经生成的部分内容", rows.get(1).text());
    }

    @Test void taskSubmissionRacingAnInterruptIsCancelledAfterSubmission() {
        var turn = repository.begin("zhangsan", "race", "agent", "原问题", "old", null);
        var tasks = org.mockito.Mockito.mock(com.tianaiagent.task.AsyncTaskCenter.class);
        var pending = new com.tianaiagent.task.TaskRecord("racy-task", com.tianaiagent.task.TaskType.AGENT_WORKFLOW, "zhangsan", Map.of());
        var cancelled = pending.copy(); cancelled.setStatus(com.tianaiagent.task.TaskStatus.CANCELLED);
        org.mockito.Mockito.when(tasks.submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> { repository.interrupt("zhangsan", "race", "agent", "old"); return pending; });
        org.mockito.Mockito.when(tasks.cancel("racy-task")).thenReturn(cancelled);
        var mapper = new ObjectMapper();
        var workflows = new com.tianaiagent.workflow.AgentWorkflowService(tasks,
                org.mockito.Mockito.mock(com.tianaiagent.service.MedicalAgentService.class),
                new com.tianaiagent.service.MedicalSafetyService(),
                org.mockito.Mockito.mock(com.tianaiagent.service.MedicalIntentRouter.class),
                org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class), mapper,
                new org.springframework.core.task.support.TaskExecutorAdapter(Runnable::run));
        var users = new UserRepository(database, "zhangsan", "123456");
        org.springframework.test.util.ReflectionTestUtils.setField(workflows, "conversations", new ConversationService(repository, mapper, users));
        var task = workflows.submitForConversation(new com.tianaiagent.workflow.WorkflowModels.WorkflowRequest("原问题", "race", "zhangsan", true), turn);
        assertEquals(com.tianaiagent.task.TaskStatus.CANCELLED, task.getStatus());
        org.mockito.Mockito.verify(tasks).cancel("racy-task");
        assertFalse(repository.isRunning(turn.replyMessageId()));
    }
}
