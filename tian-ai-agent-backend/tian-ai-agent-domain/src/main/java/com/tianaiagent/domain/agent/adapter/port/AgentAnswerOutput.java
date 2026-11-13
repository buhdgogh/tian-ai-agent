package com.tianaiagent.domain.agent.adapter.port;

import java.util.List;

public interface AgentAnswerOutput {
    default void thinking(int step) {}
    void delta(String text);
    void tools(List<String> names);
}
