package com.tianaiagent.modelrouting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("agent.model-routing")
public record ModelRoutingProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("30s") Duration cooldown,
        List<Provider> providers) {

    public enum Protocol { DASHSCOPE, OPENAI_COMPATIBLE }

    public record Provider(
            String id,
            Protocol protocol,
            String baseUrl,
            @DefaultValue("/v1/chat/completions") String completionsPath,
            String apiKey,
            String model,
            String reasoningEffort,
            @DefaultValue("0") int priority,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean supportsTools,
            @DefaultValue("true") boolean supportsStreaming) {

        @Override
        public String toString() {
            return "Provider[id=" + id + ", protocol=" + protocol + ", priority=" + priority + "]";
        }
    }
}
