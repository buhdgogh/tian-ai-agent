package com.tianaiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.chatmemory.EnterpriseMemoryService;
import com.tianaiagent.chatmemory.FileBasedChatMemory;
import com.tianaiagent.chatmemory.LayeredMemoryService;
import com.tianaiagent.rag.QueryRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalAdvisorRagMemoryTest {
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
        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        ObjectMapper objectMapper = new ObjectMapper();
        LayeredMemoryService layeredMemory = mock(LayeredMemoryService.class);
        when(layeredMemory.context(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("");
        when(knowledgeBase.queryWithSources("高血压有哪些并发症？", 8))
                .thenReturn(objectMapper.createObjectNode().put("answer", "可累及心、脑、肾。"));
        when(queryRewriter.doQueryRewrite(contains("那需要做什么检查？")))
                .thenReturn("高血压患者需要做什么并发症筛查？");
        when(knowledgeBase.queryWithSources("高血压患者需要做什么并发症筛查？", 8))
                .thenReturn(objectMapper.createObjectNode().put("answer", "需要结合医生评估。"));

        MedicalAdvisorService service = new MedicalAdvisorService(
                mock(ChatModel.class), memoryService, new MedicalSafetyService(),
                knowledgeBase, objectMapper, queryRewriter, layeredMemory);

        service.doChatWithRag("高血压有哪些并发症？", "rag_chat");
        verify(queryRewriter, never()).doQueryRewrite(org.mockito.ArgumentMatchers.anyString());
        service.doChatWithRag("那需要做什么检查？", "rag_chat");

        assertEquals(4, ragMemory.get("rag_chat").size());
        assertEquals("那需要做什么检查？", ragMemory.get("rag_chat").get(2).getText());
        verify(queryRewriter).doQueryRewrite(contains("高血压有哪些并发症？"));
        verify(knowledgeBase).queryWithSources("高血压患者需要做什么并发症筛查？", 8);

        ragMemory.clear("rag_chat");
        Files.deleteIfExists(tempDir.resolve("advisor"));
        Files.deleteIfExists(tempDir.resolve("rag"));
        Files.deleteIfExists(tempDir);
    }
}
