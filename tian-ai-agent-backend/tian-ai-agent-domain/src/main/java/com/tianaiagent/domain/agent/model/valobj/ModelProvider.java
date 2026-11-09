package com.tianaiagent.domain.agent.model.valobj;

import java.net.URI;

public record ModelProvider(String id, Protocol protocol, String baseUrl, String apiKey, String model,
                            int priority, boolean enabled, String workspaceId, boolean streamUsage,
                            String reasoningEffort) {
    public enum Protocol { OPENAI_COMPATIBLE, ANTHROPIC, DASHSCOPE }

    public void validate() {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,48}") || protocol == null) {
            throw new IllegalArgumentException("提供商 ID 或接口协议无效");
        }
        if (baseUrl == null || baseUrl.length() > 500 || model == null || model.isBlank()
                || model.length() > 160 || apiKey == null || apiKey.isBlank()
                || apiKey.length() > 4096 || !apiKey.matches("[!-~]+")
                || (workspaceId != null && !workspaceId.matches("[A-Za-z0-9_-]{1,160}"))
                || (reasoningEffort != null && !reasoningEffort.matches("[A-Za-z0-9_-]{1,30}"))) {
            throw new IllegalArgumentException("模型名称、API Key、地址或优先级无效");
        }
        URI uri = URI.create(baseUrl);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Base URL 必须是有效的 HTTP 或 HTTPS API 地址");
        }
    }

    public ModelProvider withApiKey(String key) {
        return new ModelProvider(id, protocol, baseUrl, key, model, priority, enabled, workspaceId, streamUsage, reasoningEffort);
    }

    @Override
    public String toString() { return "ModelProvider[id=" + id + ", protocol=" + protocol + ", model=" + model + "]"; }
}
