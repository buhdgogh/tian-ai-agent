package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.domain.agent.service.AgentWorkflowService;
import com.tianaiagent.api.workflow.WorkflowRequest;
import com.tianaiagent.domain.conversation.service.ConversationScope;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/workflows")
public class AgentWorkflowController {
    private final AgentWorkflowService workflowService;
    private final KnowledgeBaseService knowledgeBaseService;

    public AgentWorkflowController(AgentWorkflowService workflowService, KnowledgeBaseService knowledgeBaseService) {
        this.workflowService = workflowService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRecord submit(@RequestBody WorkflowRequest request, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        String spaceId = KnowledgeSpace.optionalId(request.spaceId());
        KnowledgeSpace space = spaceId == null ? null : knowledgeBaseService.space(spaceId);
        String chatId = request.chatId() == null || request.chatId().isBlank() ? null
                : ConversationScope.storageId(identity.ownerId(), request.chatId(), spaceId);
        return workflowService.submit(request.objective(), chatId, identity.ownerId(), request.reviewEnabled(), spaceId, space != null && space.medical());
    }
}
