package com.tianaiagent.infrastructure.gateway.model;

import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.types.exception.ModelUnavailableException;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 厂商协议由 ChatModel 适配。路由只在本次调用可安全重放时切换。 */
public final class RoutedChatModel implements ChatModel {
    private final List<Group> defaults;
    private final Map<String, List<Group>> ownerGroups = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Duration cooldown;

    public RoutedChatModel(List<Target> targets, Duration timeout, Duration cooldown) {
        this.timeout = timeout;
        this.cooldown = cooldown;
        this.defaults = groupTargets(targets);
    }

    public Runnable prepareTargets(String ownerId, List<Target> targets, boolean custom) {
        if (!custom) return () -> ownerGroups.remove(ownerId);
        List<Group> next = groupTargets(targets);
        return () -> ownerGroups.put(ownerId, next);
    }

    private static List<Group> groupTargets(List<Target> targets) {
        var ordered = new TreeMap<Integer, List<Route>>();
        targets.forEach(target -> ordered.computeIfAbsent(target.priority(), ignored -> new ArrayList<>())
                .add(new Route(target)));
        return ordered.values().stream().map(routes -> new Group(List.copyOf(routes))).toList();
    }

    public boolean hasPersonalRoutes() {
        AgentRunAudit.Run run = AgentRunContext.current();
        return run != null && ownerGroups.containsKey(run.ownerId());
    }

    @Override
    public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }

    @Override
    public ChatResponse call(Prompt prompt) {
        AgentRunAudit.Run run = AgentRunContext.current();
        List<Route> routes = orderedRoutes(run);
        for (Route route : routes) {
            if (route.cooling()) {
                new Attempt(route, run).record("SKIPPED", "COOLDOWN");
                continue;
            }
            Attempt attempt = new Attempt(route, run);
            try {
                ChatResponse response = route.target.model().call(attempt.prompt(prompt));
                if (response == null || response.getResults().isEmpty()) throw new EmptyResponse();
                attempt.observe(response);
                attempt.record("SUCCESS", null);
                return response;
            } catch (RuntimeException e) {
                Failure failure = failure(e);
                attempt.record("FAILED", failure.reason());
                if (failure.retriable()) route.cooldown(cooldown);
                if (!failure.retriable() || attempt.usedTool.get()) throw failed(failure);
            }
        }
        throw unavailable(routes.isEmpty());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        AgentRunAudit.Run parent = AgentRunContext.current();
        return Flux.deferContextual(context -> {
            AgentRunAudit.Run run = context.getOrDefault(AgentRunContext.REACTOR_KEY, parent);
            return stream(prompt, orderedRoutes(run), 0, run);
        });
    }

    private Flux<ChatResponse> stream(Prompt prompt, List<Route> routes, int index, AgentRunAudit.Run run) {
        while (index < routes.size() && routes.get(index).cooling()) {
            new Attempt(routes.get(index++), run).record("SKIPPED", "COOLDOWN");
        }
        if (index == routes.size()) return Flux.error(unavailable(routes.isEmpty()));
        Route route = routes.get(index);
        int next = index + 1;
        Attempt attempt = new Attempt(route, run);
        AtomicBoolean emitted = new AtomicBoolean();
        return Flux.defer(() -> route.target.model().stream(attempt.prompt(prompt)))
                .timeout(timeout)
                .switchIfEmpty(Flux.error(new EmptyResponse()))
                .doOnNext(response -> {
                    emitted.set(true);
                    attempt.observe(response);
                })
                .doOnComplete(() -> attempt.record("SUCCESS", null))
                .doOnCancel(() -> attempt.record("CANCELLED", null))
                .onErrorResume(error -> {
                    Failure failure = failure(error);
                    attempt.record("FAILED", failure.reason());
                    if (failure.retriable()) route.cooldown(cooldown);
                    if (emitted.get() || attempt.usedTool.get() || !failure.retriable()) {
                        return Flux.error(failed(failure));
                    }
                    return stream(prompt, routes, next, run);
                });
    }

    private List<Route> orderedRoutes(AgentRunAudit.Run run) {
        List<Group> groups = run == null ? defaults : ownerGroups.getOrDefault(run.ownerId(), defaults);
        List<Route> result = new ArrayList<>();
        for (Group group : groups) {
            List<Route> routes = group.routes;
            int start = Math.floorMod(group.cursor.getAndIncrement(), routes.size());
            for (int i = 0; i < routes.size(); i++) result.add(routes.get((start + i) % routes.size()));
        }
        if (run != null && run.modelProviderId() != null) {
            for (int i = 1; i < result.size(); i++) {
                if (run.modelProviderId().equals(result.get(i).target.provider())) {
                    result.addFirst(result.remove(i));
                    break;
                }
            }
        }
        return result;
    }

    private static Failure failure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response) return httpFailure(response.getStatusCode().value());
            if (cause instanceof WebClientResponseException response) return httpFailure(response.getStatusCode().value());
            if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException) return new Failure("TIMEOUT", true);
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException) {
                return new Failure("INTERRUPTED", false);
            }
            if (cause instanceof IOException) return new Failure("NETWORK", true);
            if (cause instanceof EmptyResponse) return new Failure("EMPTY_RESPONSE", true);
        }
        return new Failure("MODEL_ERROR", false);
    }

    private static Failure httpFailure(int code) {
        return new Failure("HTTP_" + code, code == 401 || code == 402 || code == 403 || code == 404
                || code == 408 || code == 429 || code >= 500);
    }

    private static ModelUnavailableException failed(Failure failure) {
        return new ModelUnavailableException("MODEL_CALL_FAILED", "模型调用失败 (" + failure.reason() + ")，请查看运行日志。");
    }

    private ModelUnavailableException unavailable(boolean noRoutes) {
        return !noRoutes
                ? new ModelUnavailableException("MODEL_UNAVAILABLE", "当前没有可用的模型提供商，请查看运行日志。")
                : new ModelUnavailableException("MODEL_NOT_CONFIGURED", "当前没有已启用的聊天模型，请在「模型设置」中添加或启用模型。");
    }

    public record Target(String provider, String modelName, int priority, ChatModel model) {}
    private record Failure(String reason, boolean retriable) {}
    private static final class EmptyResponse extends RuntimeException {}

    private static final class Group {
        private final List<Route> routes;
        private final AtomicInteger cursor = new AtomicInteger();
        private Group(List<Route> routes) { this.routes = routes; }
    }

    private static final class Route {
        private final Target target;
        private final AtomicLong retryAt = new AtomicLong();
        private Route(Target target) { this.target = target; }
        private boolean cooling() { return retryAt.get() > System.currentTimeMillis(); }
        private void cooldown(Duration delay) {
            retryAt.accumulateAndGet(System.currentTimeMillis() + delay.toMillis(), Math::max);
        }
    }

    private static final class Attempt {
        private final Route route;
        private final AgentRunAudit.Run run;
        private final Instant start = Instant.now();
        private final long nanos = System.nanoTime();
        private final AtomicBoolean usedTool = new AtomicBoolean();
        private final AtomicBoolean recorded = new AtomicBoolean();
        private Usage usage;
        private String model;

        private Attempt(Route route, AgentRunAudit.Run run) {
            this.route = route;
            this.run = run;
            this.model = route.target.modelName();
        }

        private Prompt prompt(Prompt original) {
            if (!(original.getOptions() instanceof ToolCallingChatOptions options)) return original;
            if (options.getToolNames() != null && !options.getToolNames().isEmpty()
                    && ToolCallingChatOptions.isInternalToolExecutionEnabled(options)) usedTool.set(true);
            if (options.getToolCallbacks() == null || options.getToolCallbacks().isEmpty()) return original;
            ToolCallingChatOptions copy = options.copy();
            copy.setToolCallbacks(options.getToolCallbacks().stream().map(this::track).toList());
            return new Prompt(original.getInstructions(), copy);
        }

        private ToolCallback track(ToolCallback callback) {
            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
                @Override public ToolMetadata getToolMetadata() { return callback.getToolMetadata(); }
                @Override public String call(String input) {
                    usedTool.set(true);
                    return AgentRunContext.within(run, () -> callback.call(input));
                }
                @Override public String call(String input, ToolContext context) {
                    usedTool.set(true);
                    return AgentRunContext.within(run, () -> callback.call(input, context));
                }
            };
        }

        private synchronized void observe(ChatResponse response) {
            if (response.getMetadata() == null) return;
            String actual = response.getMetadata().getModel();
            if (actual != null && !actual.isBlank()) model = actual;
            Usage reported = response.getMetadata().getUsage();
            if (reported != null && !(reported instanceof EmptyUsage) && reported.getTotalTokens() != null
                    && reported.getTotalTokens() > 0) usage = reported;
        }

        private synchronized void record(String status, String reason) {
            if (run == null || !recorded.compareAndSet(false, true)) return;
            run.event(new AgentRun.Event("MODEL", "chat", route.target.provider(), model, status, start,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos),
                    usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(),
                    usage == null ? null : usage.getTotalTokens(), reason));
        }
    }
}
