package com.tianaiagent.domain.agent.model.valobj;

public record AgentPreset(String id, String name, String description, String content, boolean enabled) {
    public enum Kind {
        SKILL, PROMPT_TEMPLATE
    }
}
