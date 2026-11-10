package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.medical.service.MedicalAgentService;
import com.tianaiagent.domain.medical.service.MedicalIntentRouter;

import com.tianaiagent.domain.agent.adapter.port.AgentAnswerOutput;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class MedicalDispatchService {
    private final MedicalIntentRouter intentRouter;
    private final MedicalAgentService agentService;
    private final AgentWorkflowService workflowService;

    public MedicalDispatchService(MedicalIntentRouter intentRouter, MedicalAgentService agentService,
                                  AgentWorkflowService workflowService) {
        this.intentRouter = intentRouter;
        this.agentService = agentService;
        this.workflowService = workflowService;
    }

    public Map<String, Object> dispatch(String message, String chatId, String ownerId, String spaceId) {
        return dispatch(message, chatId, ownerId, spaceId, true);
    }

    public Map<String, Object> dispatch(String message, String chatId, String ownerId, String spaceId, boolean medical) {
        return dispatch(message, chatId, ownerId, spaceId, medical, "");
    }

    public Map<String, Object> dispatch(String message, String chatId, String ownerId, String spaceId, boolean medical,
                                        String skillInstructions) {
        var route = route(message, chatId, medical);
        if (route.requiresWorkflow()) {
            return Map.of("mode", "WORKFLOW", "route", route, "task",
                    workflowService.submit(message, chatId, ownerId, true, spaceId, medical, skillInstructions));
        }
        return Map.of("mode", "REACT", "route", route,
                "answer", agentService.doChat(message, chatId, route, spaceId, medical, skillInstructions));
    }

    public void stream(String message, String chatId, String ownerId, String spaceId, boolean medical, Output output) {
        stream(message, chatId, ownerId, spaceId, medical, output, "");
    }

    public void stream(String message, String chatId, String ownerId, String spaceId, boolean medical, Output output,
                       String skillInstructions) {
        stream(message, chatId, ownerId, spaceId, medical, output, skillInstructions, false);
    }

    public void stream(String message, String chatId, String ownerId, String spaceId, boolean medical, Output output,
                       String skillInstructions, boolean localWorkspace) {
        var route = route(message, chatId, medical);
        if (localWorkspace && route.requiresWorkflow()) {
            // 浏览器目录工具随当前流式连接工作。
            route = new MedicalIntentRouter.RoutingDecision(route.standaloneQuestion(), route.intentTypes(),
                    route.confidence(), route.strategy(), false, route.riskLevel());
        }
        output.route(route);
        if (route.requiresWorkflow()) {
            output.workflow(workflowService.submit(message, chatId, ownerId, true, spaceId, medical, skillInstructions));
            return;
        }
        agentService.streamAnswer(message, chatId, route, spaceId, medical, output, skillInstructions);
    }

    private MedicalIntentRouter.RoutingDecision route(String message, String chatId, boolean medical) {
        return AgentRunContext.stage("意图识别", () -> intentRouter.route(message, agentService.getChatMessages(chatId), medical));
    }

    public interface Output extends AgentAnswerOutput {
        void route(MedicalIntentRouter.RoutingDecision decision);
        void workflow(TaskRecord task);
    }
}
