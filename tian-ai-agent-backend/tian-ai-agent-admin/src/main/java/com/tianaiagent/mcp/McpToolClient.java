package com.tianaiagent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.toolgateway.ToolGateway;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

/**
 * 描述：MCP 工具调用客户端（streamable-http，JSON-RPC 2.0）
 *
 * 直接通过 HTTP POST 调用 MCP 服务的 tools/call。FastMCP 的 streamable-http 要求
 * 先完成 initialize 握手，本类内部维护该握手与 Mcp-Session-Id。
 * 工具名与端点集中在 application.yml 的 knowledge-base.mcp.* 配置中。
 */
@Component
@Slf4j
public class McpToolClient {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_INITIALIZED = "notifications/initialized";
    private static final String METHOD_TOOLS_CALL = "tools/call";
    private static final String HEADER_SESSION = "Mcp-Session-Id";

    private final ObjectMapper objectMapper;  // 用来序列化/反序列化消息体中的内容
    private final AtomicInteger idCounter = new AtomicInteger(0);  // 用来唯一标识请求的id
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // 强制 HTTP/1.1，避免对明文连接发 h2c 升级被 uvicorn 拒收
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Value("${knowledge-base.mcp.endpoint}")
    private String endpoint;

    @Value("${knowledge-base.mcp.parse-tool:parse_document}")
    private String parseTool;

    @Value("${knowledge-base.mcp.parse-batch-tool:parse_documents_batch}")
    private String parseBatchTool;

    @Value("${knowledge-base.mcp.parse-status-tool:get_parse_task}")
    private String parseStatusTool;

    @Value("${knowledge-base.mcp.query-tool:query_knowledge}")
    private String queryTool;

    @Value("${knowledge-base.mcp.retrieve-tool:retrieve_knowledge}")
    private String retrieveTool;

    @Value("${knowledge-base.mcp.delete-tool:delete_document}")
    private String deleteTool;

    @Value("${knowledge-base.mcp.session-refresh-after-idle-seconds:1500}")
    private long sessionRefreshAfterIdleSeconds;

    private volatile String sessionId;
    private volatile long sessionLastUsedNanos;
    private ToolGateway toolGateway;

    @Autowired
    void setToolGateway(ToolGateway toolGateway) { this.toolGateway = toolGateway; }

    public McpToolClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String getParseTool() {
        return parseTool;
    }

    public String getParseBatchTool() {
        return parseBatchTool;
    }

    public String getParseStatusTool() {
        return parseStatusTool;
    }

    public String getQueryTool() {
        return queryTool;
    }

    public String getRetrieveTool() {
        return retrieveTool;
    }

    public String getDeleteTool() {
        return deleteTool;
    }

    /**
     * 调用 MCP 工具并返回其文本结果
     *
     * @param toolName  MCP 工具名
     * @param arguments 工具入参
     * @return 工具返回的文本（如 Markdown、构建摘要、答案）
     */
    public synchronized String call(String toolName, Map<String, Object> arguments) {
        if (toolGateway == null) return callWithRetry(toolName, arguments);
        String serialized;
        try { serialized = objectMapper.writeValueAsString(arguments); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("MCP参数无法序列化", error); }
        return toolGateway.observeOperation("mcp:" + toolName, serialized, () -> callWithRetry(toolName, arguments));
    }

    private String callWithRetry(String toolName, Map<String, Object> arguments) {
        refreshIdleSessionIfNecessary();
        try {
            return callTool(toolName, arguments);
        } catch (McpSessionNotFoundException firstFailure) {
            // 服务端已明确拒绝旧会话，因此工具尚未执行，可以安全地重新握手并重放一次。
            log.warn("MCP会话已失效，正在重新建立会话并重试一次: tool={}", toolName);
            resetSession();
            try {
                return callTool(toolName, arguments);
            } catch (RuntimeException retryFailure) {
                resetSession();
                throw retryFailure;
            }
        } catch (RuntimeException e) {
            resetSession();
            throw e;
        }
    }

    private String callTool(String toolName, Map<String, Object> arguments) {
        ensureInitialized();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        log.info("MCP工具调用 tool={}, argumentKeys={}", toolName, arguments.keySet());
        String result = extractText(post(METHOD_TOOLS_CALL, params, false));
        sessionLastUsedNanos = System.nanoTime();
        return result;
    }

    private void refreshIdleSessionIfNecessary() {
        if (sessionId == null || sessionLastUsedNanos == 0) return;
        long refreshSeconds = Math.max(1, sessionRefreshAfterIdleSeconds);
        long idleNanos = System.nanoTime() - sessionLastUsedNanos;
        if (idleNanos >= TimeUnit.SECONDS.toNanos(refreshSeconds)) {
            log.info("MCP会话达到客户端空闲刷新阈值，下一次调用将重新握手");
            resetSession();
        }
    }

    private void resetSession() {
        sessionId = null;
        sessionLastUsedNanos = 0;
    }

    /**
     * 完成 MCP streamable-http 的 initialize 握手（仅首次）
     */
    private synchronized void ensureInitialized() {
        if (sessionId != null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2025-06-18");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "tian-ai-agent-admin", "version", "1.0.0"));

        HttpResponse<String> initResp = post(METHOD_INITIALIZE, params, false);
        throwIfError(initResp);
        sessionId = initResp.headers().firstValue(HEADER_SESSION).orElse(null);

        // 通知服务端握手完成（notification，无 id）
        post(METHOD_INITIALIZED, Map.of(), true);
        sessionLastUsedNanos = System.nanoTime();
    }

    /**
     * 发送一条 JSON-RPC 请求
     *
     * @param method       JSON-RPC 方法名
     * @param params       方法参数
     * @param notification 是否为通知（通知不带 id，服务端不返回结果）
     */
    private HttpResponse<String> post(String method, Map<String, Object> params, boolean notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", JSON_RPC_VERSION);
        if (!notification) {
            body.put("id", idCounter.incrementAndGet());
        }
        body.put("method", method);
        body.put("params", params);

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(Duration.ofMinutes(10));
            if (sessionId != null) {
                builder.header(HEADER_SESSION, sessionId);
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(json));
            // 请求参数可能含患者资料和OSS预签名URL，医疗场景禁止输出消息体。
            log.info("MCP请求 method={}, endpoint={}, payloadChars={}", method, endpoint, json.length());
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            log.info("MCP响应 method={}, status={}, payloadChars={}", method,
                    response.statusCode(), response.body() == null ? 0 : response.body().length());
            return response;
        } catch (Exception e) {
            log.error("MCP 请求失败: {}", method, e);
            throw new IllegalStateException("MCP 请求失败: " + method + " - " + e.getMessage(), e);
        }
    }

    /**
     * 从 JSON-RPC 响应中提取文本内容（响应可能是纯 JSON 或 SSE 格式）
     */
    private String extractText(HttpResponse<String> response) {
        JsonNode root = parseJson(response);
        if (root.hasNonNull("error")) {
            JsonNode error = root.get("error");
            if (isSessionNotFound(error)) {
                throw new McpSessionNotFoundException("MCP会话不存在或已过期");
            }
            throw new IllegalStateException("MCP 返回错误: " + error);
        }
        JsonNode result = root.path("result");
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException("MCP 工具执行错误: " + result);
        }
        StringBuilder sb = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part.path("text").asText(null) != null) {
                    sb.append(part.path("text").asText());
                }
            }
        }
        return sb.toString();
    }

    boolean isSessionNotFound(JsonNode error) {
        if (error == null || error.isNull()) return false;
        String message = error.path("message").asText("");
        return "session not found".equalsIgnoreCase(message.trim());
    }

    private static final class McpSessionNotFoundException extends IllegalStateException {
        private McpSessionNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 若响应为 JSON-RPC 错误则抛异常
     */
    private void throwIfError(HttpResponse<String> response) {
        JsonNode root = parseJson(response);
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("MCP 返回错误: " + root.get("error"));
        }
    }

    /**
     * 解析响应体为 JSON。streamable-http 可能返回 SSE（text/event-stream），
     * 需先从 data: 行中提取 JSON 载荷。
     */
    private JsonNode parseJson(HttpResponse<String> response) {
        String json = extractSseData(response.body());
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("MCP 响应解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 若响应为 SSE（含 event:/data: 行），提取所有 data: 行的内容；否则原样返回。
     */
    private String extractSseData(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (data.length() > 0) {
                    data.append("\n");
                }
                data.append(value);
            }
        }
        return data.toString();
    }
}
