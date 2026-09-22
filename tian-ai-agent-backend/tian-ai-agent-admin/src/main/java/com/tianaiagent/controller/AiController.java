package com.tianaiagent.controller;

import com.tianaiagent.chatmemory.FileBasedChatMemory;
import com.fasterxml.jackson.databind.JsonNode;
import com.tianaiagent.service.MedicalAdvisorService;
import com.tianaiagent.service.MedicalAdvisorService.MedicalEvidenceReport;
import com.tianaiagent.service.MedicalAgentService;
import com.tianaiagent.service.MedicalIntentRouter;
import com.tianaiagent.workflow.AgentWorkflowService;
import com.tianaiagent.workflow.WorkflowModels.WorkflowRequest;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** 医疗信息、证据查询与安全分诊接口。 */
@RestController
@RequestMapping("/ai")
public class AiController {
    @Resource
    private MedicalAdvisorService advisorService;

    @Resource
    private MedicalAgentService agentService;

    @Resource private MedicalIntentRouter intentRouter;
    @Resource private AgentWorkflowService workflowService;

    @GetMapping("/sessions")
    public List<FileBasedChatMemory.ConversationSummary> listSessions(
            @RequestParam(defaultValue = "medical") String type) {
        return switch (type) {
            case "agent" -> agentService.listConversations();
            case "rag" -> advisorService.listRagConversations();
            default -> advisorService.listConversations();
        };
    }

    @DeleteMapping("/sessions/{chatId}")
    public boolean clearSession(@PathVariable String chatId,
                                @RequestParam(defaultValue = "medical") String type) {
        switch (type) {
            case "agent" -> agentService.clearChat(chatId);
            case "rag" -> advisorService.clearRagChat(chatId);
            default -> advisorService.clearChat(chatId);
        }
        return true;
    }

    @GetMapping("/sessions/{chatId}/messages")
    public Object getMessages(@PathVariable String chatId,
                              @RequestParam(defaultValue = "medical") String type) {
        return switch (type) {
            case "agent" -> agentService.getChatMessages(chatId);
            case "rag" -> advisorService.getRagChatMessages(chatId);
            default -> advisorService.getChatMessages(chatId);
        };
    }

    @PostMapping("/medical/chat")
    public String chat(@RequestBody ChatRequest request) {
        request.validate();
        return advisorService.doChat(request.message(), request.chatId());
    }

    @PostMapping(value = "/medical/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        request.validate();
        return advisorService.doChatByStream(request.message(), request.chatId())
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    @PostMapping("/medical/evidence-report")
    public MedicalEvidenceReport createReport(@RequestBody ChatRequest request) {
        request.validate();
        return advisorService.createEvidenceReport(request.message(), request.chatId());
    }

    @PostMapping("/medical/rag-chat")
    public JsonNode ragChat(@RequestBody ChatRequest request) {
        request.validate();
        return advisorService.doChatWithRag(request.message(), request.chatId());
    }

    @PostMapping("/medical/tool-chat")
    public String toolChat(@RequestBody ChatRequest request) {
        request.validate();
        return advisorService.doChatWithTools(request.message(), request.chatId());
    }

    @PostMapping(value = "/medical-agent/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentChat(@RequestBody ChatRequest request) {
        request.validate();
        return agentService.doChatByStream(request.message(), request.chatId());
    }

    /** 单一意图走 ReAct，多意图或复杂目标自动提交 Planner 工作流。 */
    @PostMapping("/medical/dispatch")
    public Object dispatch(@RequestBody ChatRequest request) {
        request.validate();
        MedicalIntentRouter.RoutingDecision route = intentRouter.route(
                request.message(), agentService.getChatMessages(request.chatId()));
        if (route.requiresWorkflow()) {
            return Map.of("mode", "WORKFLOW", "route", route, "task",
                    workflowService.submit(new WorkflowRequest(request.message(), request.chatId(),
                            request.chatId(), true)));
        }
        return Map.of("mode", "REACT", "route", route,
                "answer", agentService.doChat(request.message(), request.chatId(), route));
    }

    public record ChatRequest(String message, String chatId) {
        void validate() {
            if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
            if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) {
                throw new IllegalArgumentException("会话 ID 只能包含字母、数字、下划线和连字符");
            }
        }
    }
}
