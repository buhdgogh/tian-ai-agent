package com.tianaiagent.infrastructure.adapter.repository.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.conversation.adapter.repository.UserProfileStore;
import com.tianaiagent.infrastructure.adapter.repository.MysqlWorkspaceDatabase;
import com.tianaiagent.infrastructure.dao.mysql.UserProfileMapper;
import com.tianaiagent.infrastructure.dao.po.UserProfileRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "agent.persistence.mysql.enabled", havingValue = "true")
public class MysqlUserProfileStore implements UserProfileStore {
    private final UserProfileMapper profiles;
    private final ObjectMapper mapper;

    public MysqlUserProfileStore(MysqlWorkspaceDatabase database, ObjectMapper mapper) {
        this.profiles = database.mapper(UserProfileMapper.class);
        this.mapper = mapper;
    }

    @Override
    public Optional<Profile> find(String ownerId) {
        return Optional.ofNullable(profiles.selectById(ownerId))
                .map(row -> new Profile(read(row.factsJson()), read(row.preferencesJson())));
    }

    @Override
    public void merge(String ownerId, Profile changes) {
        if (changes.facts().isEmpty() && changes.preferences().isEmpty()) return;
        profiles.merge(new UserProfileRow(ownerId, write(changes.facts()), write(changes.preferences())));
    }

    private Map<String, String> read(String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("读取用户画像失败", e); }
    }

    private String write(Map<String, String> value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("序列化用户画像失败", e); }
    }
}
