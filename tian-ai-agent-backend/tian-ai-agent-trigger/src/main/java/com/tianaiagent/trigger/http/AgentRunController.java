package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.model.entity.ToolAuditRecord;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.ToolGateway;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class AgentRunController {
    private final AgentRunAudit audit;
    private final ToolGateway toolGateway;

    public AgentRunController(AgentRunAudit audit, ToolGateway toolGateway) {
        this.audit = audit;
        this.toolGateway = toolGateway;
    }

    @GetMapping("/agent-runs")
    public List<AgentRun.Summary> recent(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return audit.recent(identity.ownerId());
    }

    @GetMapping("/agent-runs/{id}")
    public AgentRun detail(@PathVariable String id, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return audit.find(identity.ownerId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/agent-runs/trace/{traceId}")
    public List<AgentRun.Summary> trace(@PathVariable String traceId,
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        if (!traceId.matches("[0-9a-f]{32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return audit.trace(identity.ownerId(), traceId);
    }

    @GetMapping("/tool-gateway/audits")
    public List<ToolAuditRecord> recentAudits(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        return audit.recentTools().orElseGet(toolGateway::recentAudits);
    }
}
