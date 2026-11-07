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
import org.springframework.beans.factory.annotation.Autowired;
import com.tianaiagent.user.CurrentUserService;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/ai/workflows")
public class AgentWorkflowController {
    private final AgentWorkflowService workflowService;
    @Autowired(required = false) private CurrentUserService currentUser;
    public AgentWorkflowController(AgentWorkflowService workflowService) { this.workflowService = workflowService; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRecord submit(@RequestBody WorkflowRequest request) {
        if (currentUser == null) return workflowService.submit(request);
        String owner = currentUser.userId();
        if (request.ownerId() != null && !owner.equals(request.ownerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ownerId与当前登录用户不一致");
        }
        return workflowService.submit(new WorkflowRequest(request.objective(), request.chatId(), owner, request.reviewEnabled()));
    }
}
