package com.tianaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianaiagent.domain.agent.adapter.port.AgentTools;
import com.tianaiagent.domain.agent.adapter.repository.AgentRunRepository;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.agent.model.valobj.ModelProvider;
import com.tianaiagent.domain.conversation.adapter.port.ConversationMemoryRegistry;
import com.tianaiagent.domain.conversation.adapter.repository.LongTermMemoryStore;
import com.tianaiagent.domain.conversation.adapter.repository.UserProfileStore;
import com.tianaiagent.domain.conversation.service.ConversationScope;
import com.tianaiagent.domain.knowledge.adapter.repository.KnowledgeRepository;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.infrastructure.adapter.repository.MysqlWorkspaceDatabase;
import com.tianaiagent.infrastructure.adapter.repository.agent.FileAgentRunRepository;
import com.tianaiagent.infrastructure.adapter.repository.agent.FileModelProviderStore;
import com.tianaiagent.infrastructure.adapter.repository.agent.MysqlModelProviderStore;
import com.tianaiagent.infrastructure.adapter.repository.conversation.FileBasedChatMemory;
import com.tianaiagent.infrastructure.dao.mysql.ModelProviderMapper;
import com.tianaiagent.infrastructure.gateway.model.RoutedChatModel;
import com.tianaiagent.types.storage.FileConstant;
import com.tianaiagent.types.exception.ModelUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "tian.mysql-test.url", matches = "jdbc:mysql:.*")
@SpringBootTest(properties = {
        "spring.ai.dashscope.api-key=",
        "doc-mcp.enabled=true",
        "doc-mcp.mineru.enabled=false",
        "doc-mcp.vector-store=local",
        "doc-mcp.embedding.provider=local",
        "spring.datasource.url=jdbc:sqlite::memory:",
        "agent.memory.backend=file",
        "agent.memory.max-messages=2",
        "agent.memory.long-term.enabled=false",
        "agent.access.users[0].id=db_owner",
        "agent.access.users[0].token=mysql-owner-test-token-123456",
        "agent.access.users[1].id=db_other",
        "agent.access.users[1].token=mysql-other-test-token-123456"
})
@AutoConfigureMockMvc
class WorkspaceMysqlPersistenceTest {
    private static final String OWNER = "Bearer mysql-owner-test-token-123456";
    private static final String OTHER = "Bearer mysql-other-test-token-123456";
    private static final String MEMORY = ConversationMemoryRegistry.MEDICAL_AGENT;
    private static final String CHAT = ConversationScope.storageId("db_owner", "existing-chat", "general");
    private static final String LEGACY_RUN_ID = "aabb00112233445566778899aabbccdd";
    private static final String MCP_TOKEN = "remote-mcp-test-token";
    private static final AtomicInteger MCP_CALLS = new AtomicInteger();
    private static HttpServer mcpServer;
    private static String mcpOrigin;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MysqlWorkspaceDatabase database;
    @Autowired ConversationMemoryRegistry memory;
    @Autowired UserProfileStore profiles;
    @Autowired LongTermMemoryStore legacyMemory;
    @Autowired KnowledgeRepository knowledge;
    @Autowired AgentRunRepository runs;
    @Autowired AgentRunAudit audit;
    @Autowired AgentTools agentTools;
    @MockitoSpyBean RoutedChatModel chatModel;
    private JdbcTemplate jdbc;

    @BeforeEach
    void connectSqlAssertions() {
        jdbc = new JdbcTemplate(database.dataSource());
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws IOException {
        properties.add("agent.persistence.mysql.jdbc-url", () -> System.getProperty("tian.mysql-test.url"));
        properties.add("agent.persistence.mysql.username", () -> "root");
        properties.add("agent.persistence.mysql.password", () -> "");
        mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        mcpServer.createContext("/mcp", exchange -> {
            try {
                if (!("Bearer " + MCP_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                var request = json.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                if (method.startsWith("notifications/")) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                String result = switch (method) {
                    case "initialize" -> """
                            {"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"test","version":"1"}}
                            """;
                    case "tools/list" -> """
                            {"tools":[{"name":"echo","description":"Return input","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}},
                                      {"name":"ignored","inputSchema":{"type":"object"}}]}
                            """;
                    case "tools/call" -> {
                        MCP_CALLS.incrementAndGet();
                        var params = request.path("params");
                        if (!"echo".equals(params.path("name").asText())) throw new IllegalArgumentException("Unexpected tool");
                        String text = params.path("arguments").path("text").asText();
                        if ("mcp-round-trip".equals(text)) {
                            try {
                                Thread.sleep(7_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new java.io.InterruptedIOException("MCP test server interrupted");
                            }
                        }
                        yield json.writeValueAsString(Map.of("content", List.of(Map.of("type", "text",
                                "text", text)), "isError", false));
                    }
                    default -> "{}";
                };
                byte[] bytes = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"),
                        "result", json.readTree(result)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        mcpServer.start();
        mcpOrigin = "http://127.0.0.1:" + mcpServer.getAddress().getPort();
        properties.add("agent.mcp.allowed-origins[0]", () -> mcpOrigin);
        new FileModelProviderStore(new ObjectMapper()).save("db_owner", List.of(
                new ModelProvider("legacy", ModelProvider.Protocol.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com", "legacy-secret-placeholder", "legacy-model", 1,
                        true, null, true, null)));
        new FileBasedChatMemory(Path.of(FileConstant.FILE_SAVE_DIR, MEMORY + "-memory").toString())
                .add(CHAT, List.of(new UserMessage("旧会话"), new AssistantMessage("旧回答")));
        var started = Instant.parse("2026-01-02T03:04:05.123456Z");
        new FileAgentRunRepository(new ObjectMapper().findAndRegisterModules()).save(new AgentRun(
                new AgentRun.Summary(LEGACY_RUN_ID, "db_owner", "DISPATCH", null, null, "COMPLETED", started,
                        10, 1, null, null, null),
                List.of(new AgentRun.Event("STAGE", "意图识别", null, null, "COMPLETED", started, 10,
                        null, null, null, null))));
    }

    @AfterAll
    static void stopMcpServer() {
        if (mcpServer != null) mcpServer.stop(0);
    }

    @Test
    void importsAnMcpEndpointAndRegistersItsToolsOnSave() throws Exception {
        var body = mapper.createObjectNode().put("url", mcpOrigin + "/mcp").put("token", MCP_TOKEN);
        var invalid = body.deepCopy().put("token", "wrong-test-token");
        mvc.perform(post("/mcp-servers").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
                .andExpect(status().isBadGateway());
        mvc.perform(get("/mcp-servers").header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        var imported = mvc.perform(post("/mcp-servers").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.tools[*].name").value(org.hamcrest.Matchers.contains("echo", "ignored")))
                .andReturn().getResponse();
        String id = mapper.readTree(imported.getContentAsString()).path("id").asText();
        var run = audit.start("db_owner", "DISPATCH", null);
        try {
            var callbacks = AgentRunContext.within(run, () -> java.util.Arrays.stream(agentTools.forSpace(null, false))
                    .filter(tool -> tool.getToolDefinition().name().startsWith("mcp_" + id + "_")).toList());
            assertThat(callbacks).hasSize(2);
            var echo = callbacks.stream().filter(tool -> tool.getToolDefinition().name().contains("_echo_"))
                    .findFirst().orElseThrow();
            assertThat(AgentRunContext.within(run, () -> echo.call("{\"text\":\"导入后可调用\"}")))
                    .contains("导入后可调用");
            mvc.perform(delete("/mcp-servers/{id}", id).header("Authorization", OWNER))
                    .andExpect(status().isNoContent());
            assertThat(AgentRunContext.within(run, () -> agentTools.forSpace(null, false)))
                    .noneMatch(tool -> tool.getToolDefinition().name().startsWith("mcp_" + id + "_"));
        } finally {
            run.finish("COMPLETED");
        }
    }

    @Test
    void listsTheCurrentAccountsRegisteredMcpToolsWithoutCallingThem() throws Exception {
        var body = mapper.createObjectNode().put("url", mcpOrigin + "/mcp").put("token", MCP_TOKEN);
        body.putArray("tools").add("echo");
        var imported = mvc.perform(post("/mcp-servers").header("Authorization", OTHER)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String id = mapper.readTree(imported.getContentAsString()).path("id").asText();
        int callsBefore = MCP_CALLS.get();
        try {
            String stream = dispatch(OTHER, "mcp-tool-inventory", "当前有哪些工具可用");
            assertThat(stream).contains("TOOL_INVENTORY", "searchWeb", "个人 MCP 工具", "`echo`", "127.0.0.1")
                    .doesNotContain("`ignored`", MCP_TOKEN, "event:tools");
            String otherStream = dispatch(OWNER, "mcp-tool-inventory", "列出当前可用的 MCP 工具");
            assertThat(otherStream).contains("MCP_TOOL_INVENTORY", "当前会话没有已启用的个人 MCP 工具")
                    .doesNotContain("`echo`", "searchWeb");
            var run = audit.recent("db_other").stream().filter(summary -> summary.kind().equals("DISPATCH"))
                    .findFirst().orElseThrow();
            assertThat(runs.find(run.id()).orElseThrow().events())
                    .anyMatch(event -> "读取工具清单".equals(event.name()))
                    .noneMatch(event -> "MODEL".equals(event.kind()) || "TOOL".equals(event.kind()));
            assertThat(storedContents(ConversationScope.storageId("db_other", "mcp-tool-inventory", "general")))
                    .anyMatch(text -> text.contains("`echo`"));
        } finally {
            mvc.perform(delete("/mcp-servers/{id}", id).header("Authorization", OTHER))
                    .andExpect(status().isNoContent());
        }
        assertThat(dispatch(OTHER, "mcp-tool-inventory", "当前有哪些工具可用"))
                .contains("当前会话没有已启用的个人 MCP 工具").doesNotContain("`echo`");
        assertThat(MCP_CALLS.get()).isEqualTo(callsBefore);
    }

    @Test
    void dispatchesARegisteredMcpToolAndReturnsItsResultToTheModel() throws Exception {
        var body = mapper.createObjectNode().put("url", mcpOrigin + "/mcp").put("token", MCP_TOKEN);
        body.putArray("tools").add("echo");
        var imported = mvc.perform(post("/mcp-servers").header("Authorization", OTHER)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String id = mapper.readTree(imported.getContentAsString()).path("id").asText();
        var offeredTool = new AtomicReference<JsonNode>();
        var toolResult = new AtomicReference<String>();
        String modelPath = "/test-model/chat/completions";
        mcpServer.createContext(modelPath, exchange -> {
            try (exchange) {
                var payload = mapper.readTree(exchange.getRequestBody());
                var reply = mapper.createObjectNode().put("id", "mcp-test").put("created", 1).put("model", "test-model");
                var choice = reply.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
                byte[] bytes;
                if (!payload.path("stream").asBoolean()) {
                    reply.put("object", "chat.completion");
                    choice.putObject("message").put("role", "assistant").put("content",
                            "{\"intentTypes\":[\"OTHER\"],\"confidence\":0.95,\"requiresWorkflow\":false}");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    bytes = mapper.writeValueAsBytes(reply);
                } else {
                    reply.put("object", "chat.completion.chunk");
                    var delta = choice.putObject("delta").put("role", "assistant");
                    for (var message : payload.path("messages")) {
                        if ("tool".equals(message.path("role").asText())) {
                            toolResult.set(message.path("content").asText());
                        }
                    }
                    if (toolResult.get() != null) {
                        delta.put("content", "MCP 调用完成");
                    } else {
                        for (var tool : payload.path("tools")) {
                            var function = tool.path("function");
                            if (function.path("name").asText().startsWith("mcp_" + id + "_echo_")) {
                                offeredTool.set(function);
                            }
                        }
                        if (offeredTool.get() == null) {
                            delta.put("content", "未收到 MCP 工具");
                        } else {
                            choice.put("finish_reason", "tool_calls");
                            delta.putArray("tool_calls").addObject().put("index", 0).put("id", "echo-1")
                                    .put("type", "function").putObject("function")
                                    .put("name", offeredTool.get().path("name").asText())
                                    .put("arguments", "{\"text\":\"mcp-round-trip\"}");
                        }
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    bytes = ("data: " + reply + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        var model = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(mcpOrigin + "/test-model")
                        .completionsPath("/chat/completions").apiKey("mock-model-key").build())
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build()).build();
        chatModel.prepareTargets("db_other", List.of(new RoutedChatModel.Target("test", "test-model", 0, model)), true).run();
        int callsBefore = MCP_CALLS.get();
        try {
            String stream = dispatch(OTHER, "mcp-round-trip-test", "请调用 MCP 的 echo 工具，把返回结果告诉我");
            assertThat(offeredTool.get()).isNotNull();
            String toolName = offeredTool.get().path("name").asText();
            assertThat(offeredTool.get().path("description").asText()).contains("127.0.0.1", "echo", "Return input")
                    .doesNotContain(MCP_TOKEN);
            assertThat(offeredTool.get().path("parameters").path("properties").has("text")).isTrue();
            assertThat(MCP_CALLS.get()).isEqualTo(callsBefore + 1);
            assertThat(toolResult.get()).contains("mcp-round-trip");
            assertThat(stream).contains("MCP 调用完成", "event:done");
            var run = audit.recent("db_other").stream().filter(summary -> summary.kind().equals("DISPATCH"))
                    .findFirst().orElseThrow();
            assertThat(runs.find(run.id()).orElseThrow().events())
                    .anyMatch(event -> "STAGE".equals(event.kind()) && "已加载 1 个个人 MCP 工具".equals(event.name()))
                    .anyMatch(event -> "TOOL".equals(event.kind()) && toolName.equals(event.name())
                            && "SUCCESS".equals(event.status()));
        } finally {
            chatModel.prepareTargets("db_other", List.of(), false).run();
            mcpServer.removeContext(modelPath);
            mvc.perform(delete("/mcp-servers/{id}", id).header("Authorization", OTHER))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void discoversAndRunsOnlyApprovedPersonalMcpTools() throws Exception {
        var body = mapper.createObjectNode().put("url", mcpOrigin + "/mcp").put("token", MCP_TOKEN).put("enabled", true);
        body.putArray("tools").add("echo");
        var forbidden = body.deepCopy().put("url", mcpOrigin.replace("127.0.0.1", "localhost") + "/mcp");
        mvc.perform(post("/mcp-servers/echo-test/discover").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(forbidden.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/mcp-servers/echo-test/discover").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("echo"))
                .andExpect(jsonPath("$[1].name").value("ignored"));
        var saved = mvc.perform(put("/mcp-servers/echo-test").header("Authorization", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(true))
                .andExpect(jsonPath("$.tools.length()").value(1)).andReturn().getResponse();
        assertThat(saved.getContentAsString()).doesNotContain(MCP_TOKEN);
        byte[] encrypted = jdbc.queryForObject("SELECT token_ciphertext FROM agent_mcp_server WHERE server_id=?",
                byte[].class, "echo-test");
        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain(MCP_TOKEN);
        mvc.perform(get("/mcp-servers").header("Authorization", OTHER))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(delete("/mcp-servers/echo-test").header("Authorization", OTHER)).andExpect(status().isNotFound());

        var ownerRun = audit.start("db_owner", "DISPATCH", null);
        var otherRun = audit.start("db_other", "DISPATCH", null);
        int callsBefore = MCP_CALLS.get();
        try {
            var callback = AgentRunContext.within(ownerRun, () -> java.util.Arrays.stream(agentTools.forSpace(null, false))
                    .filter(tool -> tool.getToolDefinition().name().startsWith("mcp_")).findFirst().orElseThrow());
            assertThat(AgentRunContext.within(otherRun, () -> agentTools.forSpace(null, false)))
                    .noneMatch(tool -> tool.getToolDefinition().name().startsWith("mcp_"));
            assertThat(AgentRunContext.within(ownerRun, () -> callback.call("{\"text\":\"工具回复\"}")))
                    .contains("工具回复");
            assertThat(MCP_CALLS.get()).isEqualTo(callsBefore + 1);
            mvc.perform(delete("/mcp-servers/echo-test").header("Authorization", OWNER)).andExpect(status().isNoContent());
            assertThat(AgentRunContext.within(ownerRun, () -> callback.call("{\"text\":\"撤销后\"}")))
                    .contains("MCP_TOOL_REVOKED");
            assertThat(MCP_CALLS.get()).isEqualTo(callsBefore + 1);
        } finally {
            ownerRun.finish("COMPLETED");
            otherRun.finish("COMPLETED");
        }
        assertThat(runs.find(ownerRun.id()).orElseThrow().events())
                .anyMatch(event -> "TOOL".equals(event.kind()) && "SUCCESS".equals(event.status()))
                .anyMatch(event -> "MCP_TOOL_REVOKED".equals(event.reason()));
    }

    @Test
    void readsAndDeletesAnExistingMysqlSummary() {
        String updatedAt = "2026-01-02T03:04:05Z";
        jdbc.update("""
                INSERT INTO agent_long_term_memory(owner_id,summary_text,facts_json,source_message_count,updated_at)
                VALUES (?,?,?,?,?)
                """, "legacy-owner", "旧摘要", "[\"已学习集合\"]", 6, updatedAt);
        assertThat(legacyMemory.find("legacy-owner")).contains(new LongTermMemoryStore.StructuredMemory(
                "legacy-owner", "旧摘要", List.of("已学习集合"), 6, updatedAt));
        assertThat(legacyMemory.delete("legacy-owner")).isEqualTo(1);
        assertThat(legacyMemory.find("legacy-owner")).isEmpty();
    }

    @Test
    void storesModelAttemptsAndLegacyEventsInMysql() throws Exception {
        assertThat(knowledge.findSpace(KnowledgeSpace.MEDICAL.id())).contains(KnowledgeSpace.MEDICAL);
        mvc.perform(get("/agent-runs/{id}", LEGACY_RUN_ID).header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].name").value("意图识别"));
        var imported = runs.find(LEGACY_RUN_ID).orElseThrow();
        assertThat(imported.summary().startedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05.123456Z"));
        runs.save(imported);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_log WHERE run_id=?",
                Integer.class, LEGACY_RUN_ID)).isEqualTo(1);

        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(primary.call(any(Prompt.class))).thenThrow(
                new RestClientResponseException("RATE_LIMIT_PRIVATE_BODY", 429, "Too Many Requests", null, null, null));
        when(backup.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("PRIVATE_MODEL_REPLY"))),
                ChatResponseMetadata.builder().model("actual-model").usage(new DefaultUsage(17, 5)).build()));
        chatModel.prepareTargets("db_other", List.of(
                new RoutedChatModel.Target("primary", "configured-primary", 0, primary),
                new RoutedChatModel.Target("backup", "configured-backup", 1, backup)), true).run();
        try {
            mvc.perform(post("/ai/chat").header("Authorization", OTHER).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"audit-mysql-chat\",\"message\":\"PRIVATE_USER_QUESTION\"}"))
                    .andExpect(status().isOk());
            var run = runs.recent("db_other", 1).getFirst();
            assertThat(run.totalTokens()).isEqualTo(22L);
            assertThat(jdbc.queryForList("""
                    SELECT provider_id FROM agent_request_log WHERE run_id=? AND kind='MODEL' ORDER BY event_no
                    """, String.class, run.id())).containsExactly("primary", "backup");
            var response = mvc.perform(get("/agent-runs/{id}", run.id()).header("Authorization", OTHER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events[0].reason").value("HTTP_429"))
                    .andExpect(jsonPath("$.events[1].model").value("actual-model"))
                    .andExpect(jsonPath("$.events[1].inputTokens").value(17))
                    .andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("RATE_LIMIT_PRIVATE_BODY", "PRIVATE_MODEL_REPLY", "PRIVATE_USER_QUESTION");
            mvc.perform(get("/agent-runs/{id}", run.id()).header("Authorization", OWNER)).andExpect(status().isNotFound());
        } finally {
            chatModel.prepareTargets("db_other", List.of(), false).run();
        }
    }

    @Test
    void conversationMigratesAndPreservesHistoryBeyondTheContextWindow() throws Exception {
        long conversationId = jdbc.queryForObject(
                "SELECT id FROM agent_conversation WHERE storage_id=?", Long.class, CHAT);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_conversation_message m JOIN agent_conversation c ON m.conversation_id=c.id
                WHERE c.storage_id=?
                """, Integer.class, CHAT)).isEqualTo(2);
        mvc.perform(get("/ai/sessions/existing-chat/messages").header("Authorization", OWNER)
                        .param("workspace", "true").param("type", "agent"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        var call = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("tool-1", "function", "lookup", "{\"q\":\"中文\"}")));
        var response = new ToolResponseMessage(
                List.of(new ToolResponseMessage.ToolResponse("tool-1", "lookup", "结果")), Map.of());
        memory.chatMemory(MEMORY).add(CHAT, List.of(new UserMessage("新消息😀"), call, response));
        assertThat(memory.chatMemory(MEMORY).get(CHAT)).hasSize(2);
        var history = memory.history(MEMORY, CHAT);
        assertThat(history).hasSize(5);
        assertThat(history.subList(0, 3).stream().map(Message::getText)).containsExactly("旧会话", "旧回答", "新消息😀");
        assertThat(((AssistantMessage) history.get(3)).getToolCalls()).isEqualTo(call.getToolCalls());
        assertThat(((ToolResponseMessage) history.get(4)).getResponses()).isEqualTo(response.getResponses());
        assertThat(jdbc.queryForObject("SELECT owner_id FROM agent_conversation WHERE storage_id=?",
                String.class, CHAT)).isEqualTo("db_owner");

        mvc.perform(get("/ai/sessions/existing-chat/messages").header("Authorization", OTHER)
                        .param("workspace", "true").param("type", "agent"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        profiles.merge("db_owner", new UserProfileStore.Profile(Map.of("skill_level", "初学者"), Map.of("language", "中文")));
        profiles.merge("db_owner", new UserProfileStore.Profile(Map.of("learning_goal", "学习 Java"), Map.of("language", "English")));
        mvc.perform(delete("/ai/sessions/existing-chat").header("Authorization", OWNER)
                        .param("workspace", "true").param("type", "agent"))
                .andExpect(status().isOk());
        assertThat(memory.history(MEMORY, CHAT)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation_message WHERE conversation_id=?",
                Integer.class, conversationId)).isZero();
        assertThat(profiles.find("db_owner")).contains(new UserProfileStore.Profile(
                Map.of("skill_level", "初学者", "learning_goal", "学习 Java"), Map.of("language", "English")));
        assertThat(profiles.find("db_other")).isEmpty();
    }

    @Test
    void dispatchStreamCommitsTheQuestionBeforeTheModelAndKeepsEveryReply() throws Exception {
        String chatId = "dispatch-mysql-chat";
        String storageId = ConversationScope.storageId("db_owner", chatId, "general");
        String firstQuestion = "Java 怎么学？";
        String reply = "先学集合，再做练习。";
        String secondQuestion = "接下来学什么？";

        doReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"intentTypes\":[\"OTHER\"],\"confidence\":0.95,\"requiresWorkflow\":false}")))))
                .when(chatModel).call(any(Prompt.class));
        doAnswer(invocation -> {
            assertThat(storedContents(storageId)).containsExactly(firstQuestion);
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(reply)))));
        }).when(chatModel).stream(any(Prompt.class));
        assertThat(dispatch(chatId, firstQuestion)).contains("event:done");
        assertThat(storedContents(storageId)).containsExactly(firstQuestion, reply);

        doAnswer(invocation -> {
            assertThat(storedContents(storageId)).containsExactly(firstQuestion, reply, secondQuestion);
            return Flux.error(new ModelUnavailableException("TEST_UNAVAILABLE", "模型暂不可用"));
        }).when(chatModel).stream(any(Prompt.class));
        assertThat(dispatch(chatId, secondQuestion)).contains("event:error");
        assertThat(storedContents(storageId)).containsExactly(firstQuestion, reply, secondQuestion);

        mvc.perform(get("/ai/sessions/{id}/messages", chatId).header("Authorization", OWNER)
                        .param("workspace", "true").param("type", "agent"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    private String dispatch(String chatId, String question) throws Exception {
        return dispatch(OWNER, chatId, question);
    }

    private String dispatch(String authorization, String chatId, String question) throws Exception {
        var body = mapper.createObjectNode().put("chatId", chatId).put("message", question);
        var pending = mvc.perform(post("/ai/dispatch/stream").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> storedContents(String storageId) {
        return jdbc.queryForList("""
                SELECT m.content FROM agent_conversation c
                JOIN agent_conversation_message m ON m.conversation_id=c.id
                WHERE c.memory_space=? AND c.storage_id=? ORDER BY m.id
                """, String.class, MEMORY, storageId);
    }

    @Test
    void modelSettingsImportAndSaveEncryptedByOwner() throws Exception {
        var original = mvc.perform(get("/model-providers").header("Authorization", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers[0].id").value("legacy"))
                .andExpect(jsonPath("$.providers[0].apiKey").doesNotExist()).andReturn().getResponse();
        String version = mapper.readTree(original.getContentAsString()).path("version").asText();
        var update = mapper.createObjectNode().put("version", version);
        update.putArray("providers").addObject().put("id", "legacy").put("protocol", "OPENAI_COMPATIBLE")
                .put("baseUrl", "https://api.deepseek.com").put("model", "updated-model").put("priority", 3);
        mvc.perform(put("/model-providers").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content(update.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers[0].hasApiKey").value(true));
        mvc.perform(get("/model-providers").header("Authorization", OTHER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers").isEmpty());
        byte[] ciphertext = jdbc.queryForObject("""
                SELECT api_key_ciphertext FROM agent_model_provider WHERE owner_id=? AND provider_id=?
                """, byte[].class, "db_owner", "legacy");
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain("legacy-secret-placeholder");

        var repository = new MysqlModelProviderStore(database.mapper(ModelProviderMapper.class), database.transactions());
        var row = repository.load().get("db_owner").getFirst();
        assertThat(row.model()).isEqualTo("updated-model");
        assertThat(row.apiKey()).isEqualTo("legacy-secret-placeholder");
        repository.save("db_owner", List.of());
        repository.importLegacy(new FileModelProviderStore(mapper));
        assertThat(repository.load()).doesNotContainKey("db_owner");
    }
}
