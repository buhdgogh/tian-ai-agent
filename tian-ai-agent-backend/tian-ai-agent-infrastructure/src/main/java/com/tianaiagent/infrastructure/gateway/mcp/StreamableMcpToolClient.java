package com.tianaiagent.infrastructure.gateway.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.port.McpToolClient;
import com.tianaiagent.domain.agent.model.valobj.McpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StreamableMcpToolClient implements McpToolClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final int MAX_RESULT_CHARS = 20_000;
    private final ObjectMapper mapper;
    private final JacksonMcpJsonMapper mcpMapper;

    public StreamableMcpToolClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.mcpMapper = new JacksonMcpJsonMapper(mapper);
    }

    @Override
    public List<McpServer.Tool> discover(String url, String token) {
        try (var client = connect(url, token)) {
            var tools = new LinkedHashMap<String, McpServer.Tool>();
            String cursor = null;
            for (int page = 0; page < 4; page++) {
                var result = cursor == null ? client.listTools() : client.listTools(cursor);
                for (var tool : result.tools()) {
                    if (tool.name() == null || !tool.name().matches("[A-Za-z0-9_.-]{1,128}")
                            || tool.inputSchema() == null || !"object".equals(tool.inputSchema().type())) continue;
                    String schema = mapper.writeValueAsString(tool.inputSchema());
                    if (schema.length() > 12_000) continue;
                    String description = tool.description() == null ? "" : limit(tool.description(), 500);
                    if (token != null && (tool.name().contains(token) || schema.contains(token)
                            || description.contains(token))) continue;
                    tools.putIfAbsent(tool.name(), new McpServer.Tool(tool.name(), description, schema));
                    if (tools.size() == 100) return List.copyOf(tools.values());
                }
                cursor = result.nextCursor();
                if (cursor == null || cursor.isEmpty()) break;
            }
            return List.copyOf(tools.values());
        } catch (RuntimeException | JsonProcessingException e) {
            throw new Unavailable();
        }
    }

    @Override
    public String call(String url, String token, String name, String arguments) {
        try {
            var json = mapper.readTree(arguments == null ? "{}" : arguments);
            if (json == null || !json.isObject()) return "{\"ok\":false,\"errorCode\":\"MCP_INVALID_ARGUMENTS\"}";
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"errorCode\":\"MCP_INVALID_ARGUMENTS\"}";
        }
        try (var client = connect(url, token)) {
            var result = client.callTool(new McpSchema.CallToolRequest(mcpMapper, name, arguments == null ? "{}" : arguments));
            if (Boolean.TRUE.equals(result.isError())) return "{\"ok\":false,\"errorCode\":\"MCP_TOOL_ERROR\"}";
            if (result.structuredContent() != null) {
                return encodeOutput(mapper.writeValueAsString(result.structuredContent()), token);
            }
            var text = new StringBuilder();
            for (var content : result.content()) {
                if (content instanceof McpSchema.TextContent item && item.text() != null) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(limit(item.text(), Math.max(0, MAX_RESULT_CHARS - text.length())));
                    if (text.length() >= MAX_RESULT_CHARS) break;
                }
            }
            return encodeOutput(text.toString(), token);
        } catch (RuntimeException | JsonProcessingException e) {
            throw new Unavailable();
        }
    }

    private String encodeOutput(String text, String token) throws JsonProcessingException {
        if (token != null) text = text.replace(token, "[已隐藏]");
        return mapper.writeValueAsString(Map.of("ok", true, "output", limit(text, MAX_RESULT_CHARS)));
    }

    private McpSyncClient connect(String url, String token) {
        URI uri = URI.create(url);
        var transport = HttpClientStreamableHttpTransport.builder(uri.getScheme() + "://" + uri.getRawAuthority())
                .endpoint(uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                .clientBuilder(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER))
                .customizeRequest(request -> {
                    request.timeout(TIMEOUT);
                    if (token != null) request.header("Authorization", "Bearer " + token);
                })
                .jsonMapper(mcpMapper)
                .openConnectionOnStartup(false)
                .resumableStreams(false)
                .build();
        var client = McpClient.sync(transport).requestTimeout(TIMEOUT).initializationTimeout(TIMEOUT)
                .enableCallToolSchemaCaching(false).loggingConsumer(message -> {}).build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    private static String limit(String value, int maxChars) {
        int end = Math.min(value.length(), maxChars);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
