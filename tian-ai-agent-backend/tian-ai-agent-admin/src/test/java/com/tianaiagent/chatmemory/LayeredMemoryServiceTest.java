package com.tianaiagent.chatmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.mcp.McpToolClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LayeredMemoryServiceTest {

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
