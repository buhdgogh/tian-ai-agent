package com.tianaiagent.domain.agent.adapter.port;

import java.io.IOException;

public interface StreamOutput {
    void send(String step) throws IOException;

    void complete() throws IOException;

    void fail(Throwable error);
}
