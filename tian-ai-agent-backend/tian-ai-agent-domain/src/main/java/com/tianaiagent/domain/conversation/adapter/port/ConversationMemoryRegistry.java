package com.tianaiagent.domain.conversation.adapter.port;

import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface ConversationMemoryRegistry {
    String MEDICAL_ADVISOR = "medical-advisor";
    String MEDICAL_AGENT = "medical-agent";
    String MEDICAL_RAG = "medical-rag";

    ChatMemory chatMemory(String space);

    void touch(String space, String conversationId);

    default List<Message> history(String space, String conversationId) {
        return chatMemory(space).get(conversationId);
    }

    List<ConversationSummary> listConversations(String space);

    default List<ConversationSummary> listConversations(String space, String ownerId, String scopeId) {
        return listConversations(space);
    }

    List<String> listConversationIds(String space);
}
