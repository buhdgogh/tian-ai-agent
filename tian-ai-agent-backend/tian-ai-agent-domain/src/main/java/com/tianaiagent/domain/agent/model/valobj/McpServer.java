package com.tianaiagent.domain.agent.model.valobj;

import java.util.List;

public record McpServer(String id, String url, String token, boolean enabled, List<Tool> tools) {
    public McpServer {
        tools = List.copyOf(tools);
    }

    @Override
    public String toString() {
        return "McpServer[id=" + id + ", enabled=" + enabled + "]";
    }

    public record Tool(String name, String description, String inputSchema) {}
}
