package com.tianaiagent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.task.*;
import com.tianaiagent.profile.UserProfileRepository;
import com.tianaiagent.toolgateway.*;
import com.tianaiagent.observability.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC round trips in H2 MySQL mode; no network or model calls. */
class MysqlPersistenceTest {
    private AgentDatabase database;
    private final ObjectMapper mapper = new ObjectMapper();
    private MysqlTaskRepository tasks;
    private MysqlToolAuditRepository audits;

    @BeforeEach
    void initialize() {
        database = new AgentDatabase("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.initialize();
        tasks = new MysqlTaskRepository(database, mapper);
        audits = new MysqlToolAuditRepository(database);
    }

    @Test
    void persistsProgressResultErrorsAndLoadsAfterRestart() {
        AsyncTaskCenter center = new AsyncTaskCenter(mapper, Runnable::run, tasks);
        var success = center.submit(TaskType.AGENT_WORKFLOW, "user1", Map.of("chatId", "chat1"), context -> {
            context.update(50, "检索", "找到证据");
            var saved = tasks.find(context.taskId()).orElseThrow();
            assertEquals(50, saved.getProgress());
            assertEquals("检索", saved.getStage());
            assertEquals(TaskStatus.RUNNING, saved.getStatus());
            assertTrue(saved.getEvents().stream().anyMatch(e -> e.contains("找到证据")));
            assertEquals(context.taskId(), ExecutionContext.current().taskId());
            return Map.of("answer", "结论", "sources", List.of("文献1"));
        });
        var failed = center.submit(TaskType.AGENT_WORKFLOW, "user2", Map.of(), context -> {
            throw new IllegalStateException("检索不可用");
        });
        AsyncTaskCenter restarted = new AsyncTaskCenter(mapper, Runnable::run, new MysqlTaskRepository(database, mapper));
        ReflectionTestUtils.invokeMethod(restarted, "loadTasks");
        assertEquals(TaskStatus.COMPLETED, restarted.get(success.getId()).getStatus());
        assertEquals("结论", ((Map<?, ?>) restarted.get(success.getId()).getResult()).get("answer"));
        assertEquals(TaskStatus.FAILED, restarted.get(failed.getId()).getStatus());
        assertEquals("检索不可用", restarted.get(failed.getId()).getError());
        assertEquals(1, restarted.list("user1", TaskType.AGENT_WORKFLOW).size());
        assertEquals(TaskStatus.COMPLETED, restarted.cancel(success.getId()).getStatus());
        assertNull(ExecutionContext.current().taskId());
    }

    @Test
    void recoversInterruptedTasksAndImportDoesNotOverwriteExistingData() {
        TaskRecord old = new TaskRecord("a".repeat(32), TaskType.DOCUMENT_INDEX, "user1", Map.of());
        old.setStatus(TaskStatus.RUNNING);
        old.setProgress(45);
        tasks.save(old);
        AsyncTaskCenter restarted = new AsyncTaskCenter(mapper, Runnable::run, tasks);
        ReflectionTestUtils.invokeMethod(restarted, "loadTasks");
        tasks.importIfAbsent(old);
        var recovered = tasks.find(old.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, recovered.getStatus());
        assertEquals(45, recovered.getProgress());
        assertTrue(recovered.getError().contains("重启"));
        assertNotNull(recovered.getFinishedAt());
    }

    @Test
    void auditSurvivesNewRepositoryAndSupportsFiltersAndPagination() {
        for (int i = 0; i < 12; i++) {
            audits.save(new ToolAuditRecord(String.format("%032d", i), "searchPubMed", i % 2 == 0 ? "SUCCESS" : "FAILED",
                    123, "digest", i % 2 == 0 ? null : "unavailable", String.format("2026-10-20T00:00:%02dZ", i),
                    "task1", "user1", "chat1", "step1", 2, "call" + i));
        }
        var restarted = new MysqlToolAuditRepository(database);
        var page = restarted.find("task1", "user1", "chat1", "FAILED", 2, 1);
        assertEquals(2, page.size());
        assertEquals("call9", page.getFirst().toolCallId());
        assertEquals(123, page.getFirst().durationMs());
        assertEquals(2, page.getFirst().iteration());
        assertTrue(restarted.find("other", null, null, null, 100, 0).isEmpty());
    }

    @Test
    void profileCrudIsUserScopedAndReplaceKeepsCreationTime() {
        var profiles = new UserProfileRepository(database, mapper);
        var first = profiles.replace("user1", new UserProfileRepository.ProfileUpdate("小天", "zh-CN",
                Map.of("answerStyle", "简洁"), List.of("用户确认喜欢表格形式的对比")));
        var replaced = profiles.replace("user1", new UserProfileRepository.ProfileUpdate("新昵称", "zh-CN", Map.of(), List.of()));
        assertEquals(first.createdAt(), replaced.createdAt());
        assertTrue(replaced.preferences().isEmpty());
        assertTrue(replaced.confirmedFacts().isEmpty());
        assertEquals("新昵称", new UserProfileRepository(database, mapper).find("user1").orElseThrow().displayName());
        assertTrue(profiles.find("user2").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> profiles.find("../user1"));
        profiles.delete("user1");
        assertTrue(profiles.find("user1").isEmpty());
        profiles.delete("user1");
    }

    @Test
    void parallelToolCallsKeepTaskStepAndCallIdsAndBusinessFailuresAreAudited() throws Exception {
        ThreadPoolTaskExecutor workers = pool(2);
        ThreadPoolTaskExecutor parallel = pool(2);
        try {
            ToolGateway gateway = new ToolGateway(List.of(), 1000, 2, 2, 1, workers);
            StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("audits", audits));
            ReflectionTestUtils.invokeMethod(gateway, "setRepository", beans.getBeanProvider(ToolAuditRepository.class));
            var published = new java.util.concurrent.CopyOnWriteArrayList<ToolAuditRecord>();
            ReflectionTestUtils.setField(gateway, "publisher", (ToolAuditPublisher) published::add);
            ToolCallback callback = new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("lookup").description("lookup").inputSchema("{}").build();
                }
                public String call(String arguments) {
                    assertEquals("task1", ExecutionContext.current().taskId());
                    return "{\"ok\":false,\"error\":\"证据服务不可用\"}";
                }
                public String call(String arguments, ToolContext context) { return call(arguments); }
            };
            var agent = new com.tianaiagent.agent.ToolCallAgent(gateway.guard(new ToolCallback[]{callback}), parallel, Set.of("lookup"));
            agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), List.of(
                    new AssistantMessage.ToolCall("call1", "function", "lookup", "{}"),
                    new AssistantMessage.ToolCall("call2", "function", "lookup", "{}")))))));
            try (var ignored = ExecutionContext.open(new ExecutionContext.Trace("task1", "user1", "chat1", "step2", 2, null))) {
                agent.act();
            }
            assertTrue(audits.find("task1", null, null, null, 100, 0).isEmpty());
            published.forEach(audits::save); // Simulate asynchronous consumers, not a gateway JDBC write.
            var saved = audits.find("task1", "user1", "chat1", "FAILED", 100, 0);
            assertEquals(2, saved.size()); // Not evicted by auditCapacity=1 in MySQL mode.
            assertEquals(Set.of("call1", "call2"), saved.stream().map(ToolAuditRecord::toolCallId).collect(java.util.stream.Collectors.toSet()));
            assertEquals("step2", saved.getFirst().stepId());
            assertEquals("证据服务不可用", saved.getFirst().error());
            assertNull(ExecutionContext.current().taskId());
        } finally { parallel.shutdown(); workers.shutdown(); }
    }

    @Test
    void duplicateAuditDeliveryIsIdempotentAndDoesNotOverwriteOriginal() {
        ToolAuditRecord first = new ToolAuditRecord("b".repeat(32), "searchPubMed", "FAILED", 100,
                "digest", "service unavailable", "2026-10-29T00:00:00Z", "task1", "user1", "chat1", "step1", 1, "call1");
        audits.save(first);
        audits.save(first);
        var rows = audits.find("task1", null, null, null, 100, 0);
        assertEquals(1, rows.size());
        assertEquals(first, rows.getFirst());
    }

    @Test
    void cancellationCannotBeOverwrittenByLateWorkerFailure() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        try {
            var center = new AsyncTaskCenter(mapper, executor, tasks);
            var task = center.submit(TaskType.AGENT_WORKFLOW, "user1", Map.of(), context -> {
                started.countDown();
                try { Thread.sleep(30_000); }
                catch (InterruptedException error) { throw new IllegalStateException("worker interrupted", error); }
                finally { done.countDown(); }
                return "ok";
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            center.cancel(task.getId());
            assertTrue(done.await(3, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(TaskStatus.CANCELLED, tasks.find(task.getId()).orElseThrow().getStatus());
        } finally { executor.shutdownNow(); }
    }

    private ThreadPoolTaskExecutor pool(int size) {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(size);
        pool.setMaxPoolSize(size);
        pool.setTaskDecorator(ExecutionContext::wrap);
        pool.initialize();
        return pool;
    }
}
