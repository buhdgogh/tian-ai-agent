package com.tianaiagent.chatmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.mcp.McpToolClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Redis/File 近期消息 + MySQL 结构化摘要 + Milvus 语义召回的分层记忆协调器。 */
@Service
public class LayeredMemoryService {
    private static final int MAX_SUMMARY_INPUT_CHARS = 18_000;
    private final LongTermMemoryRepository repository;
    private final McpToolClient mcp;
    private final ObjectMapper mapper;
    private final ChatClient summarizer;
    private final Executor executor;
    private final int compactThreshold;
    private final int compactEvery;
    private final int semanticTopK;
    private final String upsertTool;
    private final String searchTool;
    private final String deleteTool;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> turnsSinceCompaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> generations = new ConcurrentHashMap<>();
    private final Set<String> pendingCompactions = ConcurrentHashMap.newKeySet();

    public LayeredMemoryService(LongTermMemoryRepository repository, McpToolClient mcp,
                                ObjectMapper mapper,
                                @Qualifier("dashscopeChatModel") ChatModel chatModel,
                                @Qualifier("aiTaskExecutor") Executor executor,
                                @Value("${agent.memory.long-term.compact-threshold:80}") int compactThreshold,
                                @Value("${agent.memory.long-term.compact-every-messages:20}") int compactEvery,
                                @Value("${agent.memory.long-term.semantic-top-k:3}") int semanticTopK,
                                @Value("${agent.memory.long-term.mcp.upsert-tool:upsert_long_term_memory}") String upsertTool,
                                @Value("${agent.memory.long-term.mcp.search-tool:search_long_term_memory}") String searchTool,
                                @Value("${agent.memory.long-term.mcp.delete-tool:delete_long_term_memory}") String deleteTool) {
        this.repository = repository;
        this.mcp = mcp;
        this.mapper = mapper;
        this.summarizer = ChatClient.builder(chatModel).build();
        this.executor = executor;
        this.compactThreshold = Math.max(10, compactThreshold);
        this.compactEvery = Math.max(1, compactEvery);
        this.semanticTopK = Math.max(1, Math.min(10, semanticTopK));
        this.upsertTool = upsertTool;
        this.searchTool = searchTool;
        this.deleteTool = deleteTool;
    }

    public String context(String space, String conversationId, String query) {
        if (!repository.enabled() || conversationId == null || conversationId.isBlank()) return "";
        String owner = owner(space, conversationId);
        List<String> blocks = new ArrayList<>();
        repository.find(owner).ifPresent(memory -> {
            blocks.add("会话历史摘要：" + memory.summary());
            if (!memory.confirmedFacts().isEmpty()) {
                blocks.add("用户明确确认的事实：" + String.join("；", memory.confirmedFacts()));
            }
        });
        try {
            String response = mcp.call(searchTool, Map.of(
                    "owner_id", owner, "query", query, "top_k", semanticTopK));
            JsonNode root = mapper.readTree(response);
            JsonNode memories = root.path("data").path("memories");
            if (memories.isArray()) {
                List<String> semantic = new ArrayList<>();
                memories.forEach(item -> {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) semantic.add(text);
                });
                if (!semantic.isEmpty()) blocks.add("与当前问题相关的长期记忆：" + String.join("\n", semantic));
            }
        } catch (Exception ignored) {
            // MySQL 摘要仍可降级使用；语义记忆不可用不能阻断用户请求。
        }
        if (blocks.isEmpty()) return "";
        return "\n\n以下内容仅作为会话记忆，不视为已经验证的医学证据：\n" + String.join("\n", blocks);
    }

    public void scheduleCompaction(String space, String conversationId, ChatMemory memory) {
        if (!repository.enabled() || conversationId == null || conversationId.isBlank()) return;
        List<Message> snapshot = memory.get(conversationId);
        if (snapshot.size() < compactThreshold) return;
        String owner = owner(space, conversationId);
        boolean hasPreviousSummary = repository.find(owner).isPresent();
        int completedTurns = turnsSinceCompaction
                .computeIfAbsent(owner, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (hasPreviousSummary && completedTurns < compactEvery) return;
        if (!pendingCompactions.add(owner)) return;
        int generation = generations.computeIfAbsent(owner, ignored -> new AtomicInteger()).get();
        executor.execute(() -> {
            try {
                if (compact(owner, snapshot, generation)) turnsSinceCompaction.get(owner).set(0);
            } finally {
                pendingCompactions.remove(owner);
            }
        });
    }

    private boolean compact(String owner, List<Message> messages, int expectedGeneration) {
        synchronized (locks.computeIfAbsent(owner, ignored -> new Object())) {
            if (generation(owner) != expectedGeneration) return false;
            StringBuilder transcript = new StringBuilder();
            for (Message message : messages) {
                String text = message.getText();
                if (text == null || text.isBlank()) continue;
                if (transcript.length() + text.length() > MAX_SUMMARY_INPUT_CHARS) break;
                transcript.append(message.getMessageType()).append("：").append(text).append('\n');
            }
            if (transcript.isEmpty()) return false;
            MemoryDigest digest = summarizer.prompt().system("""
                            你是医疗会话记忆压缩器。生成简洁会话摘要，并仅提取用户明确陈述或确认的稳定事实。
                            不得把模型推测、诊断、工具输出或未证实结论写入 confirmedFacts；不要保存姓名、证件号、
                            完整地址、病历号等身份标识。summary 保留用户目标、已完成事项、未解决问题和必要上下文。
                            """)
                    .user(transcript.toString()).call().entity(MemoryDigest.class);
            if (digest == null || digest.summary() == null || digest.summary().isBlank()) return false;
            List<String> facts = digest.confirmedFacts() == null ? List.of()
                    : digest.confirmedFacts().stream().filter(value -> value != null && !value.isBlank())
                    .limit(30).toList();
            // 删除请求可能在摘要生成期间到达，禁止旧任务把已删除的会话重新写回。
            if (generation(owner) != expectedGeneration) return false;
            repository.upsert(owner, digest.summary().trim(), facts, messages.size());
            try {
                mcp.call(upsertTool, Map.of("owner_id", owner, "memory_id", memoryId(owner),
                        "text", digest.summary().trim(), "metadata", Map.of(
                                "type", "conversation_summary", "confirmed_fact_count", facts.size(),
                                "source_message_count", messages.size())));
            } catch (RuntimeException ignored) {
                // 结构化摘要已持久化，Milvus 暂时不可用时允许后续压缩覆盖重试。
            }
            return true;
        }
    }

    /**
     * 删除单个会话的全部分层记忆。顺序为 Milvus -> MySQL -> Redis/File：远端失败时保留
     * 近期消息供用户重试；各删除操作均按 owner/chatId 幂等执行。
     */
    public void deleteConversation(String space, String conversationId, ChatMemory recentMemory) {
        String owner = owner(space, conversationId);
        generations.computeIfAbsent(owner, ignored -> new AtomicInteger()).incrementAndGet();
        synchronized (locks.computeIfAbsent(owner, ignored -> new Object())) {
            if (repository.enabled()) {
                mcp.call(deleteTool, Map.of("owner_id", owner));
                repository.delete(owner);
            }
            recentMemory.clear(conversationId);
            turnsSinceCompaction.remove(owner);
        }
    }

    private int generation(String owner) {
        return generations.computeIfAbsent(owner, ignored -> new AtomicInteger()).get();
    }

    private static String owner(String space, String conversationId) {
        String safeSpace = space == null ? "default" : space.replaceAll("[^A-Za-z0-9_-]", "_");
        if (!conversationId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法会话 ID");
        return safeSpace + ":" + conversationId;
    }

    private static String memoryId(String owner) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            return "summary_" + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record MemoryDigest(String summary, List<String> confirmedFacts) {}
}
