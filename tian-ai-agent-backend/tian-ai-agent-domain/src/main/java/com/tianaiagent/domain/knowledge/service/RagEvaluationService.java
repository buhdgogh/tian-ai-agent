package com.tianaiagent.domain.knowledge.service;

import com.tianaiagent.domain.task.service.AsyncTaskCenter;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.domain.task.model.valobj.TaskType;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;
import com.tianaiagent.types.exception.McpDisabledException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RagEvaluationService {
    private final AsyncTaskCenter taskCenter;
    private final Optional<IndexService> indexing;
    private final Optional<KnowledgeIndex> answers;

    public RagEvaluationService(AsyncTaskCenter taskCenter,
                                Optional<IndexService> indexing, Optional<KnowledgeIndex> answers) {
        this.taskCenter = taskCenter;
        this.indexing = indexing;
        this.answers = answers;
    }

    public TaskRecord submit(EvaluationRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new IllegalArgumentException("评测集不能为空");
        }
        int topK = request.topK() == null ? 5 : Math.max(1, Math.min(request.topK(), 20));
        IndexService index = indexing.orElseThrow(McpDisabledException::new);
        index.space(request.spaceId());
        Map<String, Object> input = Map.of("name", defaultName(request.name()),
                "caseCount", request.cases().size(), "topK", topK,
                "generateAnswers", request.generateAnswers(), "spaceId", request.spaceId());
        return taskCenter.submit(TaskType.RAG_EVALUATION, request.ownerId(), input,
                context -> evaluate(context, request, topK, index));
    }

    private EvaluationReport evaluate(AsyncTaskCenter.TaskContext context,
                                      EvaluationRequest request, int topK, IndexService indexService) throws Exception {
        Map<String, Map<String, Double>> stageMetrics = new LinkedHashMap<>();
        for (IndexService.RetrievalMode mode : IndexService.RetrievalMode.values()) {
            stageMetrics.put(mode.name(), evaluateRetrievalStage(indexService, request, topK, mode));
        }
        List<CaseResult> results = new ArrayList<>();
        double reciprocalRankSum = 0;
        int hits = 0;
        int citationAnswers = 0;
        double scoreSum = 0;
        for (int index = 0; index < request.cases().size(); index++) {
            EvaluationCase item = request.cases().get(index);
            if (item == null || item.question() == null || item.question().isBlank()) continue;
            int progress = 5 + (int) ((index * 90.0) / request.cases().size());
            context.update(progress, "执行 RAG 评测", "正在评测第 " + (index + 1) + " 条问题");
            var evidence = indexService.retrieve(request.spaceId(), item.question(), topK).sources();
            int rank = expectedRank(evidence, item.expectedDocumentIds());
            boolean hit = rank > 0;
            if (hit) { hits++; reciprocalRankSum += 1.0 / rank; }
            double topScore = evidence.isEmpty() ? 0 : evidence.getFirst().score();
            scoreSum += topScore;
            String answer = null;
            int citationCount = 0;
            if (request.generateAnswers()) {
                var cited = answers.orElseThrow(McpDisabledException::new)
                        .queryWithSources(request.spaceId(), item.question(), topK);
                answer = cited.answer();
                citationCount = cited.sources().size();
                if (citationCount > 0) citationAnswers++;
            }
            results.add(new CaseResult(item.id(), item.question(), hit, rank, topScore,
                    evidence.stream().map(KnowledgeIndex.Evidence::documentId).distinct().toList(),
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

    private Map<String, Double> evaluateRetrievalStage(IndexService index, EvaluationRequest request, int topK,
                                                       IndexService.RetrievalMode mode) {
        int evaluated = 0;
        int hits = 0;
        double reciprocalRank = 0;
        double recall = 0;
        for (EvaluationCase item : request.cases()) {
            if (item == null || item.question() == null || item.question().isBlank()) continue;
            List<String> expected = item.expectedDocumentIds() == null ? List.of()
                    : item.expectedDocumentIds().stream().distinct().toList();
            var evidence = index.retrieve(request.spaceId(), item.question(), topK, mode).sources();
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

    private int expectedRank(List<KnowledgeIndex.Evidence> evidence, List<String> expectedIds) {
        if (expectedIds == null || expectedIds.isEmpty()) return 0;
        for (int i = 0; i < evidence.size(); i++) {
            if (expectedIds.contains(evidence.get(i).documentId())) return i + 1;
        }
        return 0;
    }

    private String defaultName(String value) { return value == null || value.isBlank() ? "RAG Evaluation" : value.trim(); }
    private double round(double value) { return Math.round(value * 10000d) / 10000d; }

    public record EvaluationRequest(String name, String ownerId, String spaceId, Integer topK,
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
