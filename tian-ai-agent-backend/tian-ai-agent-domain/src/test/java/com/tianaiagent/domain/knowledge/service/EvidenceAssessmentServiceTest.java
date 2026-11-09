package com.tianaiagent.domain.knowledge.service;

import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;

import com.tianaiagent.types.config.DocMcpProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAssessmentServiceTest {
    private final DocMcpProperties.Evidence thresholds = new DocMcpProperties().getEvidence();
    private final EvidenceAssessmentService service = new EvidenceAssessmentService(thresholds.getMinimumRelevantScore(),
            thresholds.getMinimumSufficientScore(), thresholds.getMinimumSources());

    @Test
    void distinguishesMissingInsufficientConflictingAndSufficientEvidence() {
        assertEquals(EvidenceAssessmentService.Level.NO_RELEVANT_EVIDENCE,
                service.assess(List.of()).level());
        assertFalse(service.assess(List.of(source(0.2, "", ""))).allowGeneration());

        var conflict = service.assess(List.of(
                source(0.8, "guideline-a", "supports"),
                source(0.7, "guideline-a", "opposes")));
        assertEquals(EvidenceAssessmentService.Level.CONFLICTING_EVIDENCE, conflict.level());
        assertTrue(conflict.allowGeneration());

        assertEquals(EvidenceAssessmentService.Level.SUFFICIENT_EVIDENCE,
                service.assess(List.of(source(0.8, "", ""))).level());
    }

    private KnowledgeIndex.Evidence source(double score, String group, String stance) {
        return new KnowledgeIndex.Evidence(1, "chunk", null, 1, "doc", "title",
                null, score, score, 0, "text", Map.of("conflict_group", group, "evidence_stance", stance));
    }
}
