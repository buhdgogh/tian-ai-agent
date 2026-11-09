package com.tianaiagent.infrastructure.adapter.repository.conversation;

import com.tianaiagent.domain.conversation.service.ConversationScope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 可供多实例共享的 Redis 会话记忆实现。 */
public class RedisChatMemory implements ChatMemory {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final String conversationSetKey;
    private final int maxMessages;
    private final Duration ttl;

    public RedisChatMemory(StringRedisTemplate redis, ObjectMapper objectMapper, String space,
                           int maxMessages, Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = "ai:memory:" + space + ":conversation:";
        this.conversationSetKey = "ai:memory:" + space + ":ids";
        this.maxMessages = maxMessages;
        this.ttl = ttl;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        validate(conversationId);
        if (messages == null || messages.isEmpty()) return;
        String key = key(conversationId);
        List<String> serialized = messages.stream().map(this::serialize).toList();
        redis.opsForList().rightPushAll(key, serialized);
        redis.opsForList().trim(key, -maxMessages, -1);
        redis.opsForSet().add(conversationSetKey, conversationId);
        touch(conversationId);
    }

    void touch(String conversationId) {
        validate(conversationId);
        if (Boolean.TRUE.equals(redis.expire(key(conversationId), ttl))) {
            redis.expire(conversationSetKey, ttl.plusDays(1));
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        validate(conversationId);
        List<String> values = redis.opsForList().range(key(conversationId), 0, -1);
        if (values == null) return List.of();
        return values.stream().map(this::deserialize).toList();
    }

    @Override
    public void clear(String conversationId) {
        validate(conversationId);
        redis.delete(key(conversationId));
        redis.opsForSet().remove(conversationSetKey, conversationId);
    }

    public List<String> listConversationIds() {
        Set<String> ids = redis.opsForSet().members(conversationSetKey);
        return ids == null ? List.of() : ids.stream().sorted().toList();
    }

    public List<ConversationSummary> listConversations() {
        List<ConversationSummary> summaries = new ArrayList<>();
        for (String id : listConversationIds()) {
            List<Message> messages = get(id);
            if (messages.isEmpty()) continue;
            // 每次会话活动会续期，从剩余 TTL 恢复已有历史的最近活动时间。
            Long remaining = redis.getExpire(key(id), TimeUnit.MILLISECONDS);
            if (remaining != null && remaining == -2) continue;
            Instant updatedAt = remaining == null || remaining < 0 ? Instant.EPOCH
                    : Instant.now().minusMillis(Math.max(0, ttl.toMillis() - remaining));
            String title = messages.stream()
                    .filter(message -> "USER".equalsIgnoreCase(message.getMessageType().name()))
                    .map(Message::getText).filter(text -> text != null && !text.isBlank())
                    .findFirst().map(text -> text.length() > 20 ? text.substring(0, 20) + "..." : text)
                    .orElse("新对话");
            summaries.add(new ConversationSummary(id, title, updatedAt));
        }
        return summaries;
    }

    private String serialize(Message message) {
        try {
            return objectMapper.writeValueAsString(ChatMessageMapper.toDto(message));
        } catch (Exception e) {
            throw new IllegalStateException("序列化会话消息失败", e);
        }
    }

    private Message deserialize(String value) {
        try {
            // 旧 Redis JSON 只有 role/content，反序列化后新字段为 null，仍可兼容读取。
            StructuredChatMessageDTO dto = objectMapper.readValue(value, StructuredChatMessageDTO.class);
            return ChatMessageMapper.fromDto(dto);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化会话消息失败", e);
        }
    }

    private String key(String conversationId) {
        return keyPrefix + conversationId;
    }

    private void validate(String conversationId) {
        if (!ConversationScope.validStorageId(conversationId)) {
            throw new IllegalArgumentException("非法会话 ID");
        }
    }
}
