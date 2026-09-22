package com.tianaiagent.chatmemory;

import com.tianaiagent.constant.FileConstant;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 统一管理不同业务空间的会话记忆，避免 Service 各自创建存储实例。
 * 存储实现支持并发锁、原子写入、会话 ID 校验和消息窗口限制。
 */
@Service
public class EnterpriseMemoryService {
    public static final String MEDICAL_ADVISOR = "medical-advisor";
    public static final String MEDICAL_AGENT = "medical-agent";
    public static final String MEDICAL_RAG = "medical-rag";
    private final Map<String, ChatMemory> spaces;

    /** 根据配置的 backend 参数，决定使用 Redis/本地文件来存储 Agent 的记忆，为三个不同的 Agent 空间分别创建独立的记忆存储实例。 */
    public EnterpriseMemoryService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                   @Value("${agent.memory.backend:file}") String backend,
                                   @Value("${agent.memory.max-messages:200}") int maxMessages,
                                   @Value("${agent.memory.ttl-days:30}") int ttlDays) {
        if ("redis".equalsIgnoreCase(backend)) {
            this.spaces = Map.of(
                    MEDICAL_ADVISOR, new RedisChatMemory(redis, objectMapper, MEDICAL_ADVISOR, maxMessages, Duration.ofDays(ttlDays)),
                    MEDICAL_AGENT, new RedisChatMemory(redis, objectMapper, MEDICAL_AGENT, maxMessages, Duration.ofDays(ttlDays)),
                    MEDICAL_RAG, new RedisChatMemory(redis, objectMapper, MEDICAL_RAG, maxMessages, Duration.ofDays(ttlDays)));
        } else {
            this.spaces = Map.of(
                    MEDICAL_ADVISOR, new FileBasedChatMemory(Path.of(FileConstant.FILE_SAVE_DIR, "medical-advisor-memory").toString(), maxMessages),
                    MEDICAL_AGENT, new FileBasedChatMemory(Path.of(FileConstant.FILE_SAVE_DIR, "medical-agent-memory").toString(), maxMessages),
                    MEDICAL_RAG, new FileBasedChatMemory(Path.of(FileConstant.FILE_SAVE_DIR, "medical-rag-memory").toString(), maxMessages));
        }
    }

    /** 统一策略模式得到对话记忆 */
    public ChatMemory chatMemory(String space) {
        ChatMemory memory = spaces.get(space);
        if (memory == null) throw new IllegalArgumentException("未知记忆空间: " + space);
        return memory;
    }

    public List<FileBasedChatMemory.ConversationSummary> listConversations(String space) {
        ChatMemory memory = chatMemory(space);
        if (memory instanceof FileBasedChatMemory file) return file.listConversations();
        if (memory instanceof RedisChatMemory redis) return redis.listConversations();
        return List.of();
    }

    public List<String> listConversationIds(String space) {
        ChatMemory memory = chatMemory(space);
        if (memory instanceof FileBasedChatMemory file) return file.listConversationIds();
        if (memory instanceof RedisChatMemory redis) return redis.listConversationIds();
        return List.of();
    }
}
