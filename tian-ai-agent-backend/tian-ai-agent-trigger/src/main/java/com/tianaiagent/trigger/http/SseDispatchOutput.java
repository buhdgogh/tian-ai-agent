package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.LocalWorkspaceReads;
import com.tianaiagent.domain.agent.service.MedicalDispatchService;
import com.tianaiagent.domain.medical.service.MedicalIntentRouter;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.types.exception.ModelUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

final class SseDispatchOutput implements MedicalDispatchService.Output {
    private final SseEmitter emitter = new SseEmitter(180_000L);
    private final AgentRunAudit.Run run;
    private final Runnable closeWorkspace;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Future<?> worker;

    SseDispatchOutput(AgentRunAudit.Run run, Runnable closeWorkspace) {
        this.run = run;
        this.closeWorkspace = closeWorkspace;
        emitter.onTimeout(() -> cancel("TIMEOUT"));
        emitter.onError(error -> cancel("CANCELLED"));
        emitter.onCompletion(() -> cancel("CANCELLED"));
    }

    SseEmitter emitter() { return emitter; }

    void attach(Future<?> future) {
        worker = future;
        if (closed.get()) future.cancel(true);
    }

    void started() { send("run", Map.of("runId", run.id())); }
    @Override public void route(MedicalIntentRouter.RoutingDecision decision) { send("route", decision); }
    @Override public void workflow(TaskRecord task) { send("task", task); }
    @Override public void delta(String text) { send("delta", Map.of("text", text)); }
    @Override public void tools(List<String> names) { send("tools", Map.of("names", names)); }
    void workspaceRequest(LocalWorkspaceReads.Request request) { send("workspace_request", request); }

    private void send(String type, Object payload) {
        if (closed.get()) throw new CancellationException();
        try {
            emitter.send(SseEmitter.event().name(type).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalStateException e) {
            throw new CancellationException();
        }
    }

    void complete() {
        send("done", Map.of());
        finish("COMPLETED");
    }

    void fail(RuntimeException error) {
        if (closed.get()) return;
        if (error instanceof UncheckedIOException || error instanceof CancellationException) {
            finish("CANCELLED");
            return;
        }
        String message = error instanceof ModelUnavailableException
                ? error.getMessage() : "Agent 执行失败，请查看运行日志。";
        try {
            send("error", Map.of("message", message, "runId", run.id()));
        } catch (UncheckedIOException | CancellationException ignored) {
            finish("CANCELLED");
            return;
        }
        finish("FAILED");
    }

    private void finish(String status) {
        if (closed.compareAndSet(false, true)) {
            closeWorkspace.run();
            run.finish(status);
            emitter.complete();
        }
    }

    private void cancel(String status) {
        if (closed.compareAndSet(false, true)) {
            closeWorkspace.run();
            Future<?> running = worker;
            if (running != null) running.cancel(true);
            run.finish(status);
        }
    }
}
