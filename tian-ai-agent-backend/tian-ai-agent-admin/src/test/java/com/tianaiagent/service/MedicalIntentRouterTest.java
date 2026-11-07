package com.tianaiagent.service;

import com.tianaiagent.rag.QueryRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;

class MedicalIntentRouterTest {
    @Test
    void routesHighConfidenceMedicationRequestWithoutLlmFallback() {
        MedicalIntentRouter router = new MedicalIntentRouter(new MedicalSafetyService(),
                mock(QueryRewriter.class), mock(ChatModel.class));

        MedicalIntentRouter.RoutingDecision decision = router.route(
                "请查询这个药物的剂量、禁忌和不良反应", List.of());

        assertEquals("KEYWORD", decision.strategy());
        assertEquals(List.of("MEDICATION"), decision.intentTypes());
        assertFalse(decision.requiresWorkflow());
        assertEquals("HIGH", decision.riskLevel());
    }

    @Test void reportIsAWorkflowEvenWhenKeywordClassificationIsConfident() {
        var model = mock(ChatModel.class);
        var router = new MedicalIntentRouter(new MedicalSafetyService(), mock(QueryRewriter.class), model);
        var decision = router.route("请生成报告并做总结", List.of());
        assertEquals(List.of("REPORT_GENERATION"), decision.intentTypes());
        assertTrue(decision.requiresWorkflow());
        verify(model, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test void aFollowUpIsResolvedBeforeTheKeywordFastPath() {
        var rewriter = mock(QueryRewriter.class);
        when(rewriter.doQueryRewrite(anyString())).thenReturn("将之前的指南对比整理成报告并总结");
        var router = new MedicalIntentRouter(new MedicalSafetyService(), rewriter, mock(ChatModel.class));
        var decision = router.route("整理成报告", List.of(new org.springframework.ai.chat.messages.AssistantMessage("先前指南对比结果")));
        assertTrue(decision.standaloneQuestion().contains("指南对比"));
        assertTrue(decision.requiresWorkflow());
        verify(rewriter).doQueryRewrite(contains("先前指南对比结果"));
    }

    @Test void emergencyDoesNotWaitForClassificationOrHistoryRewrite() {
        var rewriter = mock(QueryRewriter.class);
        var model = mock(ChatModel.class);
        var router = new MedicalIntentRouter(new MedicalSafetyService(), rewriter, model);
        var decision = router.route("突发胸痛伴呼吸困难", List.of(new org.springframework.ai.chat.messages.UserMessage("健康咨询")));
        assertEquals(List.of("EMERGENCY"), decision.intentTypes());
        assertFalse(decision.requiresWorkflow());
        verifyNoInteractions(rewriter);
        verify(model, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
}
