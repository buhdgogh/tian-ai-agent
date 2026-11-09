package com.tianaiagent.infrastructure.adapter.repository.conversation;

import com.tianaiagent.infrastructure.dao.mysql.ConversationMapper;

import com.tianaiagent.domain.conversation.adapter.port.ConversationMemoryRegistry;
import com.tianaiagent.types.storage.FileConstant;
import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import com.tianaiagent.infrastructure.adapter.repository.MysqlWorkspaceDatabase;
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
public class EnterpriseMemoryService implements ConversationMemoryRegistry {
    private static final Logger log = LoggerFactory.getLogger(EnterpriseMemoryService.class);
    private final Map<String, ChatMemory> spaces;

    /** 根据配置的 backend 参数，决定使用 Redis/本地文件来存储 Agent 的记忆，为三个不同的 Agent 空间分别创建独立的记忆存储实例。 */
    public EnterpriseMemoryService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                   @Value("${agent.memory.backend:file}") String backend,
                                   @Value("${agent.memory.max-messages:200}") int maxMessages,
                                   @Value("${agent.memory.ttl-days:30}") int ttlDays,
                                   ObjectProvider<MysqlWorkspaceDatabase> mysql,
                                   @Value("${agent.persistence.mysql.import-legacy-history:true}") boolean importHistory) {
        Map<String, ChatMemory> existing;
        if ("redis".equalsIgnoreCase(backend)) {
            existing = Map.of(
                    MEDICAL_ADVISOR, new RedisChatMemory(redis, objectMapper, MEDICAL_ADVISOR, maxMessages, Duration.ofDays(ttlDays)),
                    MEDICAL_AGENT, new RedisChatMemory(redis, objectMapper, MEDICAL_AGENT, maxMessages, Duration.ofDays(ttlDays)),
                    MEDICAL_RAG, new RedisChatMemory(redis, objectMapper, MEDICAL_RAG, maxMessages, Duration.ofDays(ttlDays)));
        } else {
            existing = Map.of(
                    MEDICAL_ADVISOR, new FileBasedChatMemory(Path.of(FileConstant.FILE_SAVE_DIR, "medical-advisor-memory").toString(), maxMessages),
                    MEDICAL_AGENT, new FileBasedChatMemory(Path.of(FileConstant.FILE_SAVE_DIR, "medical-agent-memory").toString(), maxMessages),
                    MEDICAL_RAG, new FileBasedChatMemory(Path.of(FileConstant.FILE_SAVE_DIR, "medical-rag-memory").toString(), maxMessages));
        }
        MysqlWorkspaceDatabase database = mysql.getIfAvailable();
        if (database == null) {
            this.spaces = existing;
            log.warn("会话存储：{}。MySQL 持久化已显式关闭", backend);
        } else {
            Map<String, ChatMemory> persisted = new java.util.HashMap<>();
            existing.forEach((space, previous) -> {
                var memory = new MysqlChatMemory(database.mapper(ConversationMapper.class), database.transactions(), objectMapper,
                        space, maxMessages, importHistory ? previous : null,
                        importHistory ? () -> listLegacy(previous) : List::of);
                if (importHistory) memory.importLegacyHistory();
                persisted.put(space, memory);
            });
            this.spaces = Map.copyOf(persisted);
            log.info("会话存储：MySQL。完整历史写入 agent_conversation 和 agent_conversation_message");
        }
    }

    /** 统一策略模式得到对话记忆 */
    public ChatMemory chatMemory(String space) {
        ChatMemory memory = spaces.get(space);
        if (memory == null) throw new IllegalArgumentException("未知记忆空间: " + space);
        return memory;
    }

    @Override
    public void touch(String space, String conversationId) {
        ChatMemory memory = chatMemory(space);
        if (memory instanceof MysqlChatMemory mysql) mysql.touch(conversationId);
        else touchLegacy(memory, conversationId);
    }

    static void touchLegacy(ChatMemory memory, String conversationId) {
        if (memory instanceof FileBasedChatMemory file) file.touch(conversationId);
        if (memory instanceof RedisChatMemory redis) redis.touch(conversationId);
    }

    public List<ConversationSummary> listConversations(String space) {
        ChatMemory memory = chatMemory(space);
        return memory instanceof MysqlChatMemory mysql ? mysql.listConversations() : listLegacy(memory);
    }

    @Override
    public List<ConversationSummary> listConversations(String space, String ownerId, String scopeId) {
        ChatMemory memory = chatMemory(space);
        return memory instanceof MysqlChatMemory mysql ? mysql.listConversations(ownerId, scopeId) : listLegacy(memory);
    }

    @Override
    public List<Message> history(String space, String conversationId) {
        ChatMemory memory = chatMemory(space);
        return memory instanceof MysqlChatMemory mysql ? mysql.history(conversationId) : memory.get(conversationId);
    }

    private static List<ConversationSummary> listLegacy(ChatMemory memory) {
        if (memory instanceof FileBasedChatMemory file) return file.listConversations();
        if (memory instanceof RedisChatMemory redis) return redis.listConversations();
        return List.of();
    }

    public List<String> listConversationIds(String space) {
        ChatMemory memory = chatMemory(space);
        if (memory instanceof FileBasedChatMemory file) return file.listConversationIds();
        if (memory instanceof RedisChatMemory redis) return redis.listConversationIds();
        if (memory instanceof MysqlChatMemory mysql) return mysql.listConversations().stream().map(ConversationSummary::id).toList();
        return List.of();
    }
}
