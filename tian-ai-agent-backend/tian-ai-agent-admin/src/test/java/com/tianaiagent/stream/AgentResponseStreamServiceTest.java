package com.tianaiagent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.task.*;
import com.tianaiagent.service.*;
import com.tianaiagent.workflow.*;
import com.tianaiagent.workflow.WorkflowModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentResponseStreamServiceTest {
    MedicalAgentService agent = mock(MedicalAgentService.class);
    AgentWorkflowService workflow = mock(AgentWorkflowService.class);
    MedicalIntentRouter router = mock(MedicalIntentRouter.class);
    MedicalSafetyService safety = mock(MedicalSafetyService.class);
    FinalAnswerStreamer answers = mock(FinalAnswerStreamer.class);
    Map<String, TaskRecord> stored = new HashMap<>();
    AgentResponseStreamService service;
    MedicalIntentRouter.RoutingDecision route = mock(MedicalIntentRouter.RoutingDecision.class);

    @BeforeEach void setup() {
        var repo = mock(TaskRepository.class);
        doAnswer(call -> { TaskRecord task = call.getArgument(0); stored.put(task.getId(), task.copy()); return null; }).when(repo).save(any());
        var center = new AsyncTaskCenter(new ObjectMapper(), Runnable::run, repo);
        service = new AgentResponseStreamService(center, agent, workflow, router, safety, answers, new ObjectMapper());
        when(safety.assess(anyString())).thenReturn(new MedicalSafetyService.RiskAssessment("ROUTINE", false, List.of(), ""));
        when(router.route(anyString(), anyList())).thenReturn(route); when(route.standaloneQuestion()).thenReturn("问题");
        when(agent.doConversationChat(anyString(), anyString(), eq(route), anyList(), any())).thenReturn("private-working-draft");
        when(answers.stream(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            Runnable check = call.getArgument(3); Consumer<String> delta = call.getArgument(2);
            check.run(); delta.accept("最终"); delta.accept("回答"); return "最终回答";
        });
    }
    @Test void reactRunsAsAPersistentResponseTaskAndOnlyFinalAnswerIsDelivered() {
        service.open("u", "c", "问题", null, List.of());
        var task = stored.values().iterator().next();
        assertEquals(TaskStatus.COMPLETED, task.getStatus()); assertEquals(TaskType.AGENT_RESPONSE, task.getType());
        var result = (Map<?, ?>) task.getResult(); assertEquals("最终回答", result.get("answer"));
        assertFalse(result.toString().contains("private-working-draft"));
        verify(answers).stream(eq("问题"), eq("private-working-draft"), any(), any());
    }
    @Test void workflowCompletesReviewBeforeStreamingAndDoesNotExposeRawStepOutputs() throws Exception {
        when(route.requiresWorkflow()).thenReturn(true);
        var review = new ReviewResult(true, false, "NONE", "通过", List.of(), "", false);
        when(workflow.executeWorkflow(any(), anyString(), eq(true))).thenReturn(new WorkflowResult(
                new WorkflowPlan("目标", "计划", List.of()), List.of(new StepExecution(1, "检索", "读取资料", "private-step-output")), "已复核草稿", review, 1));
        service.open("u", "c", "问题", null, List.of());
        var result = (Map<?, ?>) stored.values().iterator().next().getResult();
        assertEquals("WORKFLOW", result.get("mode")); assertEquals("最终回答", result.get("answer"));
        assertFalse(result.toString().contains("private-step-output"));
        var order = inOrder(workflow, answers); order.verify(workflow).executeWorkflow(any(), anyString(), eq(true)); order.verify(answers).stream(anyString(), contains("已复核草稿"), any(), any());
    }
    @Test void deterministicEmergencyDoesNotWaitForOrInvokeAnAnswerModel() {
        when(safety.assess(anyString())).thenReturn(new MedicalSafetyService.RiskAssessment("EMERGENCY", true, List.of(), "紧急提示"));
        service.open("u", "c", "问题", null, List.of());
        var result = (Map<?, ?>) stored.values().iterator().next().getResult(); assertEquals("紧急提示", result.get("answer"));
        verifyNoInteractions(agent, workflow, router, answers);
    }
}
