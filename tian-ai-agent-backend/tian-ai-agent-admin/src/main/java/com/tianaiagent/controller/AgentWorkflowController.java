package com.tianaiagent.controller;

import com.tianaiagent.task.TaskRecord;
import com.tianaiagent.workflow.AgentWorkflowService;
import com.tianaiagent.workflow.WorkflowModels.WorkflowRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/workflows")
public class AgentWorkflowController {
    private final AgentWorkflowService workflowService;
    public AgentWorkflowController(AgentWorkflowService workflowService) { this.workflowService = workflowService; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRecord submit(@RequestBody WorkflowRequest request) { return workflowService.submit(request); }
}
