package com.tianaiagent;

import com.tianaiagent.domain.conversation.adapter.port.ConversationMemoryRegistry;
import com.tianaiagent.domain.conversation.service.ConversationScope;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.task.model.valobj.TaskStatus;
import com.tianaiagent.domain.task.model.valobj.TaskType;
import com.tianaiagent.domain.task.service.AsyncTaskCenter;
import com.tianaiagent.types.storage.FileConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.persistence.mysql.enabled=false",
        "spring.ai.dashscope.api-key=",
        "doc-mcp.enabled=false",
        "agent.models.cooldown=0s",
        "agent.memory.backend=file",
        "agent.memory.long-term.enabled=false",
        "agent.access.users[0].id=trace_owner",
        "agent.access.users[0].token=trace-owner-test-token-123456",
        "agent.access.users[1].id=trace_other",
        "agent.access.users[1].token=trace-other-test-token-123456"
})
@AutoConfigureMockMvc
class ModelRoutingIntegrationTest {
    private static final String OWNER = "Bearer trace-owner-test-token-123456";
    private static final String OTHER = "Bearer trace-other-test-token-123456";
    private static final String INDEPENDENT_TASKS = "请分别完成两个独立任务。第一，说明如何把一份 PDF 文档解析并入库。"
            + "第二，为虚构的北辰项目生成一份进度报告模板。这两个任务独立交付，只需在聊天中提供文本。";
    private static HttpServer server;
    private static final AtomicReference<String> SECONDARY_REQUEST = new AtomicReference<>();
    private static final AtomicReference<String> SECONDARY_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> ANTHROPIC_KEY = new AtomicReference<>();
    private static final AtomicReference<String> ANTHROPIC_VERSION = new AtomicReference<>();
    private static final AtomicReference<String> ANTHROPIC_REQUEST = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> STREAM_GATE = new AtomicReference<>();
    private static String base;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ConversationMemoryRegistry memoryRegistry;
    @Autowired AgentRunAudit audit;
    @Autowired AsyncTaskCenter tasks;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/primary/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 429, "{\"error\":{\"message\":\"RATE_LIMIT_BODY_SHOULD_NOT_BE_LOGGED\"}}");
        });
        server.createContext("/secondary/v1/chat/completions", exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            SECONDARY_REQUEST.set(payload);
            SECONDARY_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var parsed = new ObjectMapper().readTree(payload);
            if (payload.contains("虚构的北辰项目")) {
                respondIndependentTasks(exchange, parsed);
            } else if (payload.contains("工作区集成验证")) {
                respondWorkspaceModel(exchange, parsed);
            } else if (parsed.path("stream").asBoolean() && payload.contains("Java怎么学") && STREAM_GATE.get() != null) {
                var gate = STREAM_GATE.get();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write("""
                            data: {"id":"chat-stream","object":"chat.completion.chunk","created":1,"model":"secondary-actual","choices":[{"index":0,"delta":{"role":"assistant","content":"先学语法"},"finish_reason":null}]}

                            data: {"id":"chat-stream","object":"chat.completion.chunk","created":1,"model":"secondary-actual","choices":[{"index":0,"delta":{"content":"和集合"},"finish_reason":null}]}

                            """.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    output.write("""
                            data: {"id":"chat-stream","object":"chat.completion.chunk","created":1,"model":"secondary-actual","choices":[{"index":0,"delta":{"content":"再做项目"},"finish_reason":"stop"}]}

                            data: [DONE]

                            """.getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (parsed.path("stream").asBoolean()) {
                respond(exchange, 200, "text/event-stream", """
                        data: {"id":"chat-stream","object":"chat.completion.chunk","created":1,"model":"secondary-actual","choices":[{"index":0,"delta":{"role":"assistant","content":"流式回复"},"finish_reason":null}]}

                        data: {"id":"chat-stream","object":"chat.completion.chunk","created":1,"model":"secondary-actual","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: {"id":"chat-stream","object":"chat.completion.chunk","created":1,"model":"secondary-actual","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}}

                        data: [DONE]

                        """);
            } else if (payload.contains("Java怎么学")) {
                String system = parsed.path("messages").get(0).path("content").asText();
                String answer = system.contains("你是请求分类器")
                        ? "{\"intentTypes\":[\"OTHER\"],\"confidence\":0.95,\"requiresWorkflow\":false}"
                        : "先学 Java 语法、集合和异常，再做一个小项目。";
                var message = new ObjectMapper().createObjectNode().put("role", "assistant").put("content", answer);
                var reply = new ObjectMapper().createObjectNode().put("id", "java-answer").put("object", "chat.completion")
                        .put("created", 1).put("model", "secondary-actual");
                reply.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop").set("message", message);
                respond(exchange, 200, reply.toString());
            } else if (parsed.path("tools").findValuesAsText("name").equals(java.util.List.of("toolSearchTool"))) {
                respond(exchange, 200, """
                        {"id":"chat-search","object":"chat.completion","created":1,"model":"secondary-actual",
                         "choices":[{"index":0,"message":{"role":"assistant","tool_calls":[
                           {"id":"search-1","type":"function","function":{"name":"toolSearchTool","arguments":"{\\"query\\":\\"doTerminate\\"}"}}
                         ]},"finish_reason":"tool_calls"}]}
                        """);
            } else if (parsed.path("tools").findValuesAsText("name").contains("doTerminate")
                    && !parsed.path("messages").findValuesAsText("tool_call_id").contains("tool-1")) {
                respond(exchange, 200, """
                        {"id":"chat-tool","object":"chat.completion","created":1,"model":"secondary-actual",
                         "choices":[{"index":0,"message":{"role":"assistant","tool_calls":[
                           {"id":"tool-1","type":"function","function":{"name":"doTerminate","arguments":"{}"}}
                         ]},"finish_reason":"tool_calls"}],
                         "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                        """);
            } else {
                respond(exchange, 200, """
                    {"id":"chat-test","object":"chat.completion","created":1,"model":"secondary-actual",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"故障切换成功"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":17,"completion_tokens":5,"total_tokens":22}}
                    """);
            }
        });
        server.createContext("/anthropic/v1/messages", exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ANTHROPIC_REQUEST.set(payload);
            ANTHROPIC_KEY.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            ANTHROPIC_VERSION.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            if (payload.contains("LIMIT_ANTHROPIC")) {
                respond(exchange, 429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"limit\"}}");
            } else if (new ObjectMapper().readTree(payload).path("stream").asBoolean()) {
                respond(exchange, 200, "text/event-stream", """
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg-test","type":"message","role":"assistant","model":"claude-actual","content":[],"stop_reason":null,"usage":{"input_tokens":10,"output_tokens":1}}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Claude 流式回复"}}

                        event: content_block_stop
                        data: {"type":"content_block_stop","index":0}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """);
            } else {
                respond(exchange, 200, """
                        {"id":"msg-test","type":"message","role":"assistant","model":"claude-actual",
                         "content":[{"type":"text","text":"Claude 原生回复"}],
                         "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":4}}
                        """);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        registry.add("agent.models.allowed-user-origins[0]", () -> base);
        registry.add("agent.models.providers[0].id", () -> "primary");
        registry.add("agent.models.providers[0].api-key", () -> "test-primary-key");
        registry.add("agent.models.providers[0].model", () -> "primary-model");
        registry.add("agent.models.providers[0].base-url", () -> base + "/primary/v1");
        registry.add("agent.models.providers[1].id", () -> "secondary");
        registry.add("agent.models.providers[1].api-key", () -> "test-secondary-key");
        registry.add("agent.models.providers[1].model", () -> "secondary-model");
        registry.add("agent.models.providers[1].reasoning-effort", () -> "none");
        registry.add("agent.models.providers[1].base-url", () -> base + "/secondary/v1");
    }

    @AfterAll
    static void stop() { if (server != null) server.stop(0); }

    @Test
    void switchesProvidersAndPersistsAnOwnerScopedAudit() throws Exception {
        mvc.perform(post("/ai/medical/chat").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"audit-test\",\"message\":\"PRIVATE_PROMPT 描述项目文档的用途\"}"))
                .andExpect(status().isOk()).andExpect(content().string("故障切换成功"));
        assertThat(mapper.readTree(SECONDARY_REQUEST.get()).path("model").asText()).isEqualTo("secondary-model");
        assertThat(mapper.readTree(SECONDARY_REQUEST.get()).path("reasoning_effort").asText()).isEqualTo("none");

        var runs = mvc.perform(get("/agent-runs").header("Authorization", OWNER))
                .andExpect(status().isOk()).andReturn().getResponse();
        String id = mapper.readTree(runs.getContentAsString()).get(0).path("id").asText();
        var detail = mvc.perform(get("/agent-runs/{id}", id).header("Authorization", OWNER))
                .andExpect(jsonPath("$.summary.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.totalTokens").value(22))
                .andExpect(jsonPath("$.events[0].provider").value("primary"))
                .andExpect(jsonPath("$.events[0].reason").value("HTTP_429"))
                .andExpect(jsonPath("$.events[1].provider").value("secondary"))
                .andExpect(jsonPath("$.events[1].model").value("secondary-actual"))
                .andExpect(jsonPath("$.events[1].inputTokens").value(17))
                .andReturn().getResponse();
        assertThat(detail.getContentAsString()).doesNotContain("PRIVATE_PROMPT", "RATE_LIMIT_BODY", "test-secondary-key");
        mvc.perform(get("/agent-runs/{id}", id).header("Authorization", OTHER)).andExpect(status().isNotFound());

        var stream = mvc.perform(post("/ai/medical/chat/stream").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"stream-test\",\"message\":\"介绍文档\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        var streamResponse = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(streamResponse.getContentAsString(StandardCharsets.UTF_8)).contains("流式回复");
        mvc.perform(get("/agent-runs/{id}", latestRun()).header("Authorization", OWNER))
                .andExpect(jsonPath("$.summary.kind").value("CHAT_STREAM"))
                .andExpect(jsonPath("$.summary.totalTokens").value(13));

        mvc.perform(post("/ai/medical/tool-chat").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"tool-test\",\"message\":\"结束说明\"}"))
                .andExpect(status().isOk()).andExpect(content().string("故障切换成功"));
        mvc.perform(get("/agent-runs/{id}", latestRun()).header("Authorization", OWNER))
                .andExpect(jsonPath("$.summary.kind").value("TOOL_CHAT"))
                .andExpect(jsonPath("$.events[?(@.kind == 'TOOL')].name").value(org.hamcrest.Matchers.hasItem("doTerminate")));
    }

    @Test
    void generalChatUsesItsOwnHistoryAndLeavesMedicalTriageOnLegacyRoutes() throws Exception {
        String chatId = "workspace-" + java.util.UUID.randomUUID();
        String payload = mapper.writeValueAsString(java.util.Map.of(
                "chatId", chatId, "message", "在手册中检索“呼吸困难”这个字段"));
        mvc.perform(post("/ai/chat").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk()).andExpect(content().string("故障切换成功"));
        String prompt = mapper.readTree(SECONDARY_REQUEST.get()).path("messages").get(0).path("content").asText();
        assertThat(prompt).contains("通用知识助手").doesNotContain("你是“天医”");
        mvc.perform(get("/ai/sessions/{id}/messages", chatId).param("workspace", "true").header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").exists());
        mvc.perform(get("/ai/sessions/{id}/messages", chatId).header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        var medical = mvc.perform(post("/ai/medical/chat").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(medical.getContentAsString(StandardCharsets.UTF_8)).contains("拨打 120");
        mvc.perform(delete("/ai/sessions/{id}", chatId).param("workspace", "true").header("Authorization", OWNER))
                .andExpect(status().isOk());
        mvc.perform(get("/ai/sessions/{id}/messages", chatId).param("workspace", "true").header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void personalSettingsUseOnlyTheAuthenticatedOwnersCredentials() throws Exception {
        mvc.perform(get("/model-providers")).andExpect(status().isUnauthorized());
        String version = settingsVersion(OWNER);
        var row = mapper.createObjectNode().put("id", "personal").put("protocol", "OPENAI_COMPATIBLE")
                .put("model", "personal-model").put("baseUrl", "http://127.0.0.1:1/v1")
                .put("apiKey", "personal-test-key");
        var update = mapper.createObjectNode().put("version", version);
        update.putArray("providers").add(row);
        mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content(update.toString()))
                .andExpect(status().isBadRequest());
        row.put("baseUrl", base + "/secondary/v1");
        var saved = mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content(update.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers[0].hasApiKey").value(true))
                .andExpect(jsonPath("$.providers[0].apiKey").doesNotExist()).andReturn().getResponse();
        try {
            assertThat(saved.getContentAsString()).doesNotContain("personal-test-key");
            mvc.perform(get("/model-providers").header("Authorization", OTHER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.providers").isEmpty());
            mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                            .content(update.toString())).andExpect(status().isConflict());

            String payload = mapper.createObjectNode().put("chatId", "provider-" + java.util.UUID.randomUUID())
                    .put("message", "介绍项目文档").toString();
            mvc.perform(post("/ai/chat").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isOk());
            assertThat(mapper.readTree(SECONDARY_REQUEST.get()).path("model").asText()).isEqualTo("personal-model");
            assertThat(SECONDARY_AUTH.get()).isEqualTo("Bearer personal-test-key");

            var stream = mvc.perform(post("/ai/chat/stream").header("Authorization", OWNER)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
            assertThat(SECONDARY_AUTH.get()).isEqualTo("Bearer personal-test-key");

            mvc.perform(post("/ai/chat").header("Authorization", OTHER).contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isOk());
            assertThat(SECONDARY_AUTH.get()).isEqualTo("Bearer test-secondary-key");

            update.put("version", settingsVersion(OWNER));
            row.remove("apiKey");
            mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                    .content(update.toString())).andExpect(status().isOk());
            update.put("version", settingsVersion(OWNER));
            row.put("baseUrl", base + "/primary/v1");
            mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                    .content(update.toString())).andExpect(status().isBadRequest());

            var stored = new com.tianaiagent.infrastructure.adapter.repository.agent.FileModelProviderStore(mapper).load();
            assertThat(stored.get("trace_owner").getFirst().apiKey()).isEqualTo("personal-test-key");
            var encrypted = java.nio.file.Path.of(com.tianaiagent.types.storage.FileConstant.FILE_SAVE_DIR,
                    "model-settings", "providers.enc");
            assertThat(new String(java.nio.file.Files.readAllBytes(encrypted), StandardCharsets.ISO_8859_1))
                    .doesNotContain("personal-test-key");
        } finally {
            var clear = mapper.createObjectNode().put("version", settingsVersion(OWNER));
            clear.putArray("providers");
            mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                    .content(clear.toString())).andExpect(status().isOk());
        }
    }

    private String settingsVersion(String authorization) throws Exception {
        var response = mvc.perform(get("/model-providers").header("Authorization", authorization))
                .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).path("version").asText();
    }

    @Test
    void personalAnthropicProviderUsesMessagesApiAndFailsOverOnStreamingRateLimit() throws Exception {
        var settings = mapper.createObjectNode().put("version", settingsVersion(OWNER));
        var providers = settings.putArray("providers");
        providers.addObject().put("id", "claude-1").put("protocol", "ANTHROPIC").put("model", "claude-configured")
                .put("baseUrl", base + "/anthropic/v1/").put("apiKey", "anthropic-test-key").put("priority", 0);
        providers.addObject().put("id", "openai-1").put("protocol", "OPENAI_COMPATIBLE").put("model", "backup-model")
                .put("baseUrl", base + "/secondary/v1").put("apiKey", "openai-test-key").put("priority", 1);
        mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content(settings.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers[0].protocol").value("ANTHROPIC"));
        try {
            String chatId = "claude-" + java.util.UUID.randomUUID();
            var payload = mapper.createObjectNode().put("chatId", chatId).put("message", "介绍原生模型");
            mvc.perform(post("/ai/chat").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                            .content(payload.toString()))
                    .andExpect(status().isOk()).andExpect(content().string("Claude 原生回复"));
            assertThat(ANTHROPIC_KEY.get()).isEqualTo("anthropic-test-key");
            assertThat(ANTHROPIC_VERSION.get()).isEqualTo("2023-06-01");
            var request = mapper.readTree(ANTHROPIC_REQUEST.get());
            assertThat(request.path("model").asText()).isEqualTo("claude-configured");
            assertThat(request.path("max_tokens").asInt()).isPositive();
            assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("user");
            assertThat(chatStream(payload.toString())).contains("Claude 流式回复");
            payload.put("message", "LIMIT_ANTHROPIC");
            assertThat(chatStream(payload.toString())).contains("流式回复").doesNotContain("Claude 流式回复");
            mvc.perform(get("/agent-runs/{id}", latestRun()).header("Authorization", OWNER))
                    .andExpect(jsonPath("$.events[0].provider").value("claude-1"))
                    .andExpect(jsonPath("$.events[0].reason").value("HTTP_429"))
                    .andExpect(jsonPath("$.events[1].provider").value("openai-1"));
        } finally {
            settings.put("version", settingsVersion(OWNER));
            providers.removeAll();
            mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                    .content(settings.toString())).andExpect(status().isOk());
        }
    }

    private String chatStream(String payload) throws Exception {
        var stream = mvc.perform(post("/ai/chat/stream").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void dispatchPrefersTheSelectedPersonalProviderAndFallsBackInOrder() throws Exception {
        var settings = mapper.createObjectNode().put("version", settingsVersion(OWNER));
        var providers = settings.putArray("providers");
        int priority = 0;
        for (String id : java.util.List.of("usual-first", "selected", "rate-limited")) {
            providers.addObject().put("id", id).put("protocol", "OPENAI_COMPATIBLE").put("model", id)
                    .put("apiKey", "test-" + id).put("priority", priority++)
                    .put("baseUrl", base + ("rate-limited".equals(id) ? "/primary/v1" : "/secondary/v1"));
        }
        mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                .content(settings.toString())).andExpect(status().isOk());
        try {
            assertThat(dispatchModelAttempts("selected")).containsOnly("selected");
            assertThat(dispatchModelAttempts("rate-limited")).startsWith("rate-limited", "usual-first");
            assertThat(dispatchModelAttempts(null)).containsOnly("usual-first");
            assertThat(dispatchModelAttempts("removed")).containsOnly("usual-first");
        } finally {
            settings.put("version", settingsVersion(OWNER));
            providers.removeAll();
            mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                    .content(settings.toString())).andExpect(status().isOk());
        }
    }

    private java.util.List<String> dispatchModelAttempts(String providerId) throws Exception {
        var payload = mapper.createObjectNode().put("chatId", "model-" + java.util.UUID.randomUUID())
                .put("message", "Java怎么学");
        if (providerId != null) payload.put("modelProviderId", providerId);
        var stream = mvc.perform(post("/ai/dispatch/stream").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("流式回复", "event:done");
        return audit.find("trace_owner", latestRun()).orElseThrow().events().stream()
                .filter(event -> "MODEL".equals(event.kind())).map(event -> event.provider()).toList();
    }

    @Test
    void workflowTaskInheritsTheDispatchModelPreferenceAcrossThreads() {
        var dispatch = audit.start("trace_owner", "DISPATCH", null, "selected");
        try {
            var task = AgentRunContext.within(dispatch, () -> tasks.submit(TaskType.AGENT_WORKFLOW,
                    dispatch.ownerId(), java.util.Map.of(), context -> AgentRunContext.current().modelProviderId()));
            org.awaitility.Awaitility.await().untilAsserted(() -> {
                var completed = tasks.get(task.getId());
                assertThat(completed.getStatus()).isEqualTo(TaskStatus.COMPLETED);
                assertThat(completed.getResult()).isEqualTo("selected");
            });
        } finally {
            dispatch.finish("COMPLETED");
        }
    }

    @Test
    void workflowCompletesTwoIndependentTextStepsWithToolDiscoveryAvailable() throws Exception {
        String payload = mapper.createObjectNode().put("chatId", "parallel-" + java.util.UUID.randomUUID())
                .put("message", INDEPENDENT_TASKS).toString();
        var response = mvc.perform(post("/ai/dispatch").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("WORKFLOW")).andReturn().getResponse();
        String taskId = mapper.readTree(response.getContentAsString()).path("task").path("id").asText();
        org.awaitility.Awaitility.await().untilAsserted(() -> {
            var task = tasks.get(taskId);
            assertThat(task.getStatus()).withFailMessage("工作流状态 %s，错误 %s", task.getStatus(), task.getError())
                    .isEqualTo(TaskStatus.COMPLETED);
            var result = mapper.valueToTree(task.getResult());
            assertThat(result.path("executions")).hasSize(2);
            assertThat(result.path("executionResult").asText()).contains("PDF 入库步骤", "北辰项目进度模板");
            assertThat(task.getEvents()).anyMatch(event -> event.contains("并行执行 2 个无依赖步骤"));
        });
    }

    @Test
    void dispatchKeepsTheGeneralQuestionAsTheLastUserMessage() throws Exception {
        String chatId = "java-" + java.util.UUID.randomUUID();
        String payload = mapper.createObjectNode().put("chatId", chatId).put("message", "Java怎么学").toString();
        mvc.perform(post("/ai/dispatch").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("REACT"))
                .andExpect(jsonPath("$.route.intentTypes[0]").value("OTHER"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("先学 Java 语法")));

        var messages = mapper.readTree(SECONDARY_REQUEST.get()).path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText())
                .contains("直接给出可执行的回答", "{{tool:0#/sources/0/chunkId}}");
        assertThat(messages.get(messages.size() - 1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(messages.size() - 1).path("content").asText()).isEqualTo("Java怎么学");
        mvc.perform(get("/ai/sessions/{id}/messages", chatId).param("workspace", "true").param("type", "agent")
                        .header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void workspacePresetsArePrivateAndSelectedSkillsOnlyReachTheCurrentModelRequest() throws Exception {
        String chatId = "skill-" + java.util.UUID.randomUUID();
        var skill = mapper.createObjectNode().put("name", "学习提纲").put("content", "用三点清单回答。").put("enabled", true);
        var template = mapper.createObjectNode().put("name", "学习问题").put("content", "请解释 {主题}。");
        String skillId = mapper.readTree(mvc.perform(post("/skills").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(skill.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id").asText();
        String templateId = mapper.readTree(mvc.perform(post("/prompt-templates").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(template.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asText();
        try {
            mvc.perform(get("/skills").header("Authorization", OTHER)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(skillId)).isEmpty());
            mvc.perform(get("/prompt-templates").header("Authorization", OWNER)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '%s')].content".formatted(templateId)).value("请解释 {主题}。"));
            mvc.perform(delete("/prompt-templates/{id}", templateId).header("Authorization", OTHER))
                    .andExpect(status().isNotFound());

            var turn = mapper.createObjectNode().put("chatId", chatId).put("message", "Java怎么学");
            turn.putArray("skillIds").add(skillId);
            mvc.perform(post("/ai/dispatch/stream").header("Authorization", OTHER)
                            .contentType(MediaType.APPLICATION_JSON).content(turn.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("所选 Skill 不可用，请刷新后重新选择"));

            var pending = mvc.perform(post("/ai/dispatch/stream").header("Authorization", OWNER)
                            .contentType(MediaType.APPLICATION_JSON).content(turn.toString()))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
            var messages = mapper.readTree(SECONDARY_REQUEST.get()).path("messages");
            assertThat(messages.get(0).path("content").asText()).doesNotContain("用三点清单回答。");
            assertThat(messages.get(messages.size() - 1).path("content").asText())
                    .startsWith("Java怎么学").contains("学习提纲", "用三点清单回答。");
            mvc.perform(get("/ai/sessions/{id}/messages", chatId).param("workspace", "true").param("type", "agent")
                            .header("Authorization", OWNER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("Java怎么学"));

            skill.put("enabled", false);
            mvc.perform(put("/skills/{id}", skillId).header("Authorization", OWNER)
                            .contentType(MediaType.APPLICATION_JSON).content(skill.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
            mvc.perform(post("/ai/dispatch/stream").header("Authorization", OWNER)
                            .contentType(MediaType.APPLICATION_JSON).content(turn.toString()))
                    .andExpect(status().isBadRequest());
        } finally {
            mvc.perform(delete("/skills/{id}", skillId).header("Authorization", OWNER)).andExpect(status().isNoContent());
            mvc.perform(delete("/prompt-templates/{id}", templateId).header("Authorization", OWNER))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void workspaceAgentReadsDirectoryAndFileThroughTheAuthenticatedBrowserStream() throws Exception {
        String chatId = "workspace-" + java.util.UUID.randomUUID();
        var requestBody = mapper.createObjectNode().put("chatId", chatId)
                .put("message", "工作区集成验证，请说明构建工具").put("localWorkspace", true);
        var pending = mvc.perform(post("/ai/dispatch/stream").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody.toString()))
                .andExpect(request().asyncStarted()).andReturn();

        var list = awaitWorkspaceEvent(pending, 0);
        assertThat(list.path("action").asText()).isEqualTo("LIST");
        assertThat(list.path("path").asText()).isEmpty();
        String runId = latestRun();
        String listId = list.path("requestId").asText();
        var toolNames = mapper.readTree(SECONDARY_REQUEST.get()).path("tools").findValuesAsText("name");
        assertThat(toolNames).containsExactlyInAnyOrder("toolSearchTool", "listWorkspaceFiles", "readWorkspaceFile");
        String listResponse = """
                {"ok":true,"entries":[{"name":"pom.xml","kind":"file"}]}
                """;
        mvc.perform(post("/ai/local-workspace/{runId}/results/{requestId}", runId, listId)
                        .header("Authorization", OTHER).contentType(MediaType.APPLICATION_JSON).content(listResponse))
                .andExpect(status().isNotFound());
        mvc.perform(post("/ai/local-workspace/{runId}/results/{requestId}", runId, listId)
                        .header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON).content(listResponse))
                .andExpect(status().isNoContent());

        var read = awaitWorkspaceEvent(pending, 1);
        assertThat(read.path("action").asText()).isEqualTo("READ");
        assertThat(read.path("path").asText()).isEqualTo("pom.xml");
        mvc.perform(post("/ai/local-workspace/{runId}/results/{requestId}", runId, read.path("requestId").asText())
                        .header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ok\":true,\"content\":\"WORKSPACE_FILE_TEST_CONTENT\"}"))
                .andExpect(status().isNoContent());

        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).containsSubsequence(
                "event:route", "event:thinking", "\"step\":1", "event:tools",
                "event:thinking", "\"step\":2", "event:workspace_request",
                "event:thinking", "\"step\":3", "event:workspace_request",
                "event:thinking", "\"step\":4", "项目使用 Maven", "event:done");
        mvc.perform(get("/ai/sessions/{id}/messages", chatId).param("workspace", "true").param("type", "agent")
                        .header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].text").value("项目使用 Maven"));
    }

    private com.fasterxml.jackson.databind.JsonNode awaitWorkspaceEvent(
            org.springframework.test.web.servlet.MvcResult pending, int index) throws Exception {
        var events = new AtomicReference<java.util.List<String>>();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).untilAsserted(() -> {
            String stream = pending.getResponse().getContentAsString(StandardCharsets.UTF_8);
            events.set(java.util.Arrays.stream(stream.split("\\R\\R"))
                    .filter(event -> event.startsWith("event:workspace_request")).toList());
            assertThat(events.get().size()).isGreaterThan(index);
        });
        String data = events.get().get(index).lines().filter(line -> line.startsWith("data:"))
                .findFirst().orElseThrow().substring(5);
        return mapper.readTree(data);
    }

    @Test
    void dispatchDeliversTheFirstModelDeltaBeforeUpstreamCompletes() throws Exception {
        var gate = new java.util.concurrent.CountDownLatch(1);
        STREAM_GATE.set(gate);
        String chatId = "sse-" + java.util.UUID.randomUUID();
        try {
            String payload = mapper.createObjectNode().put("chatId", chatId).put("message", "Java怎么学").toString();
            var stream = mvc.perform(post("/ai/dispatch/stream").header("Authorization", OWNER)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(request().asyncStarted()).andReturn();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(7)).untilAsserted(() ->
                    assertThat(stream.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .contains("event:route", "event:delta", "先学语法").doesNotContain("再做项目", "event:done"));
            gate.countDown();
            var result = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("再做项目", "event:done");
            mvc.perform(get("/ai/sessions/{id}/messages", chatId).param("workspace", "true").param("type", "agent")
                            .header("Authorization", OWNER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[1].text").value("先学语法和集合再做项目"));
        } finally {
            gate.countDown();
            STREAM_GATE.set(null);
        }
    }

    @Test
    void dispatchMovesAnOlderSessionToTheTopOfHistory() throws Exception {
        String older = "recent-old-" + java.util.UUID.randomUUID();
        String newer = "recent-new-" + java.util.UUID.randomUUID();
        String oldStorageId = ConversationScope.storageId("trace_owner", older, "general");
        String newStorageId = ConversationScope.storageId("trace_owner", newer, "general");
        var memory = memoryRegistry.chatMemory(ConversationMemoryRegistry.MEDICAL_AGENT);
        memory.add(oldStorageId, java.util.List.of(new UserMessage("旧会话")));
        memory.add(newStorageId, java.util.List.of(new UserMessage("新会话")));
        var directory = java.nio.file.Path.of(FileConstant.FILE_SAVE_DIR, "medical-agent-memory");
        var now = java.time.Instant.now();
        java.nio.file.Files.setLastModifiedTime(directory.resolve(oldStorageId + ".kryo"),
                java.nio.file.attribute.FileTime.from(now.minusSeconds(2)));
        java.nio.file.Files.setLastModifiedTime(directory.resolve(newStorageId + ".kryo"),
                java.nio.file.attribute.FileTime.from(now.minusSeconds(1)));

        assertThat(sessionOrder(older, newer)).containsExactly(newer, older);

        String payload = mapper.createObjectNode().put("chatId", older).put("message", "Java怎么学").toString();
        var stream = mvc.perform(post("/ai/dispatch/stream").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        assertThat(sessionOrder(older, newer)).containsExactly(older, newer);
    }

    private java.util.List<String> sessionOrder(String first, String second) throws Exception {
        var response = mvc.perform(get("/ai/sessions").header("Authorization", OWNER)
                        .param("workspace", "true").param("type", "agent"))
                .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).findValuesAsText("id").stream()
                .filter(id -> id.equals(first) || id.equals(second)).toList();
    }

    private String latestRun() throws Exception {
        var response = mvc.perform(get("/agent-runs").header("Authorization", OWNER))
                .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).get(0).path("id").asText();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, "application/json; charset=UTF-8", body);
    }

    private static void respondIndependentTasks(com.sun.net.httpserver.HttpExchange exchange,
                                                com.fasterxml.jackson.databind.JsonNode request) throws IOException {
        var json = new ObjectMapper();
        var messages = request.path("messages");
        String system = messages.get(0).path("content").asText();
        String answer;
        if (system.contains("你是请求分类器")) {
            answer = "{\"intentTypes\":[\"OTHER\"],\"confidence\":1,\"requiresWorkflow\":true}";
        } else if (system.contains("的 Planner")) {
            answer = """
                    {"summary":"两个独立文本任务","steps":[
                      {"id":"step1","role":"资料说明","description":"说明 PDF 入库步骤","successCriteria":"给出文本步骤","dependsOn":[]},
                      {"id":"step2","role":"模板编写","description":"编写北辰项目进度模板","successCriteria":"给出文本模板","dependsOn":[]}
                    ]}
                    """;
        } else if (system.contains("你是结果整合器")) {
            String input = messages.get(messages.size() - 1).path("content").asText();
            answer = input.contains("导入并检查索引状态") && input.contains("里程碑及下一步")
                    ? "PDF 入库步骤：导入并检查索引状态。\n北辰项目进度模板：里程碑及下一步。" : "缺少执行结果";
        } else if (system.contains("工作流 Reviewer")) {
            answer = """
                    {"passed":true,"requiresHumanReview":false,"reviewerRole":"NONE","message":"已核对两个交付项",
                     "issues":[],"revisionInstruction":"","replanRequired":false}
                    """;
        } else {
            if (!request.path("tools").findValuesAsText("name").equals(java.util.List.of("toolSearchTool"))) {
                respond(exchange, 400, "{\"error\":{\"message\":\"工具搜索入口缺失\"}}");
                return;
            }
            String input = messages.get(messages.size() - 1).path("content").asText();
            answer = input.contains("当前步骤：1.") ? "导入并检查索引状态。" : "里程碑及下一步。";
        }
        var reply = json.createObjectNode().put("id", "workflow-test").put("object", "chat.completion")
                .put("created", 1).put("model", "secondary-actual");
        reply.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop")
                .putObject("message").put("role", "assistant").put("content", answer);
        respond(exchange, 200, reply.toString());
    }

    private static void respondWorkspaceModel(com.sun.net.httpserver.HttpExchange exchange,
                                              com.fasterxml.jackson.databind.JsonNode request) throws IOException {
        var json = new ObjectMapper();
        if (!request.path("stream").asBoolean()) {
            var response = json.createObjectNode().put("id", "workspace-route").put("object", "chat.completion")
                    .put("created", 1).put("model", "secondary-actual");
            response.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop").putObject("message")
                    .put("role", "assistant").put("content",
                            "{\"intentTypes\":[\"OTHER\"],\"confidence\":1,\"requiresWorkflow\":true}");
            respond(exchange, 200, response.toString());
            return;
        }
        var messages = request.path("messages");
        long toolReplies = messages.findValuesAsText("role").stream().filter("tool"::equals).count();
        var response = json.createObjectNode().put("id", "workspace-turn-" + toolReplies)
                .put("object", "chat.completion.chunk").put("created", 1).put("model", "secondary-actual");
        var choice = response.putArray("choices").addObject().put("index", 0);
        var delta = choice.putObject("delta").put("role", "assistant");
        if (toolReplies < 3) {
            choice.put("finish_reason", "tool_calls");
            String tool = switch ((int) toolReplies) {
                case 0 -> "toolSearchTool";
                case 1 -> "listWorkspaceFiles";
                default -> "readWorkspaceFile";
            };
            String arguments = switch ((int) toolReplies) {
                case 0 -> "{\"query\":\"listWorkspaceFiles readWorkspaceFile\"}";
                case 1 -> "{}";
                default -> "{\"path\":\"pom.xml\"}";
            };
            if (toolReplies == 0) {
                assertThat(request.path("tools").findValuesAsText("name")).containsExactly("toolSearchTool");
            }
            delta.putArray("tool_calls").addObject().put("index", 0).put("id", "workspace-tool-" + toolReplies)
                    .put("type", "function").putObject("function")
                    .put("name", tool).put("arguments", arguments);
        } else {
            choice.put("finish_reason", "stop");
            delta.put("content", messages.toString().contains("WORKSPACE_FILE_TEST_CONTENT") ? "项目使用 Maven" : "未取得文件");
        }
        respond(exchange, 200, "text/event-stream", "data: " + response + "\n\ndata: [DONE]\n\n");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
