package com.tianaiagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianaiagent.service.MedicalAdvisorService;
import com.tianaiagent.service.MedicalAdvisorService.MedicalEvidenceReport;
import com.tianaiagent.service.MedicalAgentService;
import com.tianaiagent.service.MedicalIntentRouter;
import com.tianaiagent.workflow.AgentWorkflowService;
import com.tianaiagent.workflow.WorkflowModels.WorkflowRequest;
import com.tianaiagent.observability.ExecutionContext;
import com.tianaiagent.profile.UserProfileRepository;
import com.tianaiagent.user.CurrentUserService;
import com.tianaiagent.conversation.ConversationService;
import com.tianaiagent.conversation.ConversationRepository.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
    @Resource private ObjectMapper objectMapper;
    @Autowired(required = false) private CurrentUserService currentUser;
    @Autowired(required = false) private ConversationService conversations;
    @Autowired(required = false) private com.tianaiagent.task.AsyncTaskCenter taskCenter;
    @Autowired(required = false) private com.tianaiagent.stream.AgentResponseStreamService responseStream;

    @GetMapping("/sessions")
    public Object listSessions(
            @RequestParam(defaultValue = "medical") String type) {
        if (conversations != null) return conversations.repository().list(currentUser.userId(), type);
        return switch (type) {
            case "agent" -> agentService.listConversations();
            case "rag" -> advisorService.listRagConversations();
            default -> advisorService.listConversations();
        };
    }

    @DeleteMapping("/sessions/{chatId}")
    public boolean clearSession(@PathVariable String chatId,
                                @RequestParam(defaultValue = "medical") String type) {
        String memoryId = chatId;
        if (conversations != null) memoryId = conversations.repository().find(currentUser.userId(), chatId, type).sessionId();
        switch (type) {
            case "agent" -> agentService.clearChat(memoryId);
            case "rag" -> advisorService.clearRagChat(memoryId);
            default -> advisorService.clearChat(memoryId);
        }
        if (conversations != null) conversations.repository().delete(currentUser.userId(), chatId, type);
        return true;
    }

    @GetMapping("/sessions/{chatId}/messages")
    public Object getMessages(@PathVariable String chatId,
                              @RequestParam(defaultValue = "medical") String type) {
        if (conversations != null) return conversations.repository().messages(currentUser.userId(), chatId, type);
        return switch (type) {
            case "agent" -> agentService.getChatMessages(chatId);
            case "rag" -> advisorService.getRagChatMessages(chatId);
            default -> advisorService.getChatMessages(chatId);
        };
    }

    @PostMapping("/sessions")
    public Object createSession(@RequestBody SessionRequest request) {
        if (conversations == null) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "创建会话需要MySQL模式");
        String chatId = request.chatId() == null || request.chatId().isBlank()
                ? java.util.UUID.randomUUID().toString().replace("-", "") : request.chatId();
        return conversations.repository().ensure(currentUser.userId(), chatId,
                request.type() == null ? "medical" : request.type(), request.title());
    }

    public record SessionRequest(String chatId, String type, String title) {}

    @PostMapping("/sessions/{chatId}/turns/{turnId}/interrupt")
    public Object interruptTurn(@PathVariable String chatId, @PathVariable String turnId,
                                @RequestParam(defaultValue = "agent") String type) {
        if (conversations == null) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "修改提问需要MySQL模式");
        var stopped = conversations.repository().interrupt(currentUser.userId(), chatId, type, turnId);
        String warning = "";
        if (stopped.taskId() != null && taskCenter != null && "CANCELLED".equals(stopped.status())) {
            try { taskCenter.cancel(stopped.taskId()); }
            catch (RuntimeException error) { warning = "已停止交付旧结果，后台任务取消尚未确认"; }
        }
        return Map.of("turnId", stopped.turnId(), "status", stopped.status(), "warning", warning);
    }

    @PostMapping("/medical/chat")
    public String chat(@RequestBody ChatRequest request) {
        request.validate();
        return execute(request, "medical", id -> advisorService.doChat(request.message(), id), value -> value);
    }

    @PostMapping(value = "/medical/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        request.validate();
        Turn turn = begin(request, "medical");
        try {
            Flux<String> source = traced(request, () -> advisorService.doChatByStream(request.message(), memoryId(request, turn)));
            return (turn == null ? source : conversations.recordStream(turn, source))
                    .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
        } catch (RuntimeException error) { fail(turn, error); throw error; }
    }

    @PostMapping("/medical/evidence-report")
    public MedicalEvidenceReport createReport(@RequestBody ChatRequest request) {
        request.validate();
        return execute(request, "medical", id -> advisorService.createEvidenceReport(request.message(), id), this::json);
    }

    @PostMapping("/medical/rag-chat")
    public JsonNode ragChat(@RequestBody ChatRequest request) {
        request.validate();
        return execute(request, "rag", id -> advisorService.doChatWithRag(request.message(), id), value -> value.path("answer").asText(""));
    }

    @PostMapping("/medical/tool-chat")
    public String toolChat(@RequestBody ChatRequest request) {
        request.validate();
        return execute(request, "medical", id -> advisorService.doChatWithTools(request.message(), id), value -> value);
    }

    @PostMapping(value = "/medical-agent/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentChat(@RequestBody ChatRequest request) {
        request.validate();
        Turn turn = begin(request, "agent");
        try {
            return traced(request, () -> agentService.doChatByStream(request.message(), memoryId(request, turn),
                    turn == null ? null : (text, error) -> {
                        if (error == null) conversations.complete(turn, text); else conversations.fail(turn, text, error);
                    }));
        } catch (RuntimeException error) { fail(turn, error); throw error; }
    }

    /** 单一意图走 ReAct，多意图或复杂目标自动提交 Planner 工作流。 */
    @PostMapping("/medical/dispatch")
    public Object dispatch(@RequestBody ChatRequest request) {
        request.validate();
        return traced(request, () -> dispatchTraced(request));
    }

    private Object dispatchTraced(ChatRequest request) {
        String type = request.sessionType() == null ? "agent" : request.sessionType();
        Turn turn = conversations == null ? null : conversations.repository().begin(owner(request), request.chatId(), type,
                request.message(), request.turnId(), request.replacesTurnId());
        try {
            if (turn != null && request.replacesTurnId() != null) agentService.clearChat(turn.session().sessionId());
            if (turn != null && !conversations.repository().isRunning(turn.replyMessageId())) {
                return Map.of("mode", "INTERRUPTED", "turnId", turn.turnId());
            }
            List<org.springframework.ai.chat.messages.Message> history = dispatchHistory(request, turn, type);
            MedicalIntentRouter.RoutingDecision route = intentRouter.route(
                    request.message(), history);
            if (turn != null && !conversations.repository().isRunning(turn.replyMessageId())) {
                return Map.of("mode", "INTERRUPTED", "turnId", turn.turnId());
            }
            if (route.requiresWorkflow()) {
                return Map.of("mode", "WORKFLOW", "route", route, "task",
                        workflowService.submitForConversation(new WorkflowRequest(route.standaloneQuestion(), request.chatId(),
                                owner(request), true), turn));
            }
            String answer = turn == null ? agentService.doChat(route.standaloneQuestion(), memoryId(request, turn), route)
                    : agentService.doConversationChat(route.standaloneQuestion(), memoryId(request, turn), route, history,
                            () -> !conversations.repository().isRunning(turn.replyMessageId()));
            if (turn != null) conversations.complete(turn, answer);
            return Map.of("mode", "REACT", "route", route, "answer", answer);
        } catch (RuntimeException error) { fail(turn, error); throw error; }
    }

    @PostMapping(value = "/medical/dispatch/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dispatchStream(@RequestBody ChatRequest request, jakarta.servlet.http.HttpServletResponse response) {
        request.validate();
        if (request.turnId() != null && request.turnId().length() > 32) throw new IllegalArgumentException("turnId最多32字符");
        String user = owner(request), type = request.sessionType() == null ? "agent" : request.sessionType();
        Turn turn = conversations == null ? null : conversations.repository().begin(user, request.chatId(), type,
                request.message(), request.turnId(), request.replacesTurnId());
        response.setHeader("Cache-Control", "no-cache"); response.setHeader("X-Accel-Buffering", "no");
        try {
            if (turn != null && request.replacesTurnId() != null) agentService.clearChat(turn.session().sessionId());
            return responseStream.open(user, request.chatId(), request.message(), turn, dispatchHistory(request, turn, type));
        } catch (RuntimeException error) { fail(turn, error); throw error; }
    }

    private List<org.springframework.ai.chat.messages.Message> dispatchHistory(ChatRequest request, Turn turn, String type) {
        if (turn == null) return agentService.getChatMessages(request.chatId());
        // Include completed workflow replies and legacy sessions, excluding this new turn.
        return conversations.repository().messages(owner(request), request.chatId(), type).stream()
                .filter(message -> !message.turnId().equals(turn.turnId()) && "COMPLETED".equals(message.status()))
                .filter(message -> message.text() != null && !message.text().isBlank())
                .<org.springframework.ai.chat.messages.Message>map(message -> "USER".equals(message.messageType())
                        ? new org.springframework.ai.chat.messages.UserMessage(message.text())
                        : new org.springframework.ai.chat.messages.AssistantMessage(message.text()))
                .toList();
    }

    private String owner(ChatRequest request) {
        if (currentUser == null) return request.userId() == null ? request.chatId() : request.userId();
        String owner = currentUser.userId();
        if (request.userId() != null && !owner.equals(request.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "userId与当前登录用户不一致");
        }
        return owner;
    }

    private Turn begin(ChatRequest request, String type) {
        return conversations == null ? null : conversations.begin(owner(request), request.chatId(), type, request.message());
    }
    private String memoryId(ChatRequest request, Turn turn) { return turn == null ? request.chatId() : turn.session().sessionId(); }
    private void fail(Turn turn, Throwable error) { if (turn != null) conversations.fail(turn, "", error); }
    private <T> T execute(ChatRequest request, String type, java.util.function.Function<String, T> work,
                          java.util.function.Function<T, String> render) {
        Turn turn = begin(request, type);
        try {
            T result = traced(request, () -> work.apply(memoryId(request, turn)));
            if (turn != null) conversations.complete(turn, render.apply(result));
            return result;
        } catch (RuntimeException error) { fail(turn, error); throw error; }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("序列化会话结果失败", error); }
    }

    private <T> T traced(ChatRequest request, java.util.function.Supplier<T> work) {
        try (var ignored = ExecutionContext.open(new ExecutionContext.Trace(null, owner(request),
                request.chatId(), null, null, null))) {
            return work.get();
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidRequest(IllegalArgumentException error) {
        return Map.of("error", error.getMessage() == null ? "请求参数不合法" : error.getMessage());
    }

    public record ChatRequest(String message, String chatId, String userId, String sessionType, String turnId, String replacesTurnId) {
        public ChatRequest(String message, String chatId) { this(message, chatId, null, null, null, null); }
        public ChatRequest(String message, String chatId, String userId) { this(message, chatId, userId, null, null, null); }
        public ChatRequest(String message, String chatId, String userId, String sessionType) { this(message, chatId, userId, sessionType, null, null); }
        void validate() {
            if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
            if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) {
                throw new IllegalArgumentException("会话 ID 只能包含字母、数字、下划线和连字符");
            }
            if (userId != null) UserProfileRepository.validateUserId(userId);
            if (sessionType != null && !List.of("medical", "rag", "agent").contains(sessionType)) {
                throw new IllegalArgumentException("非法会话类型");
            }
            if (turnId != null && !turnId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法轮次ID");
            if (replacesTurnId != null && !replacesTurnId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法原轮次ID");
        }
    }
}
