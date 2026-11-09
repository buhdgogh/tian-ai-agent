package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.adapter.port.StreamOutput;
import com.tianaiagent.domain.agent.model.valobj.AgentState;
import com.tianaiagent.types.exception.ModelUnavailableException;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 描述：抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {
    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    // 对话 ID 和 记忆
    private String chatId;
    private ChatMemory chatMemory;
    private Consumer<List<Message>> historyWriter;

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        return run(userPrompt, this::step, false);
    }

    protected String run(String userPrompt, Supplier<String> step, boolean propagateErrors) {
        // 1、基础校验（检查当前状态是否可用以及用户是否输入了问题）
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;
        
        // 3、加载记忆（使用spring ai提供的memory包中的chatMemory类）
        int historySize = 0;
        if (StrUtil.isNotBlank(chatId) && chatMemory != null) {
            List<Message> savedMessages = chatMemory.get(chatId);
            if (savedMessages != null) {
                this.messageList = new ArrayList<>(savedMessages);
                historySize = this.messageList.size();
            }
        }
        
        // 3.1、记录消息上下文（消息综合 = 历史记忆 + 用户提示词）
        messageList.add(new UserMessage(userPrompt));
        int savedCount = persistHistory(historySize);
        // 4、实际运行过程（最大运行多步，将每一步的结果保存，最后返回结果列表）
        List<String> results = new ArrayList<>();
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 单步执行
                String stepResult = AgentRunContext.stage("ReAct " + stepNumber, step);
                savedCount = persistHistory(savedCount);
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            
            return String.join("\n", results);
        } catch (ModelUnavailableException e) {
            state = AgentState.ERROR;
            throw e;
        } catch (Exception e) {
            state = AgentState.ERROR;
            if (propagateErrors) throw e;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 6、清理资源（每轮对话完成最后需要做的内容）
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @param output 步骤输出接收方
     */
    public void runStream(String userPrompt, StreamOutput output) {
        // 1、基础校验（校验当前模型的状态 --> 状态机机制）
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }

        // 2、异步执行代理，更改状态
        this.state = AgentState.RUNNING;

        // 3、加载记忆
        int loadedHistorySize = 0;
        if (StrUtil.isNotBlank(chatId) && chatMemory != null) {
            List<Message> savedMessages = chatMemory.get(chatId);
            if (savedMessages != null) {
                this.messageList = new ArrayList<>(savedMessages);
                loadedHistorySize = this.messageList.size();
            }
        }

        // 3.1、记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        final int savedBeforeRun = persistHistory(loadedHistorySize);

        // 4、异步执行流式返回
        CompletableFuture.runAsync(AgentRunContext.wrap(() -> {
            try {
                int savedCount = savedBeforeRun;
                // 执行循环
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    String stepResult = AgentRunContext.stage("ReAct " + stepNumber, this::step);
                    savedCount = persistHistory(savedCount);
                    output.send(stepResult);
                }
                // 检查是否超出步骤限制
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    output.send("Terminated: Reached max steps (" + maxSteps + ")");
                }
                
                // 执行结束，发送完成标志
                output.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent in stream", e);
                try {
                    output.send("执行错误: " + e.getMessage());
                } catch (IOException ex) {
                    log.error("Failed to send agent error", ex);
                }
                output.fail(e);
            } finally {
                // 6、清理资源（每轮对话完成需要做什么）
                this.cleanup();
            }
        }));
    }

    /**
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    private int persistHistory(int savedCount) {
        int size = messageList.size();
        if (size <= savedCount || StrUtil.isBlank(chatId) || chatMemory == null) return size;
        var additions = new ArrayList<>(messageList.subList(savedCount, size));
        if (historyWriter == null) chatMemory.add(chatId, additions);
        else historyWriter.accept(additions);
        return size;
    }
}
