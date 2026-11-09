package com.tianaiagent.api.agent;

import java.util.List;

public record ModelProviderSettingsRequest(String version, List<Provider> providers) {
    public record Provider(String id, String protocol, String baseUrl, String apiKey, String model,
                           Integer priority, Boolean enabled, String workspaceId,
                           Boolean streamUsage, String reasoningEffort) {
        @Override public String toString() { return "Provider[id=" + id + "]"; }
    }
}
