package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.adapter.repository.AgentRunRepository;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.types.exception.ModelUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Service
public class AgentRunAudit {
    private static final Logger log = LoggerFactory.getLogger(AgentRunAudit.class);
    private static final int MAX_EVENTS = 1000;
    private final AgentRunRepository repository;
    private final ConcurrentHashMap<String, Run> active = new ConcurrentHashMap<>();

    public AgentRunAudit(AgentRunRepository repository) { this.repository = repository; }

    public Run start(String ownerId, String kind, String taskId) {
        return start(ownerId, kind, taskId, null);
    }

    public Run start(String ownerId, String kind, String taskId, String modelProviderId) {
        Run parent = AgentRunContext.current();
        if (modelProviderId == null && parent != null && ownerId.equals(parent.ownerId)) {
            modelProviderId = parent.modelProviderId;
        }
        Run run = new Run(ownerId, kind, taskId, parent == null ? null : parent.id, modelProviderId);
        active.put(run.id, run);
        return run;
    }

    public <T> T call(String owner, String kind, Supplier<T> work) {
        return call(owner, kind, null, work);
    }

    public <T> T call(String owner, String kind, String modelProviderId, Supplier<T> work) {
        Run run = start(owner, kind, null, modelProviderId);
        try {
            T value = AgentRunContext.within(run, work);
            run.finish("COMPLETED");
            return value;
        } catch (RuntimeException | Error e) {
            if (e instanceof ModelUnavailableException modelError) modelError.attachRunId(run.id());
            run.finish("FAILED");
            throw e;
        }
    }

    public <T> Flux<T> stream(String owner, String kind, Supplier<Flux<T>> work) {
        return Flux.defer(() -> {
            Run run = start(owner, kind, null);
            return Flux.defer(() -> AgentRunContext.within(run, work))
                    .contextWrite(context -> context.put(AgentRunContext.REACTOR_KEY, run))
                    .doOnComplete(() -> run.finish("COMPLETED"))
                    .doOnError(error -> run.finish("FAILED"))
                    .doFinally(signal -> {
                        if (signal == reactor.core.publisher.SignalType.CANCEL) run.finish("CANCELLED");
                    });
        });
    }

    public List<AgentRun.Summary> recent(String owner) {
        var running = active.values().stream().filter(run -> owner.equals(run.ownerId)).map(Run::summary).toList();
        var saved = repository.recent(owner, 100);
        var savedIds = new HashSet<String>();
        saved.forEach(summary -> savedIds.add(summary.id()));
        return Stream.concat(saved.stream(), running.stream().filter(summary -> !savedIds.contains(summary.id())))
                .sorted(Comparator.comparing(AgentRun.Summary::startedAt).reversed()).limit(100).toList();
    }

    public Optional<AgentRun> find(String owner, String id) {
        Run run = active.get(id);
        return (run == null ? repository.find(id) : Optional.of(run.snapshot()))
                .filter(snapshot -> owner.equals(snapshot.summary().ownerId()));
    }

    public final class Run {
        private final String id = UUID.randomUUID().toString().replace("-", "");
        private final Instant startedAt = Instant.now();
        private final long startNanos = System.nanoTime();
        private final String ownerId;
        private final String kind;
        private final String taskId;
        private final String parentRunId;
        private final String modelProviderId;
        private final List<AgentRun.Event> events = new ArrayList<>();
        private String status = "RUNNING";
        private long elapsedMs;
        private Long inputTokens;
        private Long outputTokens;
        private Long totalTokens;

        private Run(String ownerId, String kind, String taskId, String parentRunId, String modelProviderId) {
            this.ownerId = ownerId;
            this.kind = kind;
            this.taskId = taskId;
            this.parentRunId = parentRunId;
            this.modelProviderId = modelProviderId;
        }

        public String id() { return id; }
        public String ownerId() { return ownerId; }
        public String modelProviderId() { return modelProviderId; }

        public synchronized void event(AgentRun.Event event) {
            if (!"RUNNING".equals(status)) return;
            if (events.size() < MAX_EVENTS) events.add(event);
            inputTokens = add(inputTokens, event.inputTokens());
            outputTokens = add(outputTokens, event.outputTokens());
            totalTokens = add(totalTokens, event.totalTokens());
        }

        public void finish(String finalStatus) {
            AgentRun data;
            synchronized (this) {
                if (!"RUNNING".equals(status)) return;
                status = finalStatus;
                elapsedMs = elapsed();
                data = snapshot();
            }
            try {
                repository.save(data);
            } catch (RuntimeException e) {
                log.warn("Agent 审计持久化失败 runId={}", id);
            } finally {
                active.remove(id, this);
            }
        }

        private synchronized AgentRun.Summary summary() {
            return new AgentRun.Summary(id, ownerId, kind, taskId, parentRunId, status, startedAt,
                    "RUNNING".equals(status) ? elapsed() : elapsedMs, events.size(), inputTokens, outputTokens, totalTokens);
        }

        private synchronized AgentRun snapshot() { return new AgentRun(summary(), List.copyOf(events)); }

        private long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos); }

        private Long add(Long value, Integer increment) {
            if (increment == null) return value;
            return (value == null ? 0L : value) + increment;
        }
    }
}
