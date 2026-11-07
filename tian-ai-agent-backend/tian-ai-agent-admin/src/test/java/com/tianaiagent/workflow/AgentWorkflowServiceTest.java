package com.tianaiagent.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tianaiagent.workflow.WorkflowModels.PlanStepDraft;
import static com.tianaiagent.workflow.WorkflowModels.PlannerOutput;
import static org.junit.jupiter.api.Assertions.*;

class AgentWorkflowServiceTest {

    @Test void oneGoalChainsRetrievalAnalysisAndReportUsingDependencyResults() {
        var tasks = org.mockito.Mockito.mock(com.tianaiagent.task.AsyncTaskCenter.class);
        var agent = org.mockito.Mockito.mock(com.tianaiagent.service.MedicalAgentService.class);
        var router = org.mockito.Mockito.mock(com.tianaiagent.service.MedicalIntentRouter.class);
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var prompts = new java.util.ArrayList<String>();
        org.mockito.Mockito.when(router.route(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new com.tianaiagent.service.MedicalIntentRouter.RoutingDecision("检索比较并报告", List.of("KNOWLEDGE_QUERY", "REPORT_GENERATION"), 0.9, "LLM", true, "ROUTINE"));
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(invocation -> {
            org.springframework.ai.chat.prompt.Prompt prompt = invocation.getArgument(0);
            String system = prompt.getInstructions().getFirst().getText();
            String output;
            if (system.contains("Agent 的 Planner")) output = """
                    {"summary":"检索后分析并报告","steps":[
                    {"id":"retrieve","role":"Researcher","description":"检索证据","successCriteria":"返回来源","dependsOn":[]},
                    {"id":"compare","role":"Analyst","description":"比较证据","successCriteria":"说明差异","dependsOn":["retrieve"]},
                    {"id":"report","role":"Writer","description":"整理报告","successCriteria":"整合比较结果","dependsOn":["compare"]}]}
                    """;
            else if (system.contains("结果整合器")) output = "完整循证报告：来源A支持常规建议，结论存在不确定性。";
            else output = "{\"passed\":true,\"requiresHumanReview\":false,\"reviewerRole\":\"NONE\",\"message\":\"目标与引用已核对\",\"issues\":[],\"revisionInstruction\":\"\",\"replanRequired\":false}";
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(output))));
        });
        org.mockito.Mockito.when(agent.executeTask(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0); prompts.add(prompt);
            if (prompt.contains("当前步骤：1.")) return "来源A及证据片段";
            if (prompt.contains("当前步骤：2.")) { assertTrue(prompt.contains("来源A及证据片段")); return "对比结论及不确定性"; }
            assertTrue(prompt.contains("对比结论及不确定性"));
            return "基于比较结论的报告草稿";
        });
        org.mockito.Mockito.when(tasks.submit(org.mockito.ArgumentMatchers.eq(com.tianaiagent.task.TaskType.AGENT_WORKFLOW), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    com.tianaiagent.task.AsyncTaskCenter.TaskWork work = invocation.getArgument(3);
                    var context = org.mockito.Mockito.mock(com.tianaiagent.task.AsyncTaskCenter.TaskContext.class);
                    var task = new com.tianaiagent.task.TaskRecord("chain", com.tianaiagent.task.TaskType.AGENT_WORKFLOW, "zhangsan", java.util.Map.of());
                    task.setResult(work.execute(context));
                    task.setStatus(com.tianaiagent.task.TaskStatus.COMPLETED);
                    return task;
                });
        var service = new AgentWorkflowService(tasks, agent, new com.tianaiagent.service.MedicalSafetyService(), router,
                model, new com.fasterxml.jackson.databind.ObjectMapper(), new org.springframework.core.task.support.TaskExecutorAdapter(Runnable::run));
        var task = service.submit(new com.tianaiagent.workflow.WorkflowModels.WorkflowRequest("检索比较并报告", "chain", "zhangsan", true));
        var result = (com.tianaiagent.workflow.WorkflowModels.WorkflowResult) task.getResult();
        assertEquals(3, prompts.size());
        assertEquals(3, result.executions().size());
        assertTrue(result.executionResult().contains("完整循证报告"));
        assertTrue(result.review().passed());
    }

    @Test
    void normalizesAndLimitsLlmPlan() {
        PlannerOutput output = new PlannerOutput("动态计划", List.of(
                new PlanStepDraft("kb", "Researcher", "检索证据", "返回来源", List.of()),
                new PlanStepDraft("web", "", "比较版本", "", List.of()),
                new PlanStepDraft("write", "Writer", "整理答案", "覆盖目标", List.of("kb", "web")),
                new PlanStepDraft("check", "Checker", "检查引用", "引用有效", List.of("write")),
                new PlanStepDraft("review", "Reviewer", "检查风险", "标记风险", List.of("check")),
                new PlanStepDraft("extra", "Extra", "不应进入计划", "超出上限", List.of("review"))));

        var plan = AgentWorkflowService.normalizePlan("比较指南", output);

        assertEquals(5, plan.steps().size());
        assertEquals(1, plan.steps().getFirst().order());
        assertEquals("Executor", plan.steps().get(1).role());
        assertEquals("给出可验证结果", plan.steps().get(1).successCriteria());
        assertEquals(List.of(), plan.steps().getFirst().dependsOn());
        assertEquals(List.of("kb", "web"), plan.steps().get(2).dependsOn());
        assertEquals("PENDING", plan.steps().getLast().status());
    }

    @Test
    void rejectsEmptyLlmPlan() {
        assertThrows(IllegalStateException.class,
                () -> AgentWorkflowService.normalizePlan("目标", new PlannerOutput("", List.of())));
    }

    @Test
    void deterministicMedicalReviewCannotBeSkipped() {
        assertTrue(AgentWorkflowService.requiresProfessionalReview("是否应该调整药物剂量"));
        assertFalse(AgentWorkflowService.requiresProfessionalReview("介绍健康饮食原则"));
    }

    @Test
    void fallsBackToSequentialDependenciesWhenPlannerOmitsDependencyField() {
        PlannerOutput output = new PlannerOutput("兼容旧输出", List.of(
                new PlanStepDraft("first", "Researcher", "步骤一", "完成一", null),
                new PlanStepDraft("second", "Writer", "步骤二", "完成二", null)));

        var plan = AgentWorkflowService.normalizePlan("目标", output);

        assertEquals(List.of(), plan.steps().getFirst().dependsOn());
        assertEquals(List.of("first"), plan.steps().get(1).dependsOn());
    }

    @Test
    void rejectsForwardDependencies() {
        PlannerOutput output = new PlannerOutput("非法计划", List.of(
                new PlanStepDraft("first", "Researcher", "步骤一", "完成一", List.of("second")),
                new PlanStepDraft("second", "Writer", "步骤二", "完成二", List.of())));

        assertThrows(IllegalStateException.class,
                () -> AgentWorkflowService.normalizePlan("目标", output));
    }

}
