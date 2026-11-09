package com.tianaiagent.domain.medical.service;

import com.tianaiagent.domain.medical.service.MedicalSafetyService;

import com.tianaiagent.domain.medical.adapter.port.MedicalQueryRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MedicalIntentRouterTest {
    @Test
    void routesHighConfidenceMedicationRequestWithoutLlmFallback() {
        MedicalIntentRouter router = new MedicalIntentRouter(new MedicalSafetyService(),
                mock(MedicalQueryRewriter.class), mock(ChatModel.class));

        MedicalIntentRouter.RoutingDecision decision = router.route(
                "请查询这个药物的剂量、禁忌和不良反应", List.of());

        assertEquals("KEYWORD", decision.strategy());
        assertEquals(List.of("MEDICATION"), decision.intentTypes());
        assertFalse(decision.requiresWorkflow());
        assertEquals("HIGH", decision.riskLevel());
    }
}
