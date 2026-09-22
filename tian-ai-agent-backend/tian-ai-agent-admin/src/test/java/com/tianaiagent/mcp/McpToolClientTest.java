package com.tianaiagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recognizesExpiredSessionError() throws Exception {
        McpToolClient client = new McpToolClient(objectMapper);
        JsonNode error = objectMapper.readTree(
                "{\"code\":-32600,\"message\":\"Session not found\"}");

        assertThat(client.isSessionNotFound(error)).isTrue();
    }

    @Test
    void reinitializesAndRetriesCurrentCallWhenSessionIsMissing() throws Exception {
        AtomicInteger initialized = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, initialized));
        server.start();
        try {
            McpToolClient client = new McpToolClient(objectMapper);
            ReflectionTestUtils.setField(client, "endpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            ReflectionTestUtils.setField(client, "sessionRefreshAfterIdleSeconds", 1500L);

            assertThat(client.call("query_knowledge", Map.of("question", "test")))
                    .isEqualTo("ok");
            assertThat(initialized.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange, AtomicInteger initialized) throws IOException {
        JsonNode request = objectMapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        if ("initialize".equals(method)) {
            int number = initialized.incrementAndGet();
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-" + number);
            respond(exchange, 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asInt()
                            + ",\"result\":{\"protocolVersion\":\"2025-06-18\","
                            + "\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
            return;
        }
        if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if ("session-1".equals(session)) {
            respond(exchange, 404,
                    "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asInt()
                            + ",\"error\":{\"code\":-32600,\"message\":\"Session not found\"}}");
            return;
        }
        respond(exchange, 200,
                "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asInt()
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
