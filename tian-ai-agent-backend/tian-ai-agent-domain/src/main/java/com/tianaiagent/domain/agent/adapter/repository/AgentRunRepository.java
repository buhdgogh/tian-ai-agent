package com.tianaiagent.domain.agent.adapter.repository;

import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.model.entity.ToolAuditRecord;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {
    void save(AgentRun run);
    List<AgentRun.Summary> recent(String ownerId, int limit);
    Optional<AgentRun> find(String id);
    default Optional<List<ToolAuditRecord>> recentTools(int limit) { return Optional.empty(); }
    default List<AgentRun.Summary> trace(String ownerId, String traceId) {
        return recent(ownerId, 100).stream().filter(run -> traceId.equals(run.traceId())).toList();
    }
}
