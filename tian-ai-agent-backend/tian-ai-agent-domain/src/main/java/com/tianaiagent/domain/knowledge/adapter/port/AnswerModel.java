package com.tianaiagent.domain.knowledge.adapter.port;

import java.util.Optional;

public interface AnswerModel {
    Optional<String> generate(String system, String user);
}
