package com.tianaiagent.chatmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
        redis.expire(key, ttl);
        redis.opsForSet().add(conversationSetKey, conversationId);
        redis.expire(conversationSetKey, ttl.plusDays(1));
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

    public List<FileBasedChatMemory.ConversationSummary> listConversations() {
        List<FileBasedChatMemory.ConversationSummary> summaries = new ArrayList<>();
        for (String id : listConversationIds()) {
            List<Message> messages = get(id);
            String title = messages.stream()
                    .filter(message -> "USER".equalsIgnoreCase(message.getMessageType().name()))
                    .map(Message::getText).filter(text -> text != null && !text.isBlank())
                    .findFirst().map(text -> text.length() > 20 ? text.substring(0, 20) + "..." : text)
                    .orElse("新对话");
            summaries.add(new FileBasedChatMemory.ConversationSummary(id, title));
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
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("非法会话 ID");
        }
    }
}
