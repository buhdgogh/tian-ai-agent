package com.tianaiagent.infrastructure.tool;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolTest
{
    @Test
    void reportsMissingCredentialWithoutNetworkCall()
    {
        String result = new WebSearchTool("").searchWeb("高血压指南 最新版本");
        assertThat(result).contains("SEARCH_API_KEY", "\"ok\":false");
    }

    @Test
    void sendsBearerHeaderAndReportsUpstreamRejection() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authorization = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        server.createContext("/search", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            byte[] body = "{\"error\":\"search-test-key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
            var key = new AtomicReference<>("'search-test-key'");
            var tool = new WebSearchTool(key::get, endpoint);
            String result = tool.searchWeb("中文搜索");
            assertThat(authorization.get()).isEqualTo("Bearer search-test-key");
            assertThat(query.get()).contains("engine=baidu", "q=中文搜索").doesNotContain("api_key", "search-test-key");
            assertThat(result).contains("SEARCH_API_HTTP_401", "\"ok\":false").doesNotContain("search-test-key");
            key.set("next-account-key");
            tool.searchWeb("中文搜索");
            assertThat(authorization.get()).isEqualTo("Bearer next-account-key");
        } finally {
            server.stop(0);
        }
    }
}
