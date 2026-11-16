package com.tianaiagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeModelConfigurationTest {
    @Test
    void requiresBothSettingsBeforeRegisteringOpenCode() {
        assertThat(targets("", "kimi-k3").defaults()).isEmpty();
        assertThat(targets("test-opencode-key", "").defaults()).isEmpty();

        var configured = targets("test-opencode-key", "kimi-k3");
        assertThat(configured.configuredDefaults()).isTrue();
        assertThat(configured.defaults()).singleElement().satisfies(target -> {
            assertThat(target.provider()).isEqualTo("opencode");
            assertThat(target.modelName()).isEqualTo("kimi-k3");
        });
    }

    private AgentModelConfiguration.ModelTargets targets(String key, String model) {
        return targets(key, model, false);
    }

    @Test
    void usesLocalOllamaByDefaultEvenWhenOpenCodeCredentialsArePresent() {
        var configured = targets("test-opencode-key", "kimi-k3", true);
        assertThat(configured.configuredDefaults()).isTrue();
        assertThat(configured.defaults()).singleElement().satisfies(target -> {
            assertThat(target.provider()).isEqualTo("ollama");
            assertThat(target.modelName()).isEqualTo("qwen3:0.6b");
        });
    }

    private AgentModelConfiguration.ModelTargets targets(String key, String model, boolean localEnabled) {
        var properties = new AgentModelProperties(Duration.ofSeconds(5), Duration.ZERO, List.of(), List.of(),
                new AgentModelProperties.OpenCode(key, model, "opencode"), new AgentModelProperties.Ollama(localEnabled, "qwen3:0.6b"));
        return new AgentModelConfiguration().modelTargets(properties, new DashScopeConnectionProperties(),
                new DashScopeChatProperties(), new StaticListableBeanFactory().getBeanProvider(ToolCallingManager.class));
    }
}
