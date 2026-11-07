package com.tianaiagent.controller;

import com.tianaiagent.toolgateway.ToolAuditRecord;
import com.tianaiagent.toolgateway.ToolGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/tool-gateway")
public class ToolGatewayController {
    private final ToolGateway toolGateway;
    public ToolGatewayController(ToolGateway toolGateway) { this.toolGateway = toolGateway; }

    @GetMapping("/audits")
    public List<ToolAuditRecord> recentAudits(
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return toolGateway.findAudits(taskId, ownerId, chatId, status, limit, offset);
    }
}
