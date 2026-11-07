package com.tianaiagent.agent;

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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path resultDirectory;
    private final ThreadPoolTaskExecutor executor = createExecutor();

    @Test
    void resolvesDependenciesUsingFullResultThenStoresBoundedResponses() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var store = new com.tianaiagent.toolgateway.ToolResultStore(
                mapper, resultDirectory.toString(), 512, 1024, 24, 1_000_000);
        AtomicReference<String> downstreamArguments = new AtomicReference<>();
        String original = "{\"padding\":\"" + "large content ".repeat(2000) + "\",\"id\":\"last-field\"}";
        ToolCallback source = simpleCallback("source", ignored -> original);
        ToolCallback downstream = simpleCallback("downstream", arguments -> {
            downstreamArguments.set(arguments);
            return "ok";
        });
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[]{source, downstream}, executor,
                Set.of("source", "downstream"));
        agent.setChatId("chat1");
        agent.setToolResultStore(store);
        AssistantMessage calls = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call1", "function", "source", "{}"),
                new AssistantMessage.ToolCall("call2", "function", "downstream", "{\"id\":\"{{tool:0#/id}}\"}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(calls))));
        agent.act();
        assertEquals("{\"id\":\"last-field\"}", downstreamArguments.get());
        var responses = ((org.springframework.ai.chat.messages.ToolResponseMessage)
                agent.getMessageList().getLast()).getResponses();
        String bounded = responses.getFirst().responseData();
        assertTrue(mapper.readTree(bounded).path("stored").asBoolean());
        assertTrue(store.countTokens(bounded) <= 512);
        String id = mapper.readTree(bounded).path("resultId").asText();
        assertTrue(store.read("medical-agent:chat1", id, 0, 256).contains(id));
    }

    @Test
    void serialToolAndRetrievalToolReceiveScopedContextAndBoundedResult() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var store = new com.tianaiagent.toolgateway.ToolResultStore(
                mapper, resultDirectory.toString(), 512, 1024, 24, 1_000_000);
        ToolCallback source = simpleCallback("source", ignored -> "medical evidence ".repeat(2000));
        ToolCallback reader = org.springframework.ai.support.ToolCallbacks.from(
                new com.tianaiagent.tools.ToolResultTool(store))[0];
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[]{source, reader}, executor, Set.of());
        agent.setChatId("chat1");
        agent.setToolResultStore(store);
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call1", "function", "source", "{}")))))));
        agent.act();
        var result = ((org.springframework.ai.chat.messages.ToolResponseMessage) agent.getMessageList().getLast())
                .getResponses().getFirst().responseData();
        String id = mapper.readTree(result).path("resultId").asText();
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call2", "function", "readToolResult",
                        "{\"resultId\":\"" + id + "\",\"offset\":0,\"maxTokens\":256}")))))));
        agent.act();
        String page = ((org.springframework.ai.chat.messages.ToolResponseMessage) agent.getMessageList().getLast())
                .getResponses().getFirst().responseData();
        assertTrue(mapper.readTree(page).path("content").asText().startsWith("medical evidence"));
        assertTrue(store.countTokens(page) <= 256);
    }

    @AfterEach
    void close() {
        executor.shutdown();
    }

    @Test
    void parallelLargeResultsRespectCombinedBatchBudget() throws Exception {
        executor.setMaxPoolSize(3);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var store = new com.tianaiagent.toolgateway.ToolResultStore(
                mapper, resultDirectory.toString(), 512, 1024, 24, 1_000_000);
        ToolCallback first = simpleCallback("first", ignored -> "first result ".repeat(2000));
        ToolCallback second = simpleCallback("second", ignored -> "second result ".repeat(2000));
        ToolCallback third = simpleCallback("third", ignored -> "third result ".repeat(2000));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[]{first, second, third}, executor,
                Set.of("first", "second", "third"));
        agent.setChatId("parallel-chat");
        agent.setToolResultStore(store);
        AssistantMessage calls = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call1", "function", "first", "{}"),
                new AssistantMessage.ToolCall("call2", "function", "second", "{}"),
                new AssistantMessage.ToolCall("call3", "function", "third", "{}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(calls))));
        agent.act();
        var responses = ((org.springframework.ai.chat.messages.ToolResponseMessage)
                agent.getMessageList().getLast()).getResponses();
        int total = 0;
        for (var response : responses) {
            assertTrue(mapper.readTree(response.responseData()).path("stored").asBoolean());
            total += store.countTokens(response.responseData());
        }
        assertTrue(total <= 1024);
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
