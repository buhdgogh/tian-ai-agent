package com.tianaiagent.domain.agent.adapter.repository;

import com.tianaiagent.domain.agent.model.entity.AgentRun;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {
    void save(AgentRun run);
    List<AgentRun.Summary> recent(String ownerId, int limit);
    Optional<AgentRun> find(String id);
}
