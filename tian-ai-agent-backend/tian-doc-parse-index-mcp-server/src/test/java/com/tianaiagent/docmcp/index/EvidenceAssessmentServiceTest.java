package com.tianaiagent.docmcp.query;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAssessmentServiceTest {
    @Test void evaluatesAbsoluteRelevanceInsteadOfAssumingFusionOrderIsConfidenceOrder() {
        var service = new EvidenceAssessmentService(new DocMcpProperties());
        assertEquals(EvidenceAssessmentService.Level.SUFFICIENT_EVIDENCE,
                service.assess(List.of(Map.of("score", 0.2, "fusionScore", 0.9), Map.of("score", 0.8, "fusionScore", 0.5))).level());
    }
    private final EvidenceAssessmentService service =
            new EvidenceAssessmentService(new DocMcpProperties());

    @Test
    void distinguishesMissingInsufficientConflictingAndSufficientEvidence() {
        assertEquals(EvidenceAssessmentService.Level.NO_RELEVANT_EVIDENCE,
                service.assess(List.of()).level());
        assertFalse(service.assess(List.of(Map.of("score", 0.2))).allowGeneration());

        var conflict = service.assess(List.of(
                source(0.8, "guideline-a", "supports"),
                source(0.7, "guideline-a", "opposes")));
        assertEquals(EvidenceAssessmentService.Level.CONFLICTING_EVIDENCE, conflict.level());
        assertTrue(conflict.allowGeneration());

        assertEquals(EvidenceAssessmentService.Level.SUFFICIENT_EVIDENCE,
                service.assess(List.of(Map.of("score", 0.8, "metadata", Map.of()))).level());
    }

    private Map<String, Object> source(double score, String group, String stance) {
        return Map.of("score", score, "metadata", Map.of(
                "conflict_group", group, "evidence_stance", stance));
    }
}
