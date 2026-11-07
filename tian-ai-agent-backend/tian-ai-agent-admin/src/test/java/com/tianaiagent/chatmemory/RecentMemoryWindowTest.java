package com.tianaiagent.chatmemory;

import com.tianaiagent.agent.BaseAgent;
import com.tianaiagent.agent.model.AgentState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RecentMemoryWindowTest {
    @Test
    void retainsToolCallAndResponseTogetherOrDropsEntireTurn() {
        List<Message> history = List.of(new UserMessage("old"), new AssistantMessage("answer"),
                new UserMessage("recent"), new AssistantMessage("", Map.of(), List.of(
                        new AssistantMessage.ToolCall("call1", "function", "search", "{}"))),
                new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(
                        "call1", "search", "result")), Map.of()), new AssistantMessage("done"));
        assertEquals(history.subList(2, 6), RecentMemoryWindow.select(history, 4, 12000));
        assertTrue(RecentMemoryWindow.select(history, 3, 12000).isEmpty());
        assertTrue(RecentMemoryWindow.select(history, 12, 10).isEmpty());
    }

    @Test
    void dropsOrphanToolResponseAtBeginningOfStorageWindow() {
        List<Message> history = List.of(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("call1", "search", "result")), Map.of()),
                new AssistantMessage("old answer"), new UserMessage("recent"), new AssistantMessage("answer"));
        assertEquals(history.subList(2, 4), RecentMemoryWindow.select(history, 12, 12000));
    }

    @Test
    void trimmingInputDoesNotRewriteHistoryOrSaveItTwice() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get("chat1")).thenReturn(List.of(new UserMessage("old"), new AssistantMessage("old answer"),
                new UserMessage("recent"), new AssistantMessage("recent answer")));
        BaseAgent agent = new BaseAgent() {
            @Override public String step() {
                assertEquals(3, getMessageList().size());
                getMessageList().add(new AssistantMessage("new answer"));
                setState(AgentState.FINISHED);
                return "new answer";
            }
        };
        agent.setChatId("chat1");
        agent.setChatMemory(memory);
        agent.setHistorySelector(messages -> RecentMemoryWindow.select(messages, 2, 12000));
        agent.run("new question");
        verify(memory).add(eq("chat1"), argThat((List<Message> messages) -> messages.size() == 2
                && messages.getFirst().getText().equals("new question")
                && messages.getLast().getText().equals("new answer")));
        verify(memory, never()).clear(anyString());
    }
}
