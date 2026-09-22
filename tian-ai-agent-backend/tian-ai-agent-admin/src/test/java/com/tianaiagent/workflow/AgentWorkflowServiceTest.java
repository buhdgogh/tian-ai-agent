package com.tianaiagent.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tianaiagent.workflow.WorkflowModels.PlanStepDraft;
import static com.tianaiagent.workflow.WorkflowModels.PlannerOutput;
import static org.junit.jupiter.api.Assertions.*;

class AgentWorkflowServiceTest {

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
