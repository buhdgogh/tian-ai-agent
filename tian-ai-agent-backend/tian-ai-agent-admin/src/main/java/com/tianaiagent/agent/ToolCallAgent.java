package com.tianaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.tianaiagent.agent.model.AgentState;
import com.tianaiagent.toolgateway.ToolResultStore;
import com.tianaiagent.observability.ExecutionContext;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 描述：处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {
    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 同一轮多个无副作用工具的并行调度器
    private final AsyncTaskExecutor parallelToolCallExecutor;
    private final Set<String> parallelSafeTools;

    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
    private final ChatOptions chatOptions;
    private ToolResultStore toolResultStore;
    private final String toolResultRunId = java.util.UUID.randomUUID().toString();
    private final com.tianaiagent.wiki.WikiTraversalState wikiTraversalFallback = new com.tianaiagent.wiki.WikiTraversalState();

    public ToolCallAgent(ToolCallback[] availableTools,
                         AsyncTaskExecutor parallelToolCallExecutor,
                         Set<String> parallelSafeTools) {
        super();
        this.availableTools = availableTools;
        this.parallelToolCallExecutor = parallelToolCallExecutor;
        this.parallelSafeTools = Set.copyOf(parallelSafeTools);
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
    }

    // 是否需要继续执行行动
    private boolean shouldActFlag = false;

    @Override
    public boolean shouldAct() {
        return shouldActFlag;
    }

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 思考结果
     */
    @Override
    public String think() {
        com.tianaiagent.stream.AgentOutput.progress("分析问题与证据需求", "正在判断下一步是否需要检索或调用工具（第" + getCurrentStep() + "步）");
        // 1、仅在第一步追加行动提示词，避免每步重复导致上下文膨胀
        if (StrUtil.isNotBlank(getNextStepPrompt()) && getCurrentStep() == 1) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }
        // 2、调用 AI 大模型，获取工具调用结果
        List<Message> messageList = this.getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        try {
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();
            // 记录响应，用于等下 Act
            this.toolCallChatResponse = chatResponse;
            // 3、解析工具调用结果，获取要调用的工具
            // 助手消息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 获取要调用的工具列表
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 输出提示信息
            String thought = assistantMessage.getText();
            log.info(getName() + "的思考：" + thought);
            
            // 如果不需要调用工具，设置标志为 false
            if (toolCallList.isEmpty()) {
                shouldActFlag = false;
                // 只有不调用工具时，才需要手动记录助手消息
                getMessageList().add(assistantMessage);
                // 没有工具调用表示模型已经给出本轮最终回答。
                setState(AgentState.FINISHED);
                return "🤔 思考：" + thought;
            } else {
                shouldActFlag = true;
                log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
                String toolCallInfo = toolCallList.stream()
                        .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                        .collect(Collectors.joining("\n"));
                log.info(toolCallInfo);
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
                return "🤔 思考：" + thought;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            shouldActFlag = false;
            return "❌ 错误：" + e.getMessage();
        }
    }


    /**
     * 执行工具调用并处理结果
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "🛠️ 无需调用工具";
        }

        // 调用工具
        if (chatOptions instanceof ToolCallingChatOptions options) {
            options.setToolCallbacks(java.util.Arrays.asList(availableTools));
            Map<String, Object> context = new HashMap<>();
            if (options.getToolContext() != null) context.putAll(options.getToolContext());
            context.put(ToolResultStore.SCOPE_KEY, toolResultScope());
            options.setToolContext(context);
        }
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        List<AssistantMessage.ToolCall> toolCalls = toolCallChatResponse.getResult().getOutput().getToolCalls();
        if (toolResultStore != null && toolCalls.size() > toolResultStore.batchTokens() / 256) {
            throw new IllegalStateException("Too many tool calls for the batch result token budget");
        }
        ToolExecutionResult toolExecutionResult = executeToolCalls(
                prompt, toolCallChatResponse.getResult().getOutput(), toolCalls);
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(boundToolResults(toolExecutionResult.conversationHistory()));
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(getMessageList());

        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            // 任务结束，更改状态
            setState(AgentState.FINISHED);
        }
        
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "🛠️ 正在使用工具 " + response.name() + " 获取信息...")
                .collect(Collectors.joining("\n"));
        log.info(results);

        return results;
    }

    /** 依赖图内部仍用完整结果；仅在写回模型上下文之前外置大结果。 */
    private List<Message> boundToolResults(List<Message> history) {
        if (toolResultStore == null || history.isEmpty()
                || !(history.getLast() instanceof ToolResponseMessage original)) return history;
        int remaining = toolResultStore.batchTokens();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (int index = 0; index < original.getResponses().size(); index++) {
            var response = original.getResponses().get(index);
            int reserve = (original.getResponses().size() - index - 1) * 256;
            String bounded = toolResultStore.limit(toolResultScope(), response.name(),
                    response.responseData(), remaining - reserve);
            remaining -= toolResultStore.countTokens(bounded);
            responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), bounded));
        }
        List<Message> boundedHistory = new ArrayList<>(history);
        boundedHistory.set(boundedHistory.size() - 1, new ToolResponseMessage(responses, original.getMetadata()));
        return boundedHistory;
    }

    private String toolResultScope() {
        return StrUtil.isNotBlank(getChatId()) ? "medical-agent:" + getChatId() : "run:" + toolResultRunId;
    }

    private ToolExecutionResult executeToolCalls(Prompt prompt, AssistantMessage assistantMessage,
                                                 List<AssistantMessage.ToolCall> toolCalls) {
        try {
            ToolDependencyGraph graph = ToolDependencyGraph.from(toolCalls);
            // All paths use one execution boundary so serial calls also carry toolCallId.
            // The graph runner parallelizes only layers consisting entirely of allowlisted tools.
            return executeToolCallGraph(prompt, assistantMessage, graph);
        } catch (IllegalArgumentException error) {
            log.warn("工具依赖图无效: {}", error.getMessage());
            return dependencyErrorResult(prompt, assistantMessage, error.getMessage());
        }
    }

    /** 按依赖图的拓扑层执行；同层全部为只读白名单工具时并行，否则保持原顺序串行。 */
    private ToolExecutionResult executeToolCallGraph(Prompt prompt, AssistantMessage assistantMessage,
                                                     ToolDependencyGraph graph) {
        ToolContext toolContext = buildToolContext(prompt);
        List<ResolvedToolCall> resolvedCalls = assistantMessage.getToolCalls().stream()
                .map(call -> new ResolvedToolCall(call, resolveTool(call.name())))
                .toList();
        Map<Integer, ToolResponseMessage.ToolResponse> completedResponses = new LinkedHashMap<>();
        Map<Integer, String> completedResults = new HashMap<>();
        Set<Integer> completed = new HashSet<>();
        int layer = 0;

        while (completed.size() < resolvedCalls.size()) {
            List<Integer> ready = graph.ready(completed);
            if (ready.isEmpty()) throw new IllegalArgumentException("工具依赖图无法继续执行");
            layer++;
            boolean parallel = ready.size() > 1 && ready.stream()
                    .allMatch(index -> parallelSafeTools.contains(resolvedCalls.get(index).call().name()));
            log.info("执行工具依赖图第 {} 层: calls={}, mode={}", layer,
                    ready.stream().map(index -> resolvedCalls.get(index).call().name()).toList(),
                    parallel ? "parallel" : "serial");

            if (parallel) {
                List<CompletableFuture<IndexedToolResponse>> futures = ready.stream()
                        .map(index -> CompletableFuture.supplyAsync(
                                () -> executeGraphNode(index, resolvedCalls.get(index), graph,
                                        completedResults, toolContext), parallelToolCallExecutor))
                        .toList();
                futures.stream().map(CompletableFuture::join)
                        .forEach(item -> recordResult(item.index(), item.response(),
                                completed, completedResponses, completedResults));
            } else {
                for (int index : ready) {
                    IndexedToolResponse item = executeGraphNode(index, resolvedCalls.get(index), graph,
                            completedResults, toolContext);
                    recordResult(item.index(), item.response(), completed, completedResponses, completedResults);
                }
            }
        }

        // DAG 可以改变执行时序，但写回会话历史时始终恢复模型原始调用顺序。
        List<ToolResponseMessage.ToolResponse> responses = java.util.stream.IntStream
                .range(0, resolvedCalls.size()).mapToObj(completedResponses::get).toList();
        List<Message> conversationHistory = new ArrayList<>(prompt.copy().getInstructions());
        conversationHistory.add(assistantMessage);
        conversationHistory.add(new ToolResponseMessage(responses, Map.of()));
        boolean returnDirect = resolvedCalls.stream()
                .allMatch(resolved -> resolved.callback().getToolMetadata().returnDirect());
        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(returnDirect)
                .build();
    }

    private IndexedToolResponse executeGraphNode(int index, ResolvedToolCall resolved,
                                                 ToolDependencyGraph graph,
                                                 Map<Integer, String> completedResults,
                                                 ToolContext toolContext) {
        boolean failedDependency = graph.dependenciesOf(index).stream()
                .map(completedResults::get)
                .anyMatch(this::isFailedResult);
        if (failedDependency) {
            return new IndexedToolResponse(index, new ToolResponseMessage.ToolResponse(
                    resolved.call().id(), resolved.call().name(),
                    "Tool execution skipped: dependency failed"));
        }
        try {
            String arguments = graph.resolveArguments(index, resolved.call().arguments(), completedResults);
            AssistantMessage.ToolCall resolvedCall = new AssistantMessage.ToolCall(
                    resolved.call().id(), resolved.call().type(), resolved.call().name(), arguments);
            return new IndexedToolResponse(index,
                    executeToolCall(new ResolvedToolCall(resolvedCall, resolved.callback()), toolContext));
        } catch (RuntimeException error) {
            return new IndexedToolResponse(index, new ToolResponseMessage.ToolResponse(
                    resolved.call().id(), resolved.call().name(),
                    "Tool dependency error: " + error.getMessage()));
        }
    }

    private void recordResult(int index, ToolResponseMessage.ToolResponse response,
                              Set<Integer> completed,
                              Map<Integer, ToolResponseMessage.ToolResponse> responses,
                              Map<Integer, String> results) {
        completed.add(index);
        responses.put(index, response);
        results.put(index, response.responseData());
    }

    private boolean isFailedResult(String result) {
        return result == null || result.startsWith("Tool execution failed:")
                || result.startsWith("Tool execution skipped:")
                || result.startsWith("Tool dependency error:");
    }

    private ToolExecutionResult dependencyErrorResult(Prompt prompt, AssistantMessage assistantMessage,
                                                      String message) {
        List<ToolResponseMessage.ToolResponse> responses = assistantMessage.getToolCalls().stream()
                .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        "Tool dependency error: " + message))
                .toList();
        List<Message> history = new ArrayList<>(prompt.copy().getInstructions());
        history.add(assistantMessage);
        history.add(new ToolResponseMessage(responses, Map.of()));
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
    }

    private ToolResponseMessage.ToolResponse executeToolCall(ResolvedToolCall resolved,
                                                              ToolContext toolContext) {
        String result;
        long started = System.nanoTime();
        var sink = com.tianaiagent.stream.AgentOutput.current();
        var trace = ExecutionContext.current();
        String eventId = toolResultRunId + ":" + getCurrentStep() + ":" + trace.stepId() + ":" + trace.iteration() + ":"
                + (resolved.call().id() == null ? java.util.UUID.randomUUID() : resolved.call().id());
        if (sink != null) sink.event("tool_start", Map.of("id", eventId, "title", "调用工具 " + resolved.call().name(), "tool", resolved.call().name(), "status", "running"));
        try (var ignored = ExecutionContext.open(ExecutionContext.current().tool(resolved.call().id()))) {
            result = resolved.callback().call(resolved.call().arguments(), toolContext);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            result = "Tool execution failed: " + message;
        }
        if (sink != null) sink.event("tool_end", Map.of("id", eventId, "title", "调用工具 " + resolved.call().name(), "tool", resolved.call().name(),
                "status", isFailedResult(result) ? "failed" : "completed", "detail", isFailedResult(result) ? "工具执行未完成" : "已收到工具结果", "durationMs", (System.nanoTime() - started) / 1_000_000));
        return new ToolResponseMessage.ToolResponse(
                resolved.call().id(), resolved.call().name(), result == null ? "" : result);
    }

    private ToolCallback resolveTool(String toolName) {
        return java.util.Arrays.stream(availableTools)
                .filter(callback -> callback.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No ToolCallback found for tool: " + toolName));
    }

    private ToolContext buildToolContext(Prompt prompt) {
        Map<String, Object> context = new HashMap<>();
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null) {
            context.putAll(options.getToolContext());
        }
        var shared = ExecutionContext.current().wikiTraversal();
        context.put(com.tianaiagent.wiki.WikiTraversalState.CONTEXT_KEY, shared == null ? wikiTraversalFallback : shared);
        context.put(com.tianaiagent.wiki.WikiTraversalState.CONSUMER_KEY, toolResultRunId);
        return new ToolContext(context);
    }

    private record ResolvedToolCall(AssistantMessage.ToolCall call, ToolCallback callback) {}
    private record IndexedToolResponse(int index, ToolResponseMessage.ToolResponse response) {}
}
