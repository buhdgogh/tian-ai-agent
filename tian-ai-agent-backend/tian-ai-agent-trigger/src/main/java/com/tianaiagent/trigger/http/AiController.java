package com.tianaiagent.trigger.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianaiagent.api.medical.ChatRequest;
import com.tianaiagent.api.medical.MedicalEvidenceReport;
import com.tianaiagent.domain.agent.adapter.port.StreamOutput;
import com.tianaiagent.domain.medical.service.MedicalAdvisorService;
import com.tianaiagent.domain.agent.service.MedicalDispatchService;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.domain.agent.service.AgentRunContext;
import com.tianaiagent.domain.agent.service.AgentPresetService;
import com.tianaiagent.domain.agent.service.LocalWorkspaceReads;
import com.tianaiagent.domain.agent.service.DesktopWorkspaceAgent;
import com.tianaiagent.domain.medical.service.MedicalAgentService;
import com.tianaiagent.domain.conversation.service.ConversationScope;
import com.tianaiagent.domain.conversation.adapter.port.ConversationMemoryRegistry;
import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import com.tianaiagent.domain.medical.service.MedicalIntentRouter;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.trigger.security.RequestIdentity;
import com.tianaiagent.types.exception.ModelUnavailableException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Agent 工作台对话、任务分发及兼容的医疗接口。 */
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
    @Resource private AgentPresetService presets;
    @Resource private LocalWorkspaceReads localWorkspaces;
    @Resource private DesktopWorkspaceAgent desktopWorkspaceAgent;

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
                conversation.medical(), request.modelProviderId(), presets.selectedSkillInstructions(identity.ownerId(), request.skillIds()));
    }

    @PostMapping("/dispatch")
    public Object workspaceDispatch(@RequestBody ChatRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        String skillInstructions = presets.selectedSkillInstructions(identity.ownerId(), request.skillIds());
        return runAudit.call(identity.ownerId(), "DISPATCH", request.modelProviderId(), () -> {
            memoryRegistry.touch(ConversationMemoryRegistry.MEDICAL_AGENT, conversation.chatId());
            var result = new LinkedHashMap<>(dispatchService.dispatch(request.message(), conversation.chatId(),
                    identity.ownerId(), conversation.spaceId(), conversation.medical(), skillInstructions));
            result.put("runId", AgentRunContext.current().id());
            return result;
        });
    }

    @PostMapping(value = "/dispatch/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter workspaceDispatchStream(@RequestBody ChatRequest request,
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity, HttpServletResponse response) {
        WorkspaceConversation conversation = workspaceConversation(request, identity);
        String skillInstructions = presets.selectedSkillInstructions(identity.ownerId(), request.skillIds());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        var run = runAudit.start(identity.ownerId(), "DISPATCH", null, request.modelProviderId());
        var output = new SseDispatchOutput(run, () -> {
            if (request.localWorkspace()) localWorkspaces.close(run.id());
        });
        if (request.localWorkspace()) localWorkspaces.open(run.id(), identity.ownerId(), output::workspaceRequest);
        try {
            output.attach(agentStreamExecutor.submit(() -> AgentRunContext.within(run, () -> {
                try {
                    output.started();
                    memoryRegistry.touch(ConversationMemoryRegistry.MEDICAL_AGENT, conversation.chatId());
                    dispatchService.stream(request.message(), conversation.chatId(), identity.ownerId(),
                            conversation.spaceId(), conversation.medical(), output, skillInstructions, request.localWorkspace());
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

    @PostMapping("/local-workspace/{runId}/results/{requestId}")
    public ResponseEntity<Void> workspaceResult(@PathVariable String runId, @PathVariable String requestId,
            @RequestBody JsonNode result, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        try {
            return localWorkspaces.reply(identity.ownerId(), runId, requestId, result)
                    ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @PostMapping(value = "/desktop-workspace/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter desktopWorkspaceStream(@RequestBody ChatRequest request,
            @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity,
            @RequestParam(defaultValue = "false") boolean auto,
            HttpServletRequest connection, HttpServletResponse response) {
        requireDesktop(connection, identity);
        request.validate();
        List<String> recent = !auto || request.recentRequests() == null ? List.of() : request.recentRequests().stream()
                .filter(text -> text != null && !text.isBlank()).limit(3).map(text -> text.substring(0, Math.min(700, text.length()))).toList();
        if (request.message().length() > 12_000) throw new IllegalArgumentException("工作区任务最多 12000 字符");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        var run = runAudit.start(identity.ownerId(), auto ? "DESKTOP_DISPATCH" : "DESKTOP_WORKSPACE", null, request.modelProviderId());
        var output = new SseDispatchOutput(run, () -> localWorkspaces.close(run.id()), 420_000L);
        try {
            output.attach(agentStreamExecutor.submit(() -> AgentRunContext.within(run, () -> {
                try {
                    output.started();
                    output.workspacePhase(0, "判断是否使用工作区");
                    var plan = desktopWorkspaceAgent.plan(request.message(), recent);
                    if (plan.workspace() && plan.roles().isEmpty()) {
                        output.delta("本轮计划中的角色都已跳过。");
                    } else if (plan.workspace()) {
                        localWorkspaces.openDesktop(run.id(), identity.ownerId(), output::desktopWorkspaceRequest);
                        output.send("route", Map.of("strategy", "WORKSPACE_TEAM", "workspaceRoles", plan.roles()));
                        output.workspacePhase(0, "本轮计划 · " + plan.steps());
                        if (auto) {
                            output.workspacePhase(0, "准备当前会话的 Docker 沙箱");
                            AgentRunContext.stage("准备 Docker 沙箱", () -> {
                                localWorkspaces.prepareDesktop(run.id());
                                return null;
                            });
                        }
                        desktopWorkspaceAgent.run(request.message(), plan, run, output::workspacePhase, output::delta);
                    } else {
                        WorkspaceConversation conversation = workspaceConversation(request, identity);
                        String skillInstructions = presets.selectedSkillInstructions(identity.ownerId(), request.skillIds());
                        memoryRegistry.touch(ConversationMemoryRegistry.MEDICAL_AGENT, conversation.chatId());
                        dispatchService.stream(request.message(), conversation.chatId(), identity.ownerId(),
                                conversation.spaceId(), conversation.medical(), output, skillInstructions);
                    }
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

    @PostMapping("/desktop-workspace/{runId}/results/{requestId}")
    public ResponseEntity<Void> desktopWorkspaceResult(@PathVariable String runId, @PathVariable String requestId,
            @RequestBody JsonNode result, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity,
            HttpServletRequest connection) {
        requireDesktop(connection, identity);
        try {
            return localWorkspaces.replyDesktop(identity.ownerId(), runId, requestId, result)
                    ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private static void requireDesktop(HttpServletRequest request, RequestIdentity identity) {
        String peer = request.getRemoteAddr();
        boolean loopback = "127.0.0.1".equals(peer) || "::1".equals(peer) || "0:0:0:0:0:0:0:1".equals(peer);
        if (!loopback || request.getHeader("Origin") != null
                || !"1".equals(request.getHeader("X-Tian-Desktop"))
                || identity.role() == RequestIdentity.Role.MCP_READER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
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
                ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId), spaceId, true, null, "");
    }

    private SseEmitter streamAgent(String message, String ownerId, String chatId, String spaceId, boolean medical,
                                 String modelProviderId, String skillInstructions) {
        var run = runAudit.start(ownerId, "REACT", null, modelProviderId);
        var output = new SseAgentOutput(run);
        try {
            return AgentRunContext.within(run, () -> {
                agentService.streamChat(message, chatId, spaceId, output, medical, skillInstructions);
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

    @ExceptionHandler(AgentPresetService.InvalidSkillSelection.class)
    public ResponseEntity<Map<String, String>> invalidSkillSelection(AgentPresetService.InvalidSkillSelection error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", error.getMessage()));
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

    private static final class SseAgentOutput implements StreamOutput {
        private final SseEmitter emitter = new SseEmitter(180_000L);
        private final AgentRunAudit.Run run;

        SseAgentOutput(AgentRunAudit.Run run) {
            this.run = run;
            emitter.onTimeout(() -> run.finish("TIMEOUT"));
            emitter.onError(error -> run.finish("FAILED"));
            emitter.onCompletion(() -> run.finish("CANCELLED"));
        }

        SseEmitter emitter() { return emitter; }

        @Override
        public void send(String step) throws IOException { emitter.send(step); }

        @Override
        public void complete() throws IOException {
            emitter.send("[DONE]");
            run.finish("COMPLETED");
            emitter.complete();
        }

        @Override
        public void fail(Throwable error) {
            run.finish("FAILED");
            emitter.completeWithError(error);
        }
    }

    private static final class SseDispatchOutput implements MedicalDispatchService.Output {
        private final SseEmitter emitter;
        private final AgentRunAudit.Run run;
        private final Runnable closeWorkspace;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Future<?> worker;

        SseDispatchOutput(AgentRunAudit.Run run, Runnable closeWorkspace) {
            this(run, closeWorkspace, 180_000L);
        }

        SseDispatchOutput(AgentRunAudit.Run run, Runnable closeWorkspace, long timeoutMs) {
            this.run = run;
            this.closeWorkspace = closeWorkspace;
            this.emitter = new SseEmitter(timeoutMs);
            emitter.onTimeout(() -> cancel("TIMEOUT"));
            emitter.onError(error -> cancel("CANCELLED"));
            emitter.onCompletion(() -> cancel("CANCELLED"));
        }

        SseEmitter emitter() { return emitter; }

        void attach(Future<?> future) {
            worker = future;
            if (closed.get()) future.cancel(true);
        }

        void started() { send("run", Map.of("runId", run.id())); }
        @Override public void route(MedicalIntentRouter.RoutingDecision decision) { send("route", decision); }
        @Override public void workflow(TaskRecord task) { send("task", task); }
        @Override public void thinking(int step) { send("thinking", Map.of("step", step)); }
        @Override public void delta(String text) { send("delta", Map.of("text", text)); }
        @Override public void tools(List<String> names) { send("tools", Map.of("names", names)); }
        void workspaceRequest(LocalWorkspaceReads.Request request) { send("workspace_request", request); }
        void desktopWorkspaceRequest(LocalWorkspaceReads.Request request) {
            send("workspace_request", Map.of("runId", run.id(), "request", request));
        }
        void workspacePhase(int step, String name) { send("thinking", Map.of("step", step, "name", name)); }

        private void send(String type, Object payload) {
            if (closed.get()) throw new CancellationException();
            try {
                emitter.send(SseEmitter.event().name(type).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (IllegalStateException e) {
                throw new CancellationException();
            }
        }

        void complete() {
            var sources = run.knowledgeSources();
            send("done", sources.isEmpty() ? Map.of() : Map.of("sources", sources));
            finish("COMPLETED");
        }

        void fail(RuntimeException error) {
            if (closed.get()) return;
            if (error instanceof UncheckedIOException || error instanceof CancellationException) {
                finish("CANCELLED");
                return;
            }
            String message = error instanceof ModelUnavailableException
                    ? error.getMessage() : "Agent 执行失败，请查看运行日志。";
            try {
                send("error", Map.of("message", message, "runId", run.id()));
            } catch (UncheckedIOException | CancellationException ignored) {
                finish("CANCELLED");
                return;
            }
            finish("FAILED");
        }

        private void finish(String status) {
            if (closed.compareAndSet(false, true)) {
                closeWorkspace.run();
                run.finish(status);
                emitter.complete();
            }
        }

        private void cancel(String status) {
            if (closed.compareAndSet(false, true)) {
                closeWorkspace.run();
                Future<?> running = worker;
                if (running != null) running.cancel(true);
                run.finish(status);
            }
        }
    }
}
