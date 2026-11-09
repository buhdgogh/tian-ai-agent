package com.tianaiagent.api.agent;

import java.util.List;

public record McpServerRequest(String url, String token, Boolean enabled, List<String> tools) {
    @Override
    public String toString() {
        return "McpServerRequest[enabled=" + enabled + "]";
    }
}
