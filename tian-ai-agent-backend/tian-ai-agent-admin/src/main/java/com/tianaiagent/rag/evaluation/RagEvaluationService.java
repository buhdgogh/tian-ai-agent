package com.tianaiagent.rag.evaluation;

import com.tianaiagent.rag.enterprise.CitedAnswerService;
import com.tianaiagent.rag.enterprise.HybridRetrievalService;
import com.tianaiagent.task.AsyncTaskCenter;
import com.tianaiagent.task.TaskRecord;
import com.tianaiagent.task.TaskType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagEvaluationService {
    private final AsyncTaskCenter taskCenter;
    private final HybridRetrievalService retrievalService;
    private final CitedAnswerService citedAnswerService;

    public RagEvaluationService(AsyncTaskCenter taskCenter,
                                HybridRetrievalService retrievalService,
                                CitedAnswerService citedAnswerService) {
        this.taskCenter = taskCenter;
        this.retrievalService = retrievalService;
        this.citedAnswerService = citedAnswerService;
    }

    public TaskRecord submit(EvaluationRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new IllegalArgumentException("评测集不能为空");
        }
        int topK = request.topK() == null ? 5 : Math.max(1, Math.min(request.topK(), 20));
        Map<String, Object> input = Map.of("name", defaultName(request.name()),
                "caseCount", request.cases().size(), "topK", topK,
                "generateAnswers", request.generateAnswers());
        return taskCenter.submit(TaskType.RAG_EVALUATION, request.ownerId(), input,
                context -> evaluate(context, request, topK));
    }

    private EvaluationReport evaluate(AsyncTaskCenter.TaskContext context,
                                      EvaluationRequest request, int topK) throws Exception {
        Map<String, Map<String, Double>> stageMetrics = new LinkedHashMap<>();
        for (HybridRetrievalService.RetrievalMode mode : HybridRetrievalService.RetrievalMode.values()) {
            stageMetrics.put(mode.name(), evaluateRetrievalStage(request.cases(), topK, mode));
        }
        List<CaseResult> results = new ArrayList<>();
        double reciprocalRankSum = 0;
        int hits = 0;
        int citationAnswers = 0;
        double scoreSum = 0;
        for (int index = 0; index < request.cases().size(); index++) {
            EvaluationCase item = request.cases().get(index);
            if (item.question() == null || item.question().isBlank()) continue;
            int progress = 5 + (int) ((index * 90.0) / request.cases().size());
            context.update(progress, "执行 RAG 评测", "正在评测第 " + (index + 1) + " 条问题");
            List<HybridRetrievalService.Evidence> evidence = retrievalService.search(item.question(), topK);
            int rank = expectedRank(evidence, item.expectedDocumentIds());
            boolean hit = rank > 0;
            if (hit) { hits++; reciprocalRankSum += 1.0 / rank; }
            double topScore = evidence.isEmpty() ? 0 : evidence.getFirst().score();
            scoreSum += topScore;
            String answer = null;
            int citationCount = 0;
            if (request.generateAnswers()) {
                CitedAnswerService.CitedAnswer cited = citedAnswerService.answer(item.question(), topK);
                answer = cited.answer();
                citationCount = cited.sources().size();
                if (citationCount > 0) citationAnswers++;
            }
            results.add(new CaseResult(item.id(), item.question(), hit, rank, topScore,
                    evidence.stream().map(HybridRetrievalService.Evidence::documentId).distinct().toList(),
                    citationCount, answer));
        }
        int count = results.size();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("hitRateAtK", count == 0 ? 0 : round((double) hits / count));
        metrics.put("mrrAtK", count == 0 ? 0 : round(reciprocalRankSum / count));
        metrics.put("averageTopScore", count == 0 ? 0 : round(scoreSum / count));
        if (request.generateAnswers()) {
            metrics.put("citationCoverage", count == 0 ? 0 : round((double) citationAnswers / count));
        }
        context.update(98, "汇总评测指标", "评测报告已生成");
        return new EvaluationReport(defaultName(request.name()), topK, count, metrics, stageMetrics, results);
    }

    private Map<String, Double> evaluateRetrievalStage(List<EvaluationCase> cases, int topK,
                                                       HybridRetrievalService.RetrievalMode mode) {
        int evaluated = 0;
        int hits = 0;
        double reciprocalRank = 0;
        double recall = 0;
        for (EvaluationCase item : cases) {
            if (item == null || item.question() == null || item.question().isBlank()) continue;
            List<String> expected = item.expectedDocumentIds() == null ? List.of()
                    : item.expectedDocumentIds().stream().distinct().toList();
            List<HybridRetrievalService.Evidence> evidence = retrievalService.search(item.question(), topK, mode);
            int rank = expectedRank(evidence, expected);
            if (rank > 0) { hits++; reciprocalRank += 1.0 / rank; }
            long found = expected.stream().filter(id -> evidence.stream()
                    .anyMatch(value -> id.equals(value.documentId()))).count();
            recall += expected.isEmpty() ? 0 : (double) found / expected.size();
            evaluated++;
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("hitRateAtK", evaluated == 0 ? 0 : round((double) hits / evaluated));
        metrics.put("mrrAtK", evaluated == 0 ? 0 : round(reciprocalRank / evaluated));
        metrics.put("recallAtK", evaluated == 0 ? 0 : round(recall / evaluated));
        return metrics;
    }

    private int expectedRank(List<HybridRetrievalService.Evidence> evidence, List<String> expectedIds) {
        if (expectedIds == null || expectedIds.isEmpty()) return 0;
        for (int i = 0; i < evidence.size(); i++) {
            if (expectedIds.contains(evidence.get(i).documentId())) return i + 1;
        }
        return 0;
    }

    private String defaultName(String value) { return value == null || value.isBlank() ? "RAG Evaluation" : value.trim(); }
    private double round(double value) { return Math.round(value * 10000d) / 10000d; }

    public record EvaluationRequest(String name, String ownerId, Integer topK,
                                    boolean generateAnswers, List<EvaluationCase> cases) {}
    public record EvaluationCase(String id, String question, List<String> expectedDocumentIds) {}
    public record CaseResult(String id, String question, boolean hit, int firstRelevantRank,
                             double topScore, List<String> retrievedDocumentIds,
                             int citationCount, String answer) {}
    public record EvaluationReport(String name, int topK, int caseCount,
                                   Map<String, Double> metrics,
                                   Map<String, Map<String, Double>> stageMetrics,
                                   List<CaseResult> cases) {}
}
