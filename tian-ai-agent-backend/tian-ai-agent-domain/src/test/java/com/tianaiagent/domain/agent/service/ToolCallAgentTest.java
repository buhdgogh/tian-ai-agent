package com.tianaiagent.domain.agent.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallAgentTest {
    private final ThreadPoolTaskExecutor executor = createExecutor();

    @AfterEach
    void close() {
        executor.shutdown();
    }

    @Test
    void executesParallelSafeToolCallsConcurrentlyAndKeepsResponseOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ToolCallback first = callback("firstRead", "first", bothStarted, active, maxActive);
        ToolCallback second = callback("secondRead", "second", bothStarted, active, maxActive);
        ToolCallAgent agent = new ToolCallAgent(
                new ToolCallback[]{first, second}, executor, Set.of("firstRead", "secondRead"));
        discover(agent, "firstRead secondRead");

        AssistantMessage message = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "firstRead", "{}"),
                new AssistantMessage.ToolCall("call-2", "function", "secondRead", "{}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(message))));

        agent.act();

        assertEquals(2, maxActive.get());
        var toolResponse = (org.springframework.ai.chat.messages.ToolResponseMessage)
                agent.getMessageList().getLast();
        assertEquals(List.of("firstRead", "secondRead"),
                toolResponse.getResponses().stream().map(response -> response.name()).toList());
    }

    @Test
    void buildsDependencyGraphResolvesJsonPointerAndWaitsForUpstream() {
        AtomicReference<String> downstreamArguments = new AtomicReference<>();
        ToolCallback source = simpleCallback("source", ignored -> "{\"data\":{\"chunkId\":\"chunk-42\"}}");
        ToolCallback downstream = simpleCallback("downstream", arguments -> {
            downstreamArguments.set(arguments);
            return "resolved";
        });
        ToolCallAgent agent = new ToolCallAgent(
                new ToolCallback[]{source, downstream}, executor, Set.of("source", "downstream"));
        discover(agent, "source downstream");

        AssistantMessage message = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "source", "{}"),
                new AssistantMessage.ToolCall("call-2", "function", "downstream",
                        "{\"chunkId\":\"{{tool:0#/data/chunkId}}\"}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(message))));

        agent.act();

        assertEquals("{\"chunkId\":\"chunk-42\"}", downstreamArguments.get());
        var toolResponse = (org.springframework.ai.chat.messages.ToolResponseMessage)
                agent.getMessageList().getLast();
        assertEquals(List.of("source", "downstream"),
                toolResponse.getResponses().stream().map(response -> response.name()).toList());
    }

    @Test
    void rejectsCyclicDependenciesWithoutExecutingTools() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback first = simpleCallback("first", arguments -> {
            executions.incrementAndGet();
            return "first";
        });
        ToolCallback second = simpleCallback("second", arguments -> {
            executions.incrementAndGet();
            return "second";
        });
        ToolCallAgent agent = new ToolCallAgent(
                new ToolCallback[]{first, second}, executor, Set.of("first", "second"));
        discover(agent, "first second");
        AssistantMessage message = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "first",
                        "{\"value\":\"{{tool:1}}\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "second",
                        "{\"value\":\"{{tool:0}}\"}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(message))));

        agent.act();

        assertEquals(0, executions.get());
        var toolResponse = (org.springframework.ai.chat.messages.ToolResponseMessage)
                agent.getMessageList().getLast();
        assertTrue(toolResponse.getResponses().stream()
                .allMatch(response -> response.responseData().contains("循环依赖")));
    }

    @Test
    void skipsDependentToolWhenUpstreamExecutionFails() {
        AtomicInteger downstreamExecutions = new AtomicInteger();
        ToolCallback source = simpleCallback("source", ignored -> {
            throw new IllegalStateException("source unavailable");
        });
        ToolCallback downstream = simpleCallback("downstream", arguments -> {
            downstreamExecutions.incrementAndGet();
            return "should-not-run";
        });
        ToolCallAgent agent = new ToolCallAgent(
                new ToolCallback[]{source, downstream}, executor, Set.of("source", "downstream"));
        discover(agent, "source downstream");
        AssistantMessage message = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "source", "{}"),
                new AssistantMessage.ToolCall("call-2", "function", "downstream",
                        "{\"value\":\"{{tool:0}}\"}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(message))));

        agent.act();

        assertEquals(0, downstreamExecutions.get());
        var toolResponse = (org.springframework.ai.chat.messages.ToolResponseMessage)
                agent.getMessageList().getLast();
        assertTrue(toolResponse.getResponses().get(1).responseData().contains("dependency failed"));
    }

    @Test
    void reportsMissingJsonPointerWithoutInvokingDependentTool() {
        AtomicInteger downstreamExecutions = new AtomicInteger();
        ToolCallback source = simpleCallback("source", ignored -> "{\"data\":{}}");
        ToolCallback downstream = simpleCallback("downstream", arguments -> {
            downstreamExecutions.incrementAndGet();
            return "should-not-run";
        });
        ToolCallAgent agent = new ToolCallAgent(
                new ToolCallback[]{source, downstream}, executor, Set.of("source", "downstream"));
        discover(agent, "source downstream");
        AssistantMessage message = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "source", "{}"),
                new AssistantMessage.ToolCall("call-2", "function", "downstream",
                        "{\"value\":\"{{tool:0#/data/missing}}\"}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(message))));

        agent.act();

        assertEquals(0, downstreamExecutions.get());
        var toolResponse = (org.springframework.ai.chat.messages.ToolResponseMessage)
                agent.getMessageList().getLast();
        assertTrue(toolResponse.getResponses().get(1).responseData().contains("不存在路径"));
    }

    @Test
    void exposesOnlyFiveMatchesAndReplacesThemOnTheNextSearch() {
        ToolCallback[] candidates = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> simpleCallback("weather_" + index, ignored -> "ok"))
                .toArray(ToolCallback[]::new);
        var search = new ToolSearchSession(candidates);
        var otherRun = new ToolSearchSession(candidates);
        assertEquals(1, search.callbacks().size());
        assertEquals(5, search.search("weather").size());
        assertEquals(6, search.callbacks().size());
        assertEquals(1, otherRun.callbacks().size());
        assertTrue(search.search("unrelated-query").isEmpty());
        assertEquals(1, search.callbacks().size());
    }

    @Test
    void rejectsCallsToToolsThatHaveNotBeenDiscovered() {
        AtomicInteger calls = new AtomicInteger();
        var agent = new ToolCallAgent(new ToolCallback[]{
                simpleCallback("hiddenTool", ignored -> String.valueOf(calls.incrementAndGet()))
        }, executor, Set.of());
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(
                new AssistantMessage("", Map.of(), List.of(
                        new AssistantMessage.ToolCall("hidden", "function", "hiddenTool", "{}")))))));

        agent.act();

        assertEquals(0, calls.get());
    }

    private static void discover(ToolCallAgent agent, String query) {
        var search = new AssistantMessage.ToolCall("discovery", "function", "toolSearchTool",
                "{\"query\":\"" + query + "\"}");
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(
                new AssistantMessage("", Map.of(), List.of(search))))));
        agent.act();
        agent.getMessageList().clear();
    }

    private static ToolCallback callback(String name, String result, CountDownLatch bothStarted,
                                         AtomicInteger active, AtomicInteger maxActive) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name).description(name).inputSchema("{}") .build();
            private final ToolMetadata metadata = ToolMetadata.builder().build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return metadata;
            }

            @Override
            public String call(String arguments) {
                return execute();
            }

            @Override
            public String call(String arguments, org.springframework.ai.chat.model.ToolContext context) {
                return execute();
            }

            private String execute() {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
                    return result;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
    }

    private static ToolCallback simpleCallback(String name,
                                               java.util.function.Function<String, String> action) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name).description(name).inputSchema("{}") .build();
            private final ToolMetadata metadata = ToolMetadata.builder().build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return metadata; }
            @Override public String call(String arguments) { return action.apply(arguments); }
            @Override public String call(String arguments,
                                         org.springframework.ai.chat.model.ToolContext context) {
                return action.apply(arguments);
            }
        };
    }

    private static ThreadPoolTaskExecutor createExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.initialize();
        return executor;
    }
}
