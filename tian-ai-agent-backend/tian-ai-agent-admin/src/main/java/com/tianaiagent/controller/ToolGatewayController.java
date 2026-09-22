package com.tianaiagent.controller;

import com.tianaiagent.toolgateway.ToolAuditRecord;
import com.tianaiagent.toolgateway.ToolGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tool-gateway")
public class ToolGatewayController {
    private final ToolGateway toolGateway;
    public ToolGatewayController(ToolGateway toolGateway) { this.toolGateway = toolGateway; }

    @GetMapping("/audits")
    public List<ToolAuditRecord> recentAudits() { return toolGateway.recentAudits(); }
}
