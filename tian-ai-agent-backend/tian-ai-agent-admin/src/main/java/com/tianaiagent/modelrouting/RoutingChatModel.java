package com.tianaiagent.modelrouting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class RoutingChatModel implements ChatModel {
    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);
    private final List<Tier> tiers;
    private final Duration timeout;
    private final Duration cooldown;
    private final ToolCallingManager tools;

    RoutingChatModel(List<Route> routes, Duration timeout, Duration cooldown, ToolCallingManager tools) {
        var byPriority = new TreeMap<Integer, List<Route>>();
        routes.forEach(route -> byPriority.computeIfAbsent(route.priority, ignored -> new ArrayList<>()).add(route));
        this.tiers = byPriority.values().stream().map(Tier::new).toList();
        this.timeout = timeout;
        this.cooldown = cooldown;
        this.tools = tools;
    }

    @Override
    public ToolCallingChatOptions getDefaultOptions() {
        // ChatClient adds tool callbacks and context only when the model exposes tool-aware options.
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var options = wireOptions(prompt);
        Prompt request = new Prompt(prompt.getInstructions(), options);
        Route previous = null;
        while (true) {
            RoutedReply reply = callProvider(request, previous);
            ChatResponse response = reply.response();
            if (!response.hasToolCalls() || !autoToolExecution(prompt)) {
                return response;
            }
            // Tool calls run outside the provider attempt. Failover of the next round carries their results.
            ToolExecutionResult execution = tools.executeToolCalls(request, response);
            if (execution.returnDirect()) {
                return new ChatResponse(ToolExecutionResult.buildGenerations(execution), response.getMetadata());
            }
            previous = reply.route();
            request = new Prompt(execution.conversationHistory(), options);
        }
    }

    private RoutedReply callProvider(Prompt request, Route previous) {
        checkInterrupted();
        Iterator<Route> routes = candidates(request, false, previous).iterator();
        while (routes.hasNext()) {
            Route route = routes.next();
            if (!route.available()) continue;
            try {
                ChatResponse response = route.model.call(request);
                checkInterrupted();
                if (response == null || response.getResult() == null) throw new EmptyReplyException();
                return new RoutedReply(route, response);
            } catch (RuntimeException error) {
                RuntimeException rejected = recordFailure(route, error);
                if (rejected != null) throw rejected;
            }
        }
        throw unavailable();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ToolCallingChatOptions options = wireOptions(prompt);
        if (hasToolDefinitions(options) && autoToolExecution(prompt)) {
            // Tool callbacks are synchronous. Complete the tool loop before emitting its final response.
            return Mono.fromCallable(() -> call(prompt)).subscribeOn(Schedulers.boundedElastic()).flux();
        }
        Prompt request = new Prompt(prompt.getInstructions(), options);
        return Flux.defer(() -> streamProvider(request, candidates(request, true, null).iterator()));
    }

    private Flux<ChatResponse> streamProvider(Prompt request, Iterator<Route> candidates) {
        Route route = nextAvailable(candidates);
        if (route == null) return Flux.error(unavailable());
        boolean[] outputStarted = { false };
        return Flux.defer(() -> route.model.stream(request))
                .timeout(timeout)
                .doOnNext(response -> outputStarted[0] |= hasOutput(response))
                .concatWith(Flux.defer(() -> outputStarted[0] ? Flux.empty() : Flux.error(new EmptyReplyException())))
                .onErrorResume(error -> {
                    RuntimeException rejected = recordFailure(route, error);
                    if (rejected != null) return Flux.error(rejected);
                    if (outputStarted[0]) {
                        return Flux.error(new ModelRoutingException("模型流式输出已中断"));
                    }
                    return streamProvider(request, candidates);
                });
    }

    private Route nextAvailable(Iterator<Route> candidates) {
        while (candidates.hasNext()) {
            Route route = candidates.next();
            if (route.available()) return route;
        }
        return null;
    }

    private boolean hasOutput(ChatResponse response) {
        return response.getResults().stream().anyMatch(generation -> {
            AssistantMessage message = generation.getOutput();
            String reason = generation.getMetadata().getFinishReason();
            return message.hasToolCalls() || !message.getMedia().isEmpty()
                    || (message.getText() != null && !message.getText().isEmpty())
                    || "content_filter".equalsIgnoreCase(reason) || "refusal".equalsIgnoreCase(reason);
        });
    }

    private Stream<Route> candidates(Prompt request, boolean streaming, Route previous) {
        var options = (ToolCallingChatOptions) request.getOptions();
        boolean toolRequired = hasToolDefinitions(options) || request.getInstructions().stream().anyMatch(message ->
                message instanceof ToolResponseMessage || message instanceof AssistantMessage assistant && assistant.hasToolCalls());
        Predicate<Route> eligible = route -> route.available()
                && (!toolRequired || route.tools) && (!streaming || route.streaming);
        Stream<Route> alternatives = tiers.stream().flatMap(tier -> tier.ordered(eligible, previous));
        return previous != null && eligible.test(previous) ? Stream.concat(Stream.of(previous), alternatives) : alternatives;
    }

    private boolean hasToolDefinitions(ToolCallingChatOptions options) {
        return !options.getToolCallbacks().isEmpty() || !options.getToolNames().isEmpty();
    }

    private boolean autoToolExecution(Prompt prompt) {
        return !(prompt.getOptions() instanceof ToolCallingChatOptions original)
                || !Boolean.FALSE.equals(original.getInternalToolExecutionEnabled());
    }

    private ToolCallingChatOptions wireOptions(Prompt prompt) {
        var builder = ToolCallingChatOptions.builder().internalToolExecutionEnabled(false);
        var options = prompt.getOptions();
        if (options != null) {
            // Model IDs are provider-specific. Keep portable options and resolve the model from each route.
            builder.temperature(options.getTemperature()).topP(options.getTopP()).topK(options.getTopK())
                    .maxTokens(options.getMaxTokens()).frequencyPenalty(options.getFrequencyPenalty())
                    .presencePenalty(options.getPresencePenalty()).stopSequences(options.getStopSequences());
        }
        if (options instanceof ToolCallingChatOptions toolOptions) {
            builder.toolCallbacks(toolOptions.getToolCallbacks()).toolNames(toolOptions.getToolNames())
                    .toolContext(toolOptions.getToolContext());
        }
        return builder.build();
    }

    private RuntimeException recordFailure(Route route, Throwable error) {
        if (Thread.currentThread().isInterrupted()) return new CancellationException();
        int status = 0;
        boolean unavailable = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException || Exceptions.isCancel(cause)) return new CancellationException();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new CancellationException();
            }
            if (cause instanceof RestClientResponseException response) status = response.getStatusCode().value();
            if (cause instanceof WebClientResponseException response) status = response.getStatusCode().value();
            unavailable |= cause instanceof IOException || cause instanceof TimeoutException
                    || cause instanceof ResourceAccessException || cause instanceof WebClientRequestException
                    || cause instanceof TransientAiException || cause instanceof EmptyReplyException;
        }
        if (status != 0) {
            unavailable = switch (status) {
                case 401, 402, 403, 404, 408, 429 -> true;
                default -> status >= 500 && status < 600;
            };
        }
        String category = status == 0 ? error.getClass().getSimpleName() : "HTTP_" + status;
        if (!unavailable) {
            log.warn("Model request rejected: provider={}, category={}", route.id, category);
            return new ModelRoutingException("模型请求失败（" + category + "）");
        }
        Instant blockedUntil = Instant.now().plus(cooldown);
        route.blockedUntil.accumulateAndGet(blockedUntil, (old, next) -> old.isAfter(next) ? old : next);
        log.warn("Model provider unavailable: provider={}, category={}", route.id, category);
        return null;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static ModelRoutingException unavailable() {
        return new ModelRoutingException("暂无可用的模型服务，请稍后重试");
    }

    static final class Route {
        private final String id;
        private final int priority;
        private final boolean tools;
        private final boolean streaming;
        private final ChatModel model;
        private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.MIN);

        Route(String id, int priority, boolean tools, boolean streaming, ChatModel model) {
            this.id = id;
            this.priority = priority;
            this.tools = tools;
            this.streaming = streaming;
            this.model = model;
        }

        boolean available() { return !blockedUntil.get().isAfter(Instant.now()); }
    }

    private static final class Tier {
        private final List<Route> routes;
        private final AtomicInteger cursor = new AtomicInteger();

        private Tier(List<Route> routes) { this.routes = List.copyOf(routes); }

        private Stream<Route> ordered(Predicate<Route> eligible, Route previous) {
            List<Route> available = routes.stream().filter(route -> route != previous).filter(eligible).toList();
            if (available.size() < 2) return available.stream();
            int offset = Math.floorMod(cursor.getAndIncrement(), available.size());
            return Stream.concat(available.subList(offset, available.size()).stream(), available.subList(0, offset).stream());
        }
    }

    private record RoutedReply(Route route, ChatResponse response) {}
    private static final class EmptyReplyException extends RuntimeException {}
    private static final class ModelRoutingException extends RuntimeException {
        private ModelRoutingException(String message) { super(message); }
    }
}
