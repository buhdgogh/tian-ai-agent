package com.tianaiagent.controller;

import com.tianaiagent.rag.evaluation.RagEvaluationService;
import com.tianaiagent.task.TaskRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public TaskRecord submit(@RequestBody RagEvaluationService.EvaluationRequest request) {
        return evaluationService.submit(request);
    }
}
