package com.tianaiagent.domain.medical.service;

import com.tianaiagent.domain.medical.service.MedicalSafetyService;

import com.tianaiagent.domain.medical.service.MedicalAgent;
import com.tianaiagent.domain.agent.adapter.port.StreamOutput;
import com.tianaiagent.domain.agent.adapter.port.AgentAnswerOutput;
import com.tianaiagent.domain.agent.adapter.port.AgentTools;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.agent.service.MyLoggerAdvisor;
import com.tianaiagent.domain.conversation.adapter.port.ConversationMemoryRegistry;
import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import com.tianaiagent.domain.conversation.service.LayeredMemoryService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Service
public class MedicalAgentService {
    @Resource private AgentTools agentTools;
    @Resource private ChatModel dashscopeChatModel;

    private final ChatMemory chatMemory;
    private final ConversationMemoryRegistry memoryService;
    private final MedicalSafetyService safetyService;
    private final MedicalIntentRouter intentRouter;
    private final LayeredMemoryService layeredMemory;
    private final AsyncTaskExecutor parallelToolCallExecutor;
    private final Set<String> parallelSafeTools;

    public MedicalAgentService(
            ConversationMemoryRegistry memoryService,
            MedicalSafetyService safetyService,
            MedicalIntentRouter intentRouter,
            LayeredMemoryService layeredMemory,
            @Qualifier("parallelToolCallExecutor") AsyncTaskExecutor parallelToolCallExecutor,
            @Value("${agent.tool-gateway.parallel-safe-tools:}") List<String> parallelSafeTools) {
        this.memoryService = memoryService;
        this.safetyService = safetyService;
        this.intentRouter = intentRouter;
        this.layeredMemory = layeredMemory;
        this.parallelToolCallExecutor = parallelToolCallExecutor;
        this.parallelSafeTools = Set.copyOf(parallelSafeTools);
        // 根据配置项自动选择从哪里加载会话消息
        this.chatMemory = memoryService.chatMemory(ConversationMemoryRegistry.MEDICAL_AGENT);
    }

    public void streamChat(String message, String chatId, String spaceId, StreamOutput output) {
        streamChat(message, chatId, spaceId, output, true);
    }

    public void streamChat(String message, String chatId, String spaceId, StreamOutput output, boolean medical) {
        var risk = medical ? AgentRunContext.stage("安全检查", () -> safetyService.assess(message)) : null;
        if (risk != null && risk.emergency()) {
            completedTurnWriter(chatId).accept(message, risk.message());
            try {
                output.send(risk.message());
                output.complete();
            } catch (IOException e) {
                output.fail(e);
            }
            return;
        }
        MedicalIntentRouter.RoutingDecision route = AgentRunContext.stage("意图识别",
                () -> intentRouter.route(message, chatMemory.get(chatId), medical));
        createAgent(chatId, route, spaceId, medical).runStream(message, output);
    }

    public String doChat(String message, String chatId, MedicalIntentRouter.RoutingDecision route, String spaceId) {
        return doChat(message, chatId, route, spaceId, true);
    }

    public String doChat(String message, String chatId, MedicalIntentRouter.RoutingDecision route, String spaceId, boolean medical) {
        var risk = medical ? safetyService.assess(message) : null;
        if (risk != null && risk.emergency()) {
            completedTurnWriter(chatId).accept(message, risk.message());
            return risk.message();
        }
        return createAgent(chatId, route, spaceId, medical).run(message);
    }

    public void streamAnswer(String message, String chatId, MedicalIntentRouter.RoutingDecision route,
                             String spaceId, boolean medical, AgentAnswerOutput output) {
        var risk = medical ? safetyService.assess(message) : null;
        if (risk != null && risk.emergency()) {
            completedTurnWriter(chatId).accept(message, risk.message());
            output.delta(risk.message());
            return;
        }
        createAgent(chatId, route, spaceId, medical).runWithDeltas(message, output);
    }

    public BiConsumer<String, String> completedTurnWriter(String chatId) {
        return layeredMemory.completedTurnWriter(ConversationMemoryRegistry.MEDICAL_AGENT, chatId, chatMemory);
    }

    public Consumer<List<Message>> messageWriter(String chatId) {
        return layeredMemory.messageWriter(ConversationMemoryRegistry.MEDICAL_AGENT, chatId, chatMemory);
    }

    public String memoryContext(String chatId, String query) {
        return layeredMemory.context(ConversationMemoryRegistry.MEDICAL_AGENT, chatId, query);
    }

    /** 工作流内部执行入口：不把规划提示和中间结果写入用户会话记忆。 */
    public String executeTask(String message, String spaceId, boolean medical) {
        var risk = medical ? safetyService.assess(message) : null;
        return risk != null && risk.emergency() ? risk.message() : createAgent(null, null, spaceId, medical).run(message);
    }

    /** MedicalAgent实际上就是一个ReAct Agent */
    private MedicalAgent createAgent(String chatId, MedicalIntentRouter.RoutingDecision route, String spaceId, boolean medical) {
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel).defaultAdvisors(new MyLoggerAdvisor()).build();
        MedicalAgent agent = new MedicalAgent(agentTools.forSpace(spaceId, medical), chatClient, parallelToolCallExecutor, parallelSafeTools, medical);
        agent.setChatId(chatId);
        agent.setChatMemory(chatMemory);
        if (route != null) {
            agent.setSystemPrompt(agent.getSystemPrompt() + "\n\n" + intentRouter.routingHint(route));
            agent.setSystemPrompt(agent.getSystemPrompt() + layeredMemory.context(
                    ConversationMemoryRegistry.MEDICAL_AGENT, chatId, route.standaloneQuestion()));
        }
        if (chatId != null) agent.setHistoryWriter(messageWriter(chatId));
        return agent;
    }

    public List<ConversationSummary> listConversations() {
        return memoryService.listConversations(ConversationMemoryRegistry.MEDICAL_AGENT);
    }

    public List<Message> getChatMessages(String chatId) {
        return chatMemory.get(chatId);
    }

    public void clearChat(String chatId) {
        layeredMemory.deleteConversation(ConversationMemoryRegistry.MEDICAL_AGENT, chatId, chatMemory);
    }
}
