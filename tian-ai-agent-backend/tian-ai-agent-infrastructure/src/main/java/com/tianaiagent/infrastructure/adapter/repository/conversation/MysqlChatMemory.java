package com.tianaiagent.infrastructure.adapter.repository.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import com.tianaiagent.domain.conversation.service.ConversationScope;
import com.tianaiagent.infrastructure.dao.mysql.ConversationMapper;
import com.tianaiagent.infrastructure.dao.po.ConversationRows;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

final class MysqlChatMemory implements ChatMemory {
    private final ConversationMapper conversations;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final String space;
    private final int maxMessages;
    private final ChatMemory legacy;
    private final Supplier<List<ConversationSummary>> legacyConversations;

    MysqlChatMemory(ConversationMapper conversations, TransactionTemplate transactions, ObjectMapper mapper, String space,
                    int maxMessages, ChatMemory legacy, Supplier<List<ConversationSummary>> legacyConversations) {
        this.conversations = conversations;
        this.transactions = transactions;
        this.mapper = mapper;
        this.space = space;
        this.maxMessages = Math.max(1, maxMessages);
        this.legacy = legacy;
        this.legacyConversations = legacyConversations;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        var scope = ConversationScope.storageScope(conversationId);
        if (messages.isEmpty()) return;
        transactions.executeWithoutResult(status -> {
            long id = lockSession(conversationId, scope);
            var previous = new ArrayList<Message>();
            if (legacy != null && !hasMessages(id)) previous.addAll(legacy.get(conversationId));
            previous.addAll(messages);
            for (Message message : previous) insert(id, message);
            conversations.updateAfterAppend(id, title(previous));
        });
    }

    void importLegacyHistory() {
        for (var summary : legacyConversations.get()) {
            var scope = ConversationScope.storageScope(summary.id());
            List<Message> messages = legacy.get(summary.id());
            if (messages.isEmpty()) continue;
            transactions.executeWithoutResult(status -> {
                long id = lockSession(summary.id(), scope);
                if (hasMessages(id)) return;
                for (Message message : messages) insert(id, message);
                var updatedAt = LocalDateTime.ofInstant(summary.updatedAt(), ZoneOffset.UTC);
                conversations.updateAfterImport(id, title(messages), updatedAt);
            });
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        ConversationScope.storageScope(conversationId);
        var rows = new ArrayList<>(conversations.messages(space, conversationId, maxMessages).stream()
                .map(this::message).toList());
        if (rows.isEmpty() && legacy != null) return legacy.get(conversationId);
        Collections.reverse(rows);
        return rows;
    }

    List<Message> history(String conversationId) {
        ConversationScope.storageScope(conversationId);
        var rows = conversations.messages(space, conversationId, null).stream().map(this::message).toList();
        return rows.isEmpty() && legacy != null ? legacy.get(conversationId) : rows;
    }

    @Override
    public void clear(String conversationId) {
        var scope = ConversationScope.storageScope(conversationId);
        transactions.executeWithoutResult(status -> {
            long id = lockSession(conversationId, scope);
            if (legacy != null) legacy.clear(conversationId);
            conversations.delete(id);
        });
    }

    List<ConversationSummary> listConversations() {
        return listConversations(null, null);
    }

    List<ConversationSummary> listConversations(String ownerId, String scopeId) {
        var summaries = new LinkedHashMap<String, ConversationSummary>();
        conversations.list(space, ownerId, scopeId == null ? "" : scopeId).stream()
                .map(row -> new ConversationSummary(row.storageId(), row.title().isBlank() ? "新对话" : row.title(),
                        row.updatedAt().toInstant(ZoneOffset.UTC)))
                .forEach(summary -> summaries.put(summary.id(), summary));
        for (var summary : legacyConversations.get()) {
            if (ownerId == null || ConversationScope.visibleId(ownerId, summary.id(), scopeId).isPresent()) {
                summaries.putIfAbsent(summary.id(), summary);
            }
        }
        return List.copyOf(summaries.values());
    }

    void touch(String conversationId) {
        ConversationScope.storageScope(conversationId);
        int updated = conversations.touch(space, conversationId);
        if (updated == 0 && legacy != null) EnterpriseMemoryService.touchLegacy(legacy, conversationId);
    }

    private long lockSession(String conversationId, ConversationScope.StorageScope scope) {
        conversations.initialize(scope.ownerId(), space, scope.scopeId(), conversationId);
        return conversations.lock(space, conversationId);
    }

    private boolean hasMessages(long id) {
        return conversations.firstMessageId(id) != null;
    }

    private void insert(long id, Message message) {
        var dto = ChatMessageMapper.toDto(message);
        conversations.insertMessage(id, new ConversationRows.Message(dto.getRole(), dto.getContent(),
                json(dto.getToolCalls()), json(dto.getToolResponses())));
    }

    private Message message(ConversationRows.Message row) {
        try {
            String calls = row.toolCalls();
            String responses = row.toolResponses();
            return ChatMessageMapper.fromDto(new StructuredChatMessageDTO(row.role(), row.content(),
                    calls == null ? null : mapper.readValue(calls, new TypeReference<>() {}),
                    responses == null ? null : mapper.readValue(responses, new TypeReference<>() {})));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("读取会话工具消息失败");
        }
    }

    private String json(List<?> values) {
        if (values.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存会话工具消息失败");
        }
    }

    private static String title(List<Message> messages) {
        return messages.stream().filter(message -> message.getMessageType() == MessageType.USER)
                .map(Message::getText).filter(text -> text != null && !text.isBlank())
                .findFirst().map(text -> text.codePointCount(0, text.length()) <= 20 ? text
                        : text.substring(0, text.offsetByCodePoints(0, 20)) + "...").orElse("");
    }
}
