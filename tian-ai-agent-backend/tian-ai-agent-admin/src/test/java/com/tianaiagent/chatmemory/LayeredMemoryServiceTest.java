package com.tianaiagent.chatmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.mcp.McpToolClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LayeredMemoryServiceTest {

    @Test
    void mergesPreviousDigestWithRecentMessagesAndDoesNotSkipMessagesAfterLargeResult() {
        LongTermMemoryRepository repository = mock(LongTermMemoryRepository.class);
        McpToolClient mcp = mock(McpToolClient.class);
        ChatMemory recent = mock(ChatMemory.class);
        ChatModel model = mock(ChatModel.class);
        when(repository.enabled()).thenReturn(true);
        when(repository.find("medical-agent:chat1")).thenReturn(Optional.of(
                new LongTermMemoryRepository.StructuredMemory("medical-agent:chat1", "early goal",
                        List.of("confirmed fact"), 80, "now")));
        var messages = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
        messages.add(new AssistantMessage("x".repeat(20000)));
        for (int i = 0; i < 9; i++) messages.add(new UserMessage("message " + i));
        messages.add(new UserMessage("latest correction"));
        when(recent.get("chat1")).thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"summary\":\"merged\",\"confirmedFacts\":[\"confirmed fact\"]}")))));
        LayeredMemoryService service = new LayeredMemoryService(repository, mcp, new ObjectMapper(), model,
                Runnable::run, 10, 1, 3, "upsert_long_term_memory", "search_long_term_memory", "delete_long_term_memory");
        service.scheduleCompaction("medical-agent", "chat1", recent);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String input = prompt.getValue().getContents();
        assertTrue(input.contains("early goal"));
        assertTrue(input.contains("confirmed fact"));
        assertTrue(input.contains("latest correction"));
        verify(repository).upsert("medical-agent:chat1", "merged", List.of("confirmed fact"), 11);
    }

    @Test
    void deletesMilvusThenMysqlThenRecentRedisMemory() {
        LongTermMemoryRepository repository = mock(LongTermMemoryRepository.class);
        McpToolClient mcp = mock(McpToolClient.class);
        ChatMemory recent = mock(ChatMemory.class);
        when(repository.enabled()).thenReturn(true);

        LayeredMemoryService service = service(repository, mcp);
        service.deleteConversation("medical-agent", "chat1", recent);

        InOrder order = inOrder(mcp, repository, recent);
        order.verify(mcp).call("delete_long_term_memory", Map.of("owner_id", "medical-agent:chat1"));
        order.verify(repository).delete("medical-agent:chat1");
        order.verify(recent).clear("chat1");
    }

    @Test
    void clearsRecentMemoryOnlyWhenLongTermMemoryIsDisabled() {
        LongTermMemoryRepository repository = mock(LongTermMemoryRepository.class);
        McpToolClient mcp = mock(McpToolClient.class);
        ChatMemory recent = mock(ChatMemory.class);
        when(repository.enabled()).thenReturn(false);

        service(repository, mcp).deleteConversation("medical-advisor", "chat2", recent);

        verify(mcp, never()).call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(repository, never()).delete(org.mockito.ArgumentMatchers.anyString());
        verify(recent).clear("chat2");
    }

    private LayeredMemoryService service(LongTermMemoryRepository repository, McpToolClient mcp) {
        return new LayeredMemoryService(repository, mcp, new ObjectMapper(), mock(ChatModel.class),
                Runnable::run, 80, 20, 3, "upsert_long_term_memory",
                "search_long_term_memory", "delete_long_term_memory");
    }
}
