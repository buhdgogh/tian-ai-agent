package com.tianaiagent.domain.agent.model.valobj;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record ModelProvider(String id, Protocol protocol, String baseUrl, String apiKey, String model,
                            int priority, boolean enabled, String workspaceId, boolean streamUsage,
                            String reasoningEffort) {
    public enum Protocol { OPENAI_COMPATIBLE, ANTHROPIC, DASHSCOPE, OPENAI_EMBEDDING, OPENCODE_FREE }

    public static final String OPENCODE_FREE_ID = "opencode-free";
    public static final String OPENCODE_BASE_URL = "https://opencode.ai/inference/openai/v1";
    public static final List<String> OPENCODE_FREE_MODELS = List.of(
            "deepseek-v4.1-flash-free", "glm-5.3-flash-free", "mimo-v2.6-flash-free", "nemotron-3.5-lightning-free");

    public static final String OPENCODE_FREE_ROUTE_PREFIX = "opencode-free:";

    public static ModelProvider openCodeFree(String apiKey) {
        return new ModelProvider(OPENCODE_FREE_ID, Protocol.OPENCODE_FREE, OPENCODE_BASE_URL, apiKey, OPENCODE_FREE_ID,
                0, false, null, false, null);
    }

    private static final Pattern BEIJING_WORKSPACE_EMBEDDING = Pattern.compile(
            "(?i:https://([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.cn-beijing\\.maas\\.aliyuncs\\.com))"
                    + "(?::443)?/(api/v1|compatible-mode/v1)/?");

    public ModelProvider {
        if (protocol == Protocol.OPENAI_EMBEDDING && baseUrl != null) {
            var workspace = BEIJING_WORKSPACE_EMBEDDING.matcher(baseUrl);
            if (workspace.matches() && "api/v1".equals(workspace.group(2))) {
                baseUrl = "https://" + workspace.group(1).toLowerCase(Locale.ROOT) + "/compatible-mode/v1";
            }
        }
    }

    public boolean usesBeijingWorkspaceEmbedding() {
        return protocol == Protocol.OPENAI_EMBEDDING && baseUrl != null
                && BEIJING_WORKSPACE_EMBEDDING.matcher(baseUrl).matches();
    }

    public void validate() {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,48}") || protocol == null) {
            throw new IllegalArgumentException("提供商 ID 或接口协议无效");
        }
        if (protocol == Protocol.OPENCODE_FREE && !equals(openCodeFree(apiKey))) {
            throw new IllegalArgumentException("OpenCode 免费模型使用固定的提供商配置");
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
