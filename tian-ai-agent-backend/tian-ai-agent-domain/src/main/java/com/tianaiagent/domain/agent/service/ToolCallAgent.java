package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.adapter.port.AgentAnswerOutput;
import com.tianaiagent.domain.agent.model.valobj.AgentState;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.tianaiagent.types.exception.ModelUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
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
    private final ToolSearchSession toolSearch;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 同一轮多个无副作用工具的并行调度器
    private final AsyncTaskExecutor parallelToolCallExecutor;
    private final Set<String> parallelSafeTools;

    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
    private String skillInstructions = "";
    private boolean stopOnTerminate = true;

    public ToolCallAgent(ToolCallback[] availableTools,
                         AsyncTaskExecutor parallelToolCallExecutor,
                         Set<String> parallelSafeTools) {
        super();
        this.toolSearch = new ToolSearchSession(availableTools);
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.parallelToolCallExecutor = parallelToolCallExecutor;
        this.parallelSafeTools = Set.copyOf(parallelSafeTools);
    }

    private ToolCallingChatOptions chatOptions() {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(toolSearch.callbacks())
                .internalToolExecutionEnabled(false)
                .build();
    }

    public String runFinalAnswer(String userPrompt) {
        stopOnTerminate = false;
        run(userPrompt, this::step, true);
        return getMessageList().reversed().stream()
                .takeWhile(message -> !(message instanceof UserMessage))
                .filter(AssistantMessage.class::isInstance)
                .map(Message::getText)
                .filter(StrUtil::isNotBlank)
                .findFirst().orElse("");
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
        try {
            return acceptResponse(chatRequest().call().chatResponse());
        } catch (ModelUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            shouldActFlag = false;
            return "❌ 错误：" + e.getMessage();
        }
    }

    public void runWithDeltas(String userPrompt, AgentAnswerOutput output) {
        run(userPrompt, () -> {
            output.thinking(getCurrentStep());
            var text = new StringBuilder();
            var calls = new LinkedHashMap<String, AssistantMessage.ToolCall>();
            var metadata = new LinkedHashMap<String, Object>();
            var run = AgentRunContext.current();
            var chunks = chatRequest().stream().chatResponse();
            if (run != null) {
                chunks = chunks.contextWrite(context -> context.put(AgentRunContext.REACTOR_KEY, run));
            }
            chunks.doOnNext(chunk -> {
                if (chunk.getResult() == null) return;
                var message = chunk.getResult().getOutput();
                metadata.putAll(message.getMetadata());
                // Spring AI 的厂商适配器已组装同一次工具调用的参数分片。
                message.getToolCalls().forEach(call -> calls.put(call.id(), call));
                String delta = message.getText();
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    output.delta(delta);
                }
            }).blockLast();
            if (text.toString().isBlank() && calls.isEmpty()) {
                throw new IllegalStateException("模型未返回回答或工具调用");
            }
            var message = new AssistantMessage(text.toString(), metadata, List.copyOf(calls.values()));
            acceptResponse(new ChatResponse(List.of(new Generation(message))));
            if (!shouldAct()) return "";
            output.tools(calls.values().stream().map(AssistantMessage.ToolCall::name).toList());
            return act();
        }, true);
    }

    private ChatClient.ChatClientRequestSpec chatRequest() {
        // 行动规则属于系统提示，会话历史只记录实际的对话和工具交互。
        String systemPrompt = getSystemPrompt();
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            systemPrompt += "\n\n" + getNextStepPrompt();
        }
        systemPrompt += "\n\n" + ToolSearchSession.INSTRUCTIONS;
        List<Message> messageList = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) messageList.add(new SystemMessage(systemPrompt));
        messageList.addAll(this.getMessageList());
        if (!skillInstructions.isEmpty()) {
            // 只修改发给模型的本轮用户消息，历史和工具上下文继续使用原始消息。
            for (int i = messageList.size() - 1; i >= 0; i--) {
                if (messageList.get(i) instanceof UserMessage user) {
                    messageList.set(i, new UserMessage(AgentPresetService.withSkillInstructions(user.getText(), skillInstructions)));
                    break;
                }
            }
        }
        Prompt prompt = new Prompt(messageList, chatOptions());
        return getChatClient().prompt(prompt);
    }

    private String acceptResponse(ChatResponse chatResponse) {
            // 记录响应，用于等下 Act
            this.toolCallChatResponse = chatResponse;
            // 3、解析工具调用结果，获取要调用的工具
            // 助手消息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 获取要调用的工具列表
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 输出提示信息
            String thought = assistantMessage.getText();
            log.info("{}收到模型回复", getName());
            
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
                log.info("{}选择了工具 {}", getName(),
                        toolCallList.stream().map(AssistantMessage.ToolCall::name).toList());
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
                return "🤔 思考：" + thought;
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
        ToolCallingChatOptions options = chatOptions();
        Prompt prompt = new Prompt(getMessageList(), options);
        List<AssistantMessage.ToolCall> toolCalls = toolCallChatResponse.getResult().getOutput().getToolCalls();
        AssistantMessage assistantMessage = toolCallChatResponse.getResult().getOutput();
        ToolExecutionResult toolExecutionResult;
        if (toolCalls.stream().anyMatch(call -> options.getToolCallbacks().stream()
                .noneMatch(callback -> callback.getToolDefinition().name().equals(call.name())))) {
            toolExecutionResult = dependencyErrorResult(prompt, assistantMessage, "请先用 toolSearchTool 查找并加载工具");
        } else {
            toolExecutionResult = executeToolCalls(prompt, assistantMessage, toolCalls);
        }
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate") && !isFailedResult(response.responseData()));
        if (stopOnTerminate && terminateToolCalled) {
            // 任务结束，更改状态
            setState(AgentState.FINISHED);
        }
        
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "🛠️ 正在使用工具 " + response.name() + " 获取信息...")
                .collect(Collectors.joining("\n"));
        log.info(results);

        return results;
    }

    private ToolExecutionResult executeToolCalls(Prompt prompt, AssistantMessage assistantMessage,
                                                 List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls.size() < 2) return toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        try {
            ToolDependencyGraph graph = ToolDependencyGraph.from(toolCalls);
            if (graph.hasDependencies() || canExecuteInParallel(toolCalls)) {
                return executeToolCallGraph(prompt, assistantMessage, graph);
            }
            // 没有依赖引用但含有写操作、流程控制或未知工具时，保持 Spring AI 串行语义。
            return toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        } catch (IllegalArgumentException error) {
            log.warn("工具依赖图无效: {}", error.getMessage());
            return dependencyErrorResult(prompt, assistantMessage, error.getMessage());
        }
    }

    private boolean canExecuteInParallel(List<AssistantMessage.ToolCall> toolCalls) {
        return toolCalls.size() > 1
                && toolCalls.stream().allMatch(call -> parallelSafeTools.contains(call.name()));
    }

    /** 按依赖图的拓扑层执行；同层全部为只读白名单工具时并行，否则保持原顺序串行。 */
    private ToolExecutionResult executeToolCallGraph(Prompt prompt, AssistantMessage assistantMessage,
                                                     ToolDependencyGraph graph) {
        ToolContext toolContext = buildToolContext(prompt);
        List<ResolvedToolCall> resolvedCalls = assistantMessage.getToolCalls().stream()
                .map(call -> new ResolvedToolCall(call, resolveTool(prompt, call.name())))
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
        try {
            result = resolved.callback().call(resolved.call().arguments(), toolContext);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            result = "Tool execution failed: " + message;
        }
        return new ToolResponseMessage.ToolResponse(
                resolved.call().id(), resolved.call().name(), result == null ? "" : result);
    }

    private ToolCallback resolveTool(Prompt prompt, String toolName) {
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
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
        return new ToolContext(context);
    }

    private record ResolvedToolCall(AssistantMessage.ToolCall call, ToolCallback callback) {}
    private record IndexedToolResponse(int index, ToolResponseMessage.ToolResponse response) {}
}
