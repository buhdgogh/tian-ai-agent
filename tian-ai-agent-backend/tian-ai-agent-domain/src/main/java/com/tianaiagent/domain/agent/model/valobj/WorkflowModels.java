package com.tianaiagent.domain.agent.model.valobj;

import java.util.List;

public final class WorkflowModels {
    private WorkflowModels() {}

    public record PlanStepDraft(String id, String role, String description,
                                String successCriteria, List<String> dependsOn) {}
    public record PlannerOutput(String summary, List<PlanStepDraft> steps) {}
    public record WorkflowStep(int order, String id, String role, String description,
                               String successCriteria, List<String> dependsOn, String status) {}
    public record WorkflowPlan(String objective, String summary, List<WorkflowStep> steps) {}
    public record StepExecution(int order, String role, String description, String result) {}
    public record WorkflowResult(WorkflowPlan plan, List<StepExecution> executions,
                                 String executionResult, ReviewResult review,
                                 int iteration) {}
    public record ReviewResult(boolean passed, boolean requiresHumanReview,
                               String reviewerRole, String message, List<String> issues,
                               String revisionInstruction, boolean replanRequired) {}
    public record ReviewerOutput(boolean passed, boolean requiresHumanReview,
                                 String reviewerRole, String message, List<String> issues,
                                 String revisionInstruction, boolean replanRequired) {}
}
