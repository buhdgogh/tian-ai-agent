package com.tianaiagent.docmcp.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WikiModelClientTest {
    HttpServer server;
    ObjectMapper mapper = new ObjectMapper();
    DocMcpProperties properties = new DocMcpProperties();
    @AfterEach void stop() { if (server != null) server.stop(0); }

    WikiModelClient serve(int status, String body, AtomicReference<String> requestBody, AtomicReference<String> authorization) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        properties.getChat().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
        properties.getChat().setApiKey("test-only-secret");
        return new WikiModelClient(HttpClient.newHttpClient(), mapper, properties);
    }

    @Test void modelRequestSeparatesCredentialsFromPromptsAndParsesUsage() throws Exception {
        var body = new AtomicReference<String>(); var auth = new AtomicReference<String>();
        var client = serve(200, "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"facts\\\":[]}\"}}]},\"usage\":{\"input_tokens\":23,\"output_tokens\":9}}", body, auth);
        var result = client.generate("编译Wiki", Map.of("text", "原始资料"));
        assertEquals(23, result.inputTokens()); assertEquals(9, result.outputTokens());
        assertTrue(result.json().path("facts").isArray());
        assertEquals("Bearer test-only-secret", auth.get()); assertFalse(body.get().contains("test-only-secret"));
        assertEquals(4096, mapper.readTree(body.get()).path("parameters").path("max_tokens").asInt());
    }

    @Test void remoteErrorsAreClassifiedWithoutPersistingSensitiveResponseBodies() throws Exception {
        var client = serve(429, "private-source-or-secret", new AtomicReference<>(), new AtomicReference<>());
        var failure = assertThrows(WikiModelClient.Failure.class, () -> client.generate("编译", Map.of()));
        assertEquals("MODEL_HTTP_429", failure.code); assertTrue(failure.retryable);
        assertFalse(failure.getMessage().contains("private-source-or-secret"));
    }

    @Test void truncatedOutputCannotBecomeACompletedWikiPage() throws Exception {
        var client = serve(200, "{\"output\":{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}}", new AtomicReference<>(), new AtomicReference<>());
        var failure = assertThrows(WikiModelClient.Failure.class, () -> client.generate("编译", Map.of()));
        assertEquals("MODEL_OUTPUT_TRUNCATED", failure.code); assertFalse(failure.retryable);
    }

    @Test void missingKeyBlocksCompilation() {
        var client = new WikiModelClient(mockHttp(), mapper, properties);
        var failure = assertThrows(WikiModelClient.Failure.class, () -> client.generate("编译", Map.of()));
        assertEquals("CHAT_NOT_CONFIGURED", failure.code);
    }
    private HttpClient mockHttp() { return org.mockito.Mockito.mock(HttpClient.class); }
}
