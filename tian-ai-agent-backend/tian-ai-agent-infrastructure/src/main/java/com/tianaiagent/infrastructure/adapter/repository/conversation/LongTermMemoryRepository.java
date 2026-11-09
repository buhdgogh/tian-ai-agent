package com.tianaiagent.infrastructure.adapter.repository.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.conversation.adapter.repository.LongTermMemoryStore;
import com.tianaiagent.infrastructure.adapter.repository.MysqlWorkspaceDatabase;
import com.tianaiagent.infrastructure.dao.mysql.LegacyMemoryMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 读取和清理旧版 MySQL 摘要，新的语义记忆由 Milvus 保存。 */
@Repository
@ConditionalOnProperty(name = "agent.persistence.mysql.enabled", havingValue = "true")
public class LongTermMemoryRepository implements LongTermMemoryStore {
    private final ObjectMapper mapper;
    private final LegacyMemoryMapper memories;

    public LongTermMemoryRepository(MysqlWorkspaceDatabase database, ObjectMapper mapper) {
        this.mapper = mapper;
        this.memories = database.mapper(LegacyMemoryMapper.class);
    }

    @Override
    public Optional<StructuredMemory> find(String ownerId) {
        return Optional.ofNullable(memories.selectById(ownerId)).map(row ->
                new StructuredMemory(ownerId, row.summaryText(), readFacts(row.factsJson()),
                        row.sourceMessageCount(), row.updatedAt()));
    }

    @Override
    public int delete(String ownerId) {
        return memories.deleteById(ownerId);
    }

    private List<String> readFacts(String json) {
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("读取旧版长期记忆失败", e); }
    }
}
