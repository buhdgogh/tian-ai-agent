package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.agent.model.entity.ToolAuditRecord;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.tianaiagent.domain.agent.service.ToolGateway;
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
    public List<ToolAuditRecord> recentAudits(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        return toolGateway.recentAudits();
    }
}
