package com.tianaiagent.domain.medical.service;

import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import com.tianaiagent.domain.medical.service.MedicalSafetyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.infrastructure.adapter.repository.conversation.EnterpriseMemoryService;
import com.tianaiagent.infrastructure.adapter.repository.conversation.FileBasedChatMemory;
import com.tianaiagent.domain.conversation.service.LayeredMemoryService;
import com.tianaiagent.domain.medical.adapter.port.MedicalQueryRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.memory.ChatMemory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MedicalAdvisorRagMemoryTest {
    @Test
    void answersWithoutKnowledgeRetrievalWhenNoSpaceWasSelected() {
        EnterpriseMemoryService memoryService = mock(EnterpriseMemoryService.class);
        ChatMemory memory = mock(ChatMemory.class);
        when(memoryService.chatMemory(EnterpriseMemoryService.MEDICAL_ADVISOR)).thenReturn(memory);
        when(memoryService.chatMemory(EnterpriseMemoryService.MEDICAL_RAG)).thenReturn(memory);
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("直接回答")))));
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        var service = new MedicalAdvisorService(model, memoryService, new MedicalSafetyService(), knowledge,
                new ObjectMapper(), mock(MedicalQueryRewriter.class), mock(LayeredMemoryService.class));

        var result = service.doChatWithRag("解释健康教育的目的", "no_space", null);

        assertEquals("直接回答", result.path("answer").asText());
        assertEquals(0, result.path("sources").size());
        verifyNoInteractions(knowledge);
    }

    @Test
    void usesPreviousRagTurnsToRewriteFollowUpQuestion() throws Exception {
        Path tempDir = Path.of("..", "tmp", "test-data", "rag-memory-" + UUID.randomUUID());
        Files.createDirectories(tempDir);
        EnterpriseMemoryService memoryService = mock(EnterpriseMemoryService.class);
        FileBasedChatMemory advisorMemory = new FileBasedChatMemory(tempDir.resolve("advisor").toString());
        FileBasedChatMemory ragMemory = new FileBasedChatMemory(tempDir.resolve("rag").toString());
        when(memoryService.chatMemory(EnterpriseMemoryService.MEDICAL_ADVISOR)).thenReturn(advisorMemory);
        when(memoryService.chatMemory(EnterpriseMemoryService.MEDICAL_RAG)).thenReturn(ragMemory);

        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        MedicalQueryRewriter queryRewriter = mock(MedicalQueryRewriter.class);
        ObjectMapper objectMapper = new ObjectMapper();
        LayeredMemoryService layeredMemory = mock(LayeredMemoryService.class);
        when(layeredMemory.context(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("");
        when(knowledgeBase.queryWithSources("medical", "高血压有哪些并发症？", 8))
                .thenReturn(objectMapper.createObjectNode().put("answer", "可累及心、脑、肾。"));
        when(queryRewriter.doQueryRewrite(contains("那需要做什么检查？")))
                .thenReturn("高血压患者需要做什么并发症筛查？");
        when(knowledgeBase.queryWithSources("medical", "高血压患者需要做什么并发症筛查？", 8))
                .thenReturn(objectMapper.createObjectNode().put("answer", "需要结合医生评估。"));

        MedicalAdvisorService service = new MedicalAdvisorService(
                mock(ChatModel.class), memoryService, new MedicalSafetyService(),
                knowledgeBase, objectMapper, queryRewriter, layeredMemory);

        service.doChatWithRag("高血压有哪些并发症？", "rag_chat", "medical");
        verify(queryRewriter, never()).doQueryRewrite(org.mockito.ArgumentMatchers.anyString());
        service.doChatWithRag("那需要做什么检查？", "rag_chat", "medical");

        assertEquals(4, ragMemory.get("rag_chat").size());
        assertEquals("那需要做什么检查？", ragMemory.get("rag_chat").get(2).getText());
        verify(queryRewriter).doQueryRewrite(contains("高血压有哪些并发症？"));
        verify(knowledgeBase).queryWithSources("medical", "高血压患者需要做什么并发症筛查？", 8);

        ragMemory.clear("rag_chat");
        Files.deleteIfExists(tempDir.resolve("advisor"));
        Files.deleteIfExists(tempDir.resolve("rag"));
        Files.deleteIfExists(tempDir);
    }
}
