package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/agent-runs")
public class AgentRunController {
    private final AgentRunAudit audit;

    public AgentRunController(AgentRunAudit audit) { this.audit = audit; }

    @GetMapping
    public List<AgentRun.Summary> recent(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return audit.recent(identity.ownerId());
    }

    @GetMapping("/{id}")
    public AgentRun detail(@PathVariable String id, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return audit.find(identity.ownerId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
