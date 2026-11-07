package com.tianaiagent.modelrouting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NATIVE_PATH = "/api/v1/services/aigc/text-generation/generation";
    private static final String COMPATIBLE_PATH = "/chat/completions";
    private final ToolCallingManager tools = ToolCallingManager.builder().build();
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ModelRoutingConfiguration.class)
            .withBean(ToolCallingManager.class, () -> tools);

    @Test
    void usesLegacyModelWhenRoutesAreAbsent() {
        ChatModel legacy = mock(ChatModel.class);
        context.withBean("dashscopeChatModel", ChatModel.class, () -> legacy)
                .run(app -> assertThat(app.getBean(ChatModel.class)).isSameAs(legacy));
    }

    @Test
    void adaptsBothWireFormatsAndSwitchesOnRateLimit() throws IOException {
        AtomicInteger nativeCalls = new AtomicInteger();
        AtomicReference<JsonNode> nativeBody = new AtomicReference<>();
        AtomicReference<JsonNode> compatibleBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(NATIVE_PATH, exchange -> {
            nativeBody.set(readBody(exchange));
            if (nativeCalls.getAndIncrement() == 0) {
                reply(exchange, 200, "application/json", """
                        {"request_id":"one","output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"native"}}]},
                        "usage":{"input_tokens":4,"output_tokens":2}}
                        """);
            } else {
                reply(exchange, 429, "application/json", "{\"message\":\"private upstream error\"}");
            }
        });
        server.createContext(COMPATIBLE_PATH, exchange -> {
            compatibleBody.set(readBody(exchange));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, "application/json", """
                    {"id":"two","model":"compatible-model","choices":[{"index":0,"finish_reason":"stop",
                    "message":{"role":"assistant","content":"backup"}}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                    """);
        });
        server.start();
        try {
            configured(server, 10).run(app -> {
                ChatClient client = ChatClient.create(app.getBean(ChatModel.class));
                assertThat(client.prompt().user("question").call().content()).isEqualTo("native");
                assertThat(client.prompt().user("question").call().content()).isEqualTo("backup");
                assertThat(client.prompt().user("question").call().content()).isEqualTo("backup");
                assertThat(nativeCalls).hasValue(2);
                assertThat(nativeBody.get().at("/model").asText()).isEqualTo("native-model");
                assertThat(nativeBody.get().at("/input/messages/0/content").asText()).isEqualTo("question");
                assertThat(compatibleBody.get().at("/model").asText()).isEqualTo("compatible-model");
                assertThat(compatibleBody.get().at("/messages/0/content").asText()).isEqualTo("question");
                assertThat(compatibleBody.get().at("/reasoning_effort").asText()).isEqualTo("none");
                assertThat(authorization.get()).isEqualTo("Bearer compatible-test-key");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void roundRobinsOnlyWithinSamePriorityAndSkipsUnsupportedTools() {
        ChatModel first = mock(ChatModel.class);
        ChatModel second = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(first.call(any(Prompt.class))).thenReturn(response("first"));
        when(second.call(any(Prompt.class))).thenReturn(response("second"));
        when(backup.call(any(Prompt.class))).thenReturn(response("backup"));
        var model = routing(new RoutingChatModel.Route("first", 0, false, true, first),
                new RoutingChatModel.Route("second", 0, true, true, second), route("backup", 1, backup));

        assertThat(model.call("question")).isEqualTo("first");
        assertThat(model.call("question")).isEqualTo("second");
        assertThat(model.call("question")).isEqualTo("first");
        var options = ToolCallingChatOptions.builder().toolNames("lookup").build();
        assertThat(model.call(new Prompt("question", options)).getResult().getOutput().getText()).isEqualTo("second");
        verify(backup, never()).call(any(Prompt.class));
    }

    @Test
    void invalidRequestsDoNotGoToBackupOrPutProviderInCooldown() {
        ChatModel first = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(first.call(any(Prompt.class))).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "private details"))
                .thenReturn(response("first"));
        var model = routing(route("first", 0, first), route("backup", 1, backup));

        assertThatThrownBy(() -> model.call("question")).hasMessage("模型请求失败（HTTP_400）").hasNoCause();
        assertThat(model.call("question")).isEqualTo("first");
        verify(backup, never()).call(any(Prompt.class));
    }

    @Test
    void failsOverStreamingOnlyBeforeVisibleOutput() throws IOException {
        AtomicInteger backupCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(NATIVE_PATH, exchange -> reply(exchange, 503, "application/json", "{}"));
        server.createContext(COMPATIBLE_PATH, exchange -> {
            backupCalls.incrementAndGet();
            reply(exchange, 200, "text/event-stream", """
                    data: {"id":"chunk","model":"compatible-model","choices":[{"index":0,"delta":{"role":"assistant","content":"backup"},"finish_reason":null}]}

                    data: [DONE]

                    """);
        });
        server.start();
        try {
            configured(server, 10).run(app -> {
                ChatClient client = ChatClient.create(app.getBean(ChatModel.class));
                assertThat(client.prompt().user("question").stream().content().collectList().block(Duration.ofSeconds(10)))
                        .containsExactly("backup");
                assertThat(backupCalls).hasValue(1);
            });
        } finally {
            server.stop(0);
        }

        ChatModel interrupted = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(interrupted.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("partial")),
                Flux.error(new ResourceAccessException("private upstream error"))));
        var model = routing(route("interrupted", 0, interrupted), route("backup", 1, backup));
        List<ChatResponse> chunks = new ArrayList<>();
        assertThatThrownBy(() -> model.stream(new Prompt("question")).doOnNext(chunks::add).blockLast())
                .hasMessage("模型流式输出已中断");
        assertThat(chunks).hasSize(1);
        verify(backup, never()).stream(any(Prompt.class));
    }

    @Test
    void carriesToolResultsToBackupWithoutReexecutingTheTool() {
        ChatModel first = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        var toolRequest = new ChatResponse(List.of(new Generation(new AssistantMessage("", java.util.Map.of(),
                List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{\"query\":\"test\"}"))))));
        when(first.call(any(Prompt.class))).thenReturn(toolRequest).thenThrow(new ResourceAccessException("unavailable"));
        when(backup.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt continuation = invocation.getArgument(0);
            assertThat(continuation.getInstructions().getLast()).isInstanceOf(ToolResponseMessage.class);
            return response("backup");
        });
        AtomicInteger executions = new AtomicInteger();
        var callback = FunctionToolCallback.builder("lookup", (Lookup request) -> {
            executions.incrementAndGet();
            return request.query();
        }).description("Look up a query").inputType(Lookup.class).build();
        var model = routing(route("first", 0, first), route("backup", 1, backup));
        var client = ChatClient.create(model);

        assertThat(client.prompt().user("question").toolCallbacks(callback).call().content()).isEqualTo("backup");
        assertThat(executions).hasValue(1);
        var manual = ToolCallingChatOptions.builder().toolCallbacks(callback).internalToolExecutionEnabled(false).build();
        var manualModel = mock(ChatModel.class);
        when(manualModel.call(any(Prompt.class))).thenReturn(toolRequest);
        assertThat(routing(route("manual", 0, manualModel)).call(new Prompt("question", manual)).hasToolCalls()).isTrue();
        assertThat(executions).hasValue(1);
    }

    private ApplicationContextRunner configured(HttpServer server, int backupPriority) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        String first = "agent.model-routing.providers[0].";
        String second = "agent.model-routing.providers[1].";
        return context.withPropertyValues(first + "id=native", first + "protocol=dashscope",
                first + "base-url=" + url, first + "api-key=native-test-key", first + "model=native-model",
                second + "id=compatible", second + "protocol=openai-compatible", second + "base-url=" + url,
                second + "completions-path=" + COMPATIBLE_PATH, second + "api-key=compatible-test-key",
                second + "model=compatible-model", second + "reasoning-effort=none", second + "priority=" + backupPriority);
    }

    private RoutingChatModel routing(RoutingChatModel.Route... routes) {
        return new RoutingChatModel(List.of(routes), Duration.ofSeconds(3), Duration.ofSeconds(30), tools);
    }

    private RoutingChatModel.Route route(String id, int priority, ChatModel model) {
        return new RoutingChatModel.Route(id, priority, true, true, model);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        return JSON.readTree(exchange.getRequestBody().readAllBytes());
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    public record Lookup(String query) {}
}
