package com.tianaiagent.domain.agent.adapter.port;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public interface AgentTools {
    List<RegisteredTool> registeredForSpace(String spaceId, boolean medical);

    default ToolCallback[] forSpace(String spaceId, boolean medical) {
        return registeredForSpace(spaceId, medical).stream().map(RegisteredTool::callback).toArray(ToolCallback[]::new);
    }

    default ToolCallback[] forSpace(String spaceId) {
        return forSpace(spaceId, true);
    }

    record RegisteredTool(String name, String mcpHost, ToolCallback callback) {
        public static RegisteredTool builtIn(ToolCallback callback) {
            return new RegisteredTool(callback.getToolDefinition().name(), null, callback);
        }
    }
}
