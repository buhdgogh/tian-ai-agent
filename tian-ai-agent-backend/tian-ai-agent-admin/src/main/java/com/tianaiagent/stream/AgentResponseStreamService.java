package com.tianaiagent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.conversation.ConversationRepository.Turn;
import com.tianaiagent.conversation.ConversationService;
import com.tianaiagent.observability.ExecutionContext;
import com.tianaiagent.service.*;
import com.tianaiagent.task.*;
import com.tianaiagent.workflow.AgentWorkflowService;
import com.tianaiagent.workflow.WorkflowModels.WorkflowResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentResponseStreamService {
    private final AsyncTaskCenter tasks;
    private final MedicalAgentService agent;
    private final AgentWorkflowService workflow;
    private final MedicalIntentRouter router;
    private final MedicalSafetyService safety;
    private final FinalAnswerStreamer answers;
    private final ObjectMapper mapper;
    private final Set<LiveAnswerSession> sessions = ConcurrentHashMap.newKeySet();
    @Autowired(required = false) private ConversationService conversations;

    public AgentResponseStreamService(AsyncTaskCenter tasks, MedicalAgentService agent, AgentWorkflowService workflow,
                                      MedicalIntentRouter router, MedicalSafetyService safety, FinalAnswerStreamer answers, ObjectMapper mapper) {
        this.tasks = tasks; this.agent = agent; this.workflow = workflow; this.router = router;
        this.safety = safety; this.answers = answers; this.mapper = mapper;
    }

    public SseEmitter open(String owner, String chatId, String question, Turn turn, List<Message> history) {
        SseEmitter emitter = new SseEmitter(600_000L);
        LiveAnswerSession session = new LiveAnswerSession(emitter, mapper); sessions.add(session);
        emitter.onCompletion(() -> sessions.remove(session)); emitter.onError(error -> sessions.remove(session));
        if (turn != null && conversations != null)
            session.checkpointWith((text, metadata) -> conversations.repository().updateRunningContent(turn.replyMessageId(), text, metadata));
        Map<String, Object> input = new LinkedHashMap<>(); input.put("chatId", chatId); input.put("objective", question);
        if (turn != null) { input.put("conversationReplyId", turn.replyMessageId()); input.put("sessionId", turn.session().sessionId()); }
        try (var scope = ExecutionContext.open(new ExecutionContext.Trace(null, owner, chatId, null, null, null).output(session))) {
            TaskRecord submitted = tasks.submit(TaskType.AGENT_RESPONSE, owner, input, context -> {
                if (turn != null && conversations != null && !conversations.repository().isRunning(turn.replyMessageId()))
                    throw new java.util.concurrent.CancellationException("本轮已中断");
                context.checkCancelled(); context.update(5, "理解问题", "正在检查风险并识别处理流程");
                var risk = safety.assess(question);
                if (risk.emergency()) {
                    session.event("answer_start", Map.of()); session.event("answer_delta", Map.of("delta", risk.message()));
                    return Map.of("answer", risk.message(), "mode", "EMERGENCY", "trace", session.trace());
                }
                var route = router.route(question, history);
                session.event("route", Map.of("mode", route.requiresWorkflow() ? "WORKFLOW" : "REACT"));
                WorkflowResult report = null; String draft;
                if (route.requiresWorkflow()) {
                    report = workflow.executeWorkflow(context, route.standaloneQuestion(), true);
                    draft = report.executionResult() + "\n复核说明：" + mapper.writeValueAsString(report.review());
                    session.event("review", Map.of("review", report.review(), "plan", report.plan()));
                } else {
                    context.update(10, "检索与分析", "Agent 正在核对证据并按需调用工具");
                    draft = agent.doConversationChat(route.standaloneQuestion(), turn == null ? chatId : turn.session().sessionId(), route, history,
                            () -> context.isCancelled() || turn != null && conversations != null && !conversations.repository().isRunning(turn.replyMessageId()));
                    if (draft == null || draft.startsWith("执行错误") || draft.startsWith("❌ 错误")) throw new IllegalStateException("Agent未完成执行");
                }
                context.checkCancelled();
                context.update(95, "生成最终回答", "执行阶段已结束，正在组织最终答复");
                session.event("answer_start", Map.of());
                String finalAnswer = answers.stream(question, draft, delta -> session.event("answer_delta", Map.of("delta", delta)), () -> {
                    context.checkCancelled();
                    if (turn != null && conversations != null && !conversations.repository().isRunning(turn.replyMessageId()))
                        throw new java.util.concurrent.CancellationException("本轮已中断");
                });
                Map<String, Object> result = new LinkedHashMap<>(); result.put("answer", finalAnswer); result.put("trace", session.trace());
                result.put("mode", route.requiresWorkflow() ? "WORKFLOW" : "REACT");
                if (report != null) result.put("workflow", Map.of("plan", report.plan(), "executionResult", finalAnswer,
                        "review", report.review(), "iteration", report.iteration()));
                return result;
            });
            if (turn != null && conversations != null && !conversations.repository().isRunning(turn.replyMessageId())
                    && (submitted.getStatus() == TaskStatus.PENDING || submitted.getStatus() == TaskStatus.RUNNING)) tasks.cancel(submitted.getId());
        } catch (RuntimeException error) {
            if (turn != null && conversations != null) conversations.fail(turn, session.answer(), error);
            session.failBeforeSubmission();
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 5000)
    public void heartbeat() {
        for (var session : sessions) { session.heartbeat(); if (!session.connected()) sessions.remove(session); }
    }
}
