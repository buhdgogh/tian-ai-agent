package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.knowledge.service.RagEvaluationService;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag-evaluations")
public class RagEvaluationController {
    private final RagEvaluationService evaluationService;
    public RagEvaluationController(RagEvaluationService evaluationService) { this.evaluationService = evaluationService; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRecord submit(@RequestBody RagEvaluationService.EvaluationRequest request,
                             @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        return evaluationService.submit(new RagEvaluationService.EvaluationRequest(request.name(), identity.ownerId(),
                request.spaceId(), request.topK(), request.generateAnswers(), request.cases()));
    }
}
