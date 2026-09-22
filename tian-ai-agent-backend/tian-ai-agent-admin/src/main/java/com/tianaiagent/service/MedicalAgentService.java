package com.tianaiagent.service;

import com.tianaiagent.agent.MedicalAgent;
import com.tianaiagent.chatmemory.EnterpriseMemoryService;
import com.tianaiagent.chatmemory.FileBasedChatMemory;
import com.tianaiagent.chatmemory.LayeredMemoryService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Service
public class MedicalAgentService {
    @Resource private ToolCallback[] allTools;
    @Resource private ChatModel dashscopeChatModel;

    private final ChatMemory chatMemory;
    private final EnterpriseMemoryService memoryService;
    private final MedicalSafetyService safetyService;
    private final MedicalIntentRouter intentRouter;
    private final LayeredMemoryService layeredMemory;
    private final AsyncTaskExecutor parallelToolCallExecutor;
    private final Set<String> parallelSafeTools;

    public MedicalAgentService(
            EnterpriseMemoryService memoryService,
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
        this.chatMemory = memoryService.chatMemory(EnterpriseMemoryService.MEDICAL_AGENT);
    }

    public SseEmitter doChatByStream(String message, String chatId) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        if (risk.emergency()) return immediate(risk.message());
        MedicalIntentRouter.RoutingDecision route = intentRouter.route(message, chatMemory.get(chatId));
        return createAgent(chatId, route).runStream(message);
    }

    public String doChat(String message, String chatId) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        if (risk.emergency()) return risk.message();
        MedicalIntentRouter.RoutingDecision route = intentRouter.route(message, chatMemory.get(chatId));
        return createAgent(chatId, route).run(message);
    }

    public String doChat(String message, String chatId, MedicalIntentRouter.RoutingDecision route) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        return risk.emergency() ? risk.message() : createAgent(chatId, route).run(message);
    }

    /** 工作流内部执行入口：不把规划提示和中间结果写入用户会话记忆。 */
    public String executeTask(String message) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        return risk.emergency() ? risk.message() : createAgent(null, null).run(message);
    }

    /** MedicalAgent实际上就是一个ReAct Agent */
    private MedicalAgent createAgent(String chatId, MedicalIntentRouter.RoutingDecision route) {
        MedicalAgent agent = new MedicalAgent(
                allTools, dashscopeChatModel, parallelToolCallExecutor, parallelSafeTools);
        agent.setChatId(chatId);
        agent.setChatMemory(chatMemory);
        if (route != null) {
            agent.setSystemPrompt(agent.getSystemPrompt() + "\n\n" + intentRouter.routingHint(route));
            agent.setSystemPrompt(agent.getSystemPrompt() + layeredMemory.context(
                    EnterpriseMemoryService.MEDICAL_AGENT, chatId, route.standaloneQuestion()));
        }
        if (chatId != null) agent.setCompletionListener(ignored -> layeredMemory.scheduleCompaction(
                EnterpriseMemoryService.MEDICAL_AGENT, chatId, chatMemory));
        return agent;
    }

    private SseEmitter immediate(String message) {
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            emitter.send(message);
            emitter.send("[DONE]");
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public List<FileBasedChatMemory.ConversationSummary> listConversations() {
        return memoryService.listConversations(EnterpriseMemoryService.MEDICAL_AGENT);
    }

    public List<Message> getChatMessages(String chatId) {
        return chatMemory.get(chatId);
    }

    public void clearChat(String chatId) {
        layeredMemory.deleteConversation(EnterpriseMemoryService.MEDICAL_AGENT, chatId, chatMemory);
    }
}
