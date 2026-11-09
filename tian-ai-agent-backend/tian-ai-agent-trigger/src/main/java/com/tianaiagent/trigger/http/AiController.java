package com.tianaiagent.trigger.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianaiagent.api.medical.ChatRequest;
import com.tianaiagent.api.medical.MedicalEvidenceReport;
import com.tianaiagent.domain.medical.service.MedicalAdvisorService;
import com.tianaiagent.domain.agent.service.MedicalDispatchService;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.medical.service.MedicalAgentService;
import com.tianaiagent.domain.conversation.service.ConversationScope;
import com.tianaiagent.domain.conversation.adapter.port.ConversationMemoryRegistry;
import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import com.tianaiagent.trigger.security.RequestIdentity;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** 知识工作台对话、任务分发及兼容的医疗接口。 */
@RestController
@RequestMapping("/ai")
public class AiController {
    @Resource
    private MedicalAdvisorService advisorService;

    @Resource
    private MedicalAgentService agentService;

    @Resource private MedicalDispatchService dispatchService;
    @Resource private KnowledgeBaseService knowledgeBaseService;
    @Resource private AgentRunAudit runAudit;
    @Resource(name = "agentStreamExecutor") private AsyncTaskExecutor agentStreamExecutor;
    @Resource private ConversationMemoryRegistry memoryRegistry;

    @GetMapping("/sessions")
    public List<ConversationSummary> listSessions(
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity, @RequestParam(defaultValue = "medical") String type,
            @RequestParam(required = false) String spaceId,
            @RequestParam(defaultValue = "false") boolean workspace) {
        String scope = sessionScope(spaceId, workspace);
        List<ConversationSummary> sessions = memoryRegistry.listConversations(
                memorySpace(type), identity.ownerId(), scope);
        return sessions.stream().flatMap(session -> ConversationScope.visibleId(identity.ownerId(), session.id(), scope)
                        .map(id -> new ConversationSummary(id, session.title(), session.updatedAt())).stream())
                .sorted(Comparator.comparing(ConversationSummary::updatedAt).reversed()).toList();
    }

    @DeleteMapping("/sessions/{chatId}")
    public boolean clearSession(@PathVariable String chatId,
                                @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity, @RequestParam(defaultValue = "medical") String type,
                                @RequestParam(required = false) String spaceId,
                              @RequestParam(defaultValue = "false") boolean workspace) {
        chatId = ConversationScope.storageId(identity.ownerId(), chatId, sessionScope(spaceId, workspace));
        switch (type) {
            case "agent" -> agentService.clearChat(chatId);
            case "rag" -> advisorService.clearRagChat(chatId);
            default -> advisorService.clearChat(chatId);
        }
        return true;
    }

    @GetMapping("/sessions/{chatId}/messages")
    public Object getMessages(@PathVariable String chatId,
                              @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity, @RequestParam(defaultValue = "medical") String type,
                              @RequestParam(required = false) String spaceId,
                              @RequestParam(defaultValue = "false") boolean workspace) {
        chatId = ConversationScope.storageId(identity.ownerId(), chatId, sessionScope(spaceId, workspace));
        return memoryRegistry.history(memorySpace(type), chatId);
    }

    private static String memorySpace(String type) {
        return switch (type) {
            case "agent" -> ConversationMemoryRegistry.MEDICAL_AGENT;
            case "rag" -> ConversationMemoryRegistry.MEDICAL_RAG;
            default -> ConversationMemoryRegistry.MEDICAL_ADVISOR;
        };
    }

    @PostMapping("/chat")
    public String workspaceChat(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        return runAudit.call(identity.ownerId(), "CHAT", () -> advisorService.doChat(
                request.message(), conversation.chatId(), conversation.spaceId(), conversation.medical()));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> workspaceChatStream(@RequestBody ChatRequest request,
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        return runAudit.stream(identity.ownerId(), "CHAT_STREAM", () -> advisorService.doChatByStream(
                        request.message(), conversation.chatId(), conversation.spaceId(), conversation.medical()))
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    @PostMapping("/chat/rag")
    public JsonNode workspaceRag(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        return runAudit.call(identity.ownerId(), "RAG_CHAT", () -> advisorService.doChatWithRag(
                request.message(), conversation.chatId(), conversation.spaceId(), conversation.medical()));
    }

    @PostMapping("/chat/tools")
    public String workspaceTools(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        return runAudit.call(identity.ownerId(), "TOOL_CHAT", () -> advisorService.doChatWithTools(
                request.message(), conversation.chatId(), conversation.spaceId(), conversation.medical()));
    }

    @PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter workspaceAgent(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        return streamAgent(request.message(), identity.ownerId(), conversation.chatId(), conversation.spaceId(),
                conversation.medical(), request.modelProviderId());
    }

    @PostMapping("/dispatch")
    public Object workspaceDispatch(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        return runAudit.call(identity.ownerId(), "DISPATCH", request.modelProviderId(), () -> {
            memoryRegistry.touch(ConversationMemoryRegistry.MEDICAL_AGENT, conversation.chatId());
            var result = new LinkedHashMap<>(dispatchService.dispatch(request.message(), conversation.chatId(),
                    identity.ownerId(), conversation.spaceId(), conversation.medical()));
            result.put("runId", AgentRunContext.current().id());
            return result;
        });
    }

    @PostMapping(value = "/dispatch/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter workspaceDispatchStream(@RequestBody ChatRequest request,
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity, HttpServletResponse response) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        var run = runAudit.start(identity.ownerId(), "DISPATCH", null, request.modelProviderId());
        var output = new SseDispatchOutput(run);
        try {
            output.attach(agentStreamExecutor.submit(() -> AgentRunContext.within(run, () -> {
                try {
                    output.started();
                    memoryRegistry.touch(ConversationMemoryRegistry.MEDICAL_AGENT, conversation.chatId());
                    dispatchService.stream(request.message(), conversation.chatId(), identity.ownerId(),
                            conversation.spaceId(), conversation.medical(), output);
                    output.complete();
                } catch (RuntimeException e) {
                    output.fail(e);
                }
                return null;
            })));
        } catch (RuntimeException e) {
            output.fail(e);
        }
        return output.emitter();
    }

    @PostMapping("/medical/chat")
    public String chat(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        request.validate();
        String spaceId = selectedSpace(request.spaceId());
        return runAudit.call(identity.ownerId(), "CHAT", () ->
                advisorService.doChat(request.message(), ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId), spaceId));
    }

    @PostMapping(value = "/medical/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        request.validate();
        String spaceId = selectedSpace(request.spaceId());
        return runAudit.stream(identity.ownerId(), "CHAT_STREAM", () ->
                advisorService.doChatByStream(request.message(), ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId), spaceId))
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    @PostMapping("/medical/evidence-report")
    public MedicalEvidenceReport createReport(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        request.validate();
        String spaceId = selectedSpace(request.spaceId());
        var report = runAudit.call(identity.ownerId(), "EVIDENCE_REPORT", () -> advisorService.createEvidenceReport(
                request.message(), ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId), spaceId));
        return report == null ? null : new MedicalEvidenceReport(report.title(), report.riskLevel(), report.summary(),
                report.redFlags(), report.evidence(), report.uncertainties(), report.nextSteps(),
                report.requiresProfessionalReview());
    }

    @PostMapping("/medical/rag-chat")
    public JsonNode ragChat(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        request.validate();
        String spaceId = selectedSpace(request.spaceId());
        return runAudit.call(identity.ownerId(), "RAG_CHAT", () ->
                advisorService.doChatWithRag(request.message(), ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId), spaceId));
    }

    @PostMapping("/medical/tool-chat")
    public String toolChat(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        request.validate();
        String spaceId = selectedSpace(request.spaceId());
        return runAudit.call(identity.ownerId(), "TOOL_CHAT", () ->
                advisorService.doChatWithTools(request.message(), ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId), spaceId));
    }

    @PostMapping(value = "/medical-agent/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentChat(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        request.validate();
        String spaceId = selectedSpace(request.spaceId());
        return streamAgent(request.message(), identity.ownerId(),
                ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId), spaceId, true, null);
    }

    private SseEmitter streamAgent(String message, String ownerId, String chatId, String spaceId, boolean medical,
                                 String modelProviderId) {
        var run = runAudit.start(ownerId, "REACT", null, modelProviderId);
        var output = new SseAgentOutput(run);
        try {
            return AgentRunContext.within(run, () -> {
                agentService.streamChat(message, chatId, spaceId, output, medical);
                return output.emitter();
            });
        } catch (RuntimeException | Error e) {
            run.finish("FAILED");
            throw e;
        }
    }

    /** 单一意图走 ReAct，多意图或复杂目标自动提交 Planner 工作流。 */
    @PostMapping("/medical/dispatch")
    public Object dispatch(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        request.validate();
        String spaceId = selectedSpace(request.spaceId());
        return runAudit.call(identity.ownerId(), "DISPATCH", () ->
                dispatchService.dispatch(request.message(), ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId),
                        identity.ownerId(), spaceId));
    }

    private String sessionScope(String spaceId, boolean workspace) {
        String scope = KnowledgeSpace.optionalId(spaceId);
        return workspace ? ConversationScope.workspaceScope(scope) : scope;
    }

    private WorkspaceConversation workspaceConversation(ChatRequest request, RequestIdentity identity) {
        request.validate();
        String id = KnowledgeSpace.optionalId(request.spaceId());
        KnowledgeSpace space = id == null ? null : knowledgeBaseService.space(id);
        String chatId = ConversationScope.storageId(identity.ownerId(), request.chatId(), ConversationScope.workspaceScope(id));
        return new WorkspaceConversation(chatId, id, space != null && space.medical());
    }

    private record WorkspaceConversation(String chatId, String spaceId, boolean medical) {}

    private String selectedSpace(String id) {
        String spaceId = KnowledgeSpace.optionalId(id);
        return spaceId == null ? null : knowledgeBaseService.space(spaceId).id();
    }
}
