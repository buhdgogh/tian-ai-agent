package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.agent.adapter.port.StreamOutput;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

final class SseAgentOutput implements StreamOutput {
    private final SseEmitter emitter = new SseEmitter(180_000L);
    private final AgentRunAudit.Run run;

    SseAgentOutput(AgentRunAudit.Run run) {
        this.run = run;
        emitter.onTimeout(() -> run.finish("TIMEOUT"));
        emitter.onError(error -> run.finish("FAILED"));
        emitter.onCompletion(() -> run.finish("CANCELLED"));
    }

    SseEmitter emitter() { return emitter; }

    @Override
    public void send(String step) throws IOException { emitter.send(step); }

    @Override
    public void complete() throws IOException {
        emitter.send("[DONE]");
        run.finish("COMPLETED");
        emitter.complete();
    }

    @Override
    public void fail(Throwable error) {
        run.finish("FAILED");
        emitter.completeWithError(error);
    }
}
