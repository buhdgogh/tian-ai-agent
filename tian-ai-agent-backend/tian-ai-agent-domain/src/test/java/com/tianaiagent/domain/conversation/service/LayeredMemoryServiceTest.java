package com.tianaiagent.domain.conversation.service;

import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway;
import com.tianaiagent.domain.conversation.adapter.repository.LongTermMemoryStore;
import com.tianaiagent.domain.conversation.adapter.repository.UserProfileStore;
import com.tianaiagent.types.exception.McpDisabledException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LayeredMemoryServiceTest {
    private final LongTermMemoryStore legacy = mock(LongTermMemoryStore.class);
    private final UserProfileStore profiles = mock(UserProfileStore.class);
    private final SemanticMemoryGateway semantic = mock(SemanticMemoryGateway.class);
    private final ChatMemory recent = mock(ChatMemory.class);
    private final ChatModel model = mock(ChatModel.class);

    @Test
    void compactsFirstTurnAndRecallsOnlyMatchingAccountAndSpace() {
        String first = "u-alice--kb-general--first";
        String namespace = "medical-agent:alice:general";
        when(recent.get(first)).thenReturn(List.of(new UserMessage("我是 Java 初学者，请用中文回答"),
                new AssistantMessage("我们可以从集合开始")));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"summary":"用户在学习 Java 集合","facts":{"skill_level":"Java 初学者","phone":"should be ignored"},
                 "preferences":{"language":"中文"}}
                """)))));
        var service = service(Optional.of(semantic), true);

        service.scheduleCompaction("medical-agent", first, recent);
        var profile = new UserProfileStore.Profile(Map.of("skill_level", "Java 初学者"), Map.of("language", "中文"));
        verify(profiles).merge("alice", profile);
        verify(semantic).upsert(eq(namespace), startsWith("summary_"), eq("用户在学习 Java 集合"), anyMap());

        when(profiles.find("alice")).thenReturn(Optional.of(profile));
        when(semantic.search(namespace, "接着学什么", 3)).thenReturn(List.of(
                new SemanticMemoryGateway.Match("id1", namespace, "用户在学习 Java 集合", Map.of(), 0.85),
                new SemanticMemoryGateway.Match("id2", "medical-agent:bob:general", "别人的历史", Map.of(), 0.99),
                new SemanticMemoryGateway.Match("id3", namespace, "低相关历史", Map.of(), 0.1)));
        assertThat(service.context("medical-agent", "u-alice--kb-general--second", "接着学什么"))
                .contains("用户在学习 Java 集合", "Java 初学者", "中文").doesNotContain("别人的历史", "低相关历史");
        assertThat(service.context("medical-agent", "u-alice--kb-private--second", "接着学什么"))
                .contains("Java 初学者").doesNotContain("用户在学习 Java 集合");
        verify(semantic).search("medical-agent:alice:private", "接着学什么", 3);
    }

    @Test
    void deletesOnlyTheConversationVectorAndPreservesTheAccountProfile() {
        var service = service(Optional.of(semantic), true);
        service.deleteConversation("medical-agent", "chat1", recent);

        var order = inOrder(semantic, legacy, recent);
        order.verify(semantic).delete(eq("medical-agent:admin:"), startsWith("summary_"));
        order.verify(semantic).delete("medical-agent:chat1");
        order.verify(legacy).delete("medical-agent:chat1");
        order.verify(recent).clear("chat1");
        verifyNoInteractions(profiles);
    }

    @Test
    void preservesOtherStoresWhenVectorDeleteFailsAndAllowsRetry() {
        doThrow(new IllegalStateException("vector unavailable")).doNothing()
                .when(semantic).delete(eq("medical-agent:admin:"), anyString());
        var service = service(Optional.of(semantic), true);

        assertThatThrownBy(() -> service.deleteConversation("medical-agent", "chat1", recent))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(legacy, recent);

        service.deleteConversation("medical-agent", "chat1", recent);
        verify(legacy).delete("medical-agent:chat1");
        verify(recent).clear("chat1");
    }

    @Test
    void rejectsIncompleteDeletionIfMilvusIsUnavailable() {
        assertThatThrownBy(() -> service(Optional.empty(), true).deleteConversation("medical-advisor", "chat2", recent))
                .isInstanceOf(McpDisabledException.class);
        verifyNoInteractions(legacy, recent);
    }

    private LayeredMemoryService service(Optional<SemanticMemoryGateway> gateway, boolean enabled) {
        return new LayeredMemoryService(Optional.of(legacy), Optional.of(profiles), gateway, model,
                Runnable::run, enabled, 2, 6, 3, 0.4);
    }
}
