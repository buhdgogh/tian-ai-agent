package com.tianaiagent.domain.agent.adapter.port;

import org.springframework.ai.tool.ToolCallback;

public interface AgentTools {
    ToolCallback[] forSpace(String spaceId, boolean medical);

    default ToolCallback[] forSpace(String spaceId) {
        return forSpace(spaceId, true);
    }
}
