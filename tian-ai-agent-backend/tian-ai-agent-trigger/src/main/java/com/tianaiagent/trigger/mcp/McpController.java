package com.tianaiagent.trigger.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(prefix = "doc-mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpController {
    private final McpToolService tools;

    public McpController(McpToolService tools) {
        this.tools = tools;
    }

    @PostMapping("${doc-mcp.endpoint:/mcp}")
    public ResponseEntity<?> handle(@RequestBody JsonNode request) {
        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        if (method.startsWith("notifications/")) return ResponseEntity.accepted().build();
        if (id == null) return ResponseEntity.accepted().build();
        Object response = switch (method) {
            case "initialize" -> success(id, Map.of(
                    "protocolVersion", "2025-06-18",
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "tian-doc-parse-index-mcp-server", "version", "0.0.1")));
            case "ping" -> success(id, Map.of());
            case "tools/list" -> success(id, Map.of("tools", tools.tools()));
            case "tools/call" -> callTool(id, request.path("params"));
            default -> error(id, -32601, "Method not found: " + method);
        };
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if ("initialize".equals(method)) builder.header("Mcp-Session-Id", UUID.randomUUID().toString());
        return builder.body(response);
    }

    private Map<String, Object> callTool(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        if (!tools.supports(name)) return error(id, -32601, "Tool not available to external callers");
        try {
            return success(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", tools.callAsText(name, params.path("arguments")))),
                    "isError", false));
        } catch (Exception e) {
            return error(id, -32603, e.getMessage());
        }
    }

    private static Map<String, Object> success(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0"); response.put("id", id); response.put("result", result); return response;
    }
    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0"); response.put("id", id);
        response.put("error", Map.of("code", code, "message", message)); return response;
    }
}
