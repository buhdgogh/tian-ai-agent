package com.tianaiagent.trigger.http;

import com.tianaiagent.api.agent.McpServerRequest;
import com.tianaiagent.domain.agent.adapter.port.McpToolClient;
import com.tianaiagent.domain.agent.service.McpServerSettingsService;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mcp-servers")
@ConditionalOnProperty(name = "agent.persistence.mysql.enabled", havingValue = "true")
class McpServerSettingsController {
    private final McpServerSettingsService settings;

    McpServerSettingsController(McpServerSettingsService settings) { this.settings = settings; }

    @GetMapping
    List<McpServerSettingsService.View> list(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return settings.list(identity.ownerId());
    }

    @PostMapping("/{id}/discover")
    List<McpServerSettingsService.ToolView> discover(@PathVariable String id, @RequestBody McpServerRequest request,
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return settings.discover(identity.ownerId(), id, request.url(), request.token());
    }

    @PutMapping("/{id}")
    McpServerSettingsService.View save(@PathVariable String id, @RequestBody McpServerRequest request,
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return settings.save(identity.ownerId(), id, request.url(), request.token(),
                Boolean.TRUE.equals(request.enabled()), request.tools());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        if (!settings.delete(identity.ownerId(), id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(McpToolClient.Unavailable.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "无法发现 MCP 工具，请核对端点、Token 和服务状态"));
    }

    @ExceptionHandler(McpServerSettingsService.Conflict.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "MCP 配置已更新，请刷新后重试"));
    }
}
