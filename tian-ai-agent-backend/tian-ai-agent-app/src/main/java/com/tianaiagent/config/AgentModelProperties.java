package com.tianaiagent.config;

import com.tianaiagent.domain.agent.model.valobj.ModelProvider.Protocol;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("agent.models")
record AgentModelProperties(@DefaultValue("60s") Duration timeout, @DefaultValue("30s") Duration cooldown,
                            List<Provider> providers, List<String> allowedUserOrigins) {
    AgentModelProperties {
        providers = providers == null ? List.of() : List.copyOf(providers);
        allowedUserOrigins = allowedUserOrigins == null ? List.of() : List.copyOf(allowedUserOrigins);
        if (timeout.isZero() || timeout.isNegative() || cooldown.isNegative()) {
            throw new IllegalArgumentException("模型超时必须大于零，冷却时间不能为负数");
        }
    }

    record Provider(String id, @DefaultValue("OPENAI_COMPATIBLE") Protocol protocol, String baseUrl,
                    String apiKey, String model, Integer priority, String workspaceId,
                    @DefaultValue("true") boolean streamUsage, String reasoningEffort) {
        @Override
        public String toString() {
            return "Provider[id=" + id + ", protocol=" + protocol + ", model=" + model + "]";
        }
    }
}
