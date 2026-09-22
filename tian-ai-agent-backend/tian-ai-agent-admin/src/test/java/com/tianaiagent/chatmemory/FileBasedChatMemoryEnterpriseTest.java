package com.tianaiagent.chatmemory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileBasedChatMemoryEnterpriseTest {
    @TempDir Path tempDir;

    @Test
    void persistsOnlyAppendedMessagesAndReloadsThem() {
        FileBasedChatMemory memory = new FileBasedChatMemory(tempDir.toString());
        memory.add("chat_01", List.of(new UserMessage("问题")));
        memory.add("chat_01", List.of(new AssistantMessage("回答")));

        List<Message> messages = memory.get("chat_01");
        assertEquals(2, messages.size());
        assertEquals("问题", messages.get(0).getText());
        assertEquals("回答", messages.get(1).getText());
    }

    @Test
    void rejectsPathTraversalConversationIds() {
        FileBasedChatMemory memory = new FileBasedChatMemory(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> memory.get("../outside"));
        assertThrows(IllegalArgumentException.class, () -> memory.get("..\\outside"));
    }

    @Test
    void persistsAndRestoresStructuredToolMessages() {
        FileBasedChatMemory memory = new FileBasedChatMemory(tempDir.toString());
        AssistantMessage assistant = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(
                        "call-01", "function", "searchKnowledge", "{\"query\":\"高血压\"}")));
        ToolResponseMessage toolResponse = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse(
                        "call-01", "searchKnowledge", "{\"answer\":\"检索结果\"}")), Map.of());

        memory.add("tool_chat", List.of(assistant, toolResponse));
        List<Message> restored = new FileBasedChatMemory(tempDir.toString()).get("tool_chat");

        AssistantMessage restoredAssistant = assertInstanceOf(AssistantMessage.class, restored.get(0));
        assertEquals("call-01", restoredAssistant.getToolCalls().get(0).id());
        assertEquals("searchKnowledge", restoredAssistant.getToolCalls().get(0).name());
        assertEquals("{\"query\":\"高血压\"}", restoredAssistant.getToolCalls().get(0).arguments());

        ToolResponseMessage restoredToolResponse = assertInstanceOf(ToolResponseMessage.class, restored.get(1));
        assertEquals("call-01", restoredToolResponse.getResponses().get(0).id());
        assertEquals("searchKnowledge", restoredToolResponse.getResponses().get(0).name());
        assertEquals("{\"answer\":\"检索结果\"}", restoredToolResponse.getResponses().get(0).responseData());
    }
}
