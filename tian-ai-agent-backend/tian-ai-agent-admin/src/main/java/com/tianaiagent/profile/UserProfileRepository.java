package com.tianaiagent.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** User-confirmed profile, independent of per-conversation summaries. */
@Repository
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class UserProfileRepository {
    private final AgentDatabase database;
    private final ObjectMapper mapper;

    public UserProfileRepository(AgentDatabase database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    public Optional<UserProfile> find(String userId) {
        validateUserId(userId);
        try (var connection = database.connection();
             var statement = connection.prepareStatement("SELECT * FROM agent_user_profile WHERE user_id=?")) {
            statement.setString(1, userId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new UserProfile(userId, rows.getString("display_name"), rows.getString("preferred_language"),
                        mapper.readValue(rows.getString("preferences_json"), new TypeReference<>() {}),
                        mapper.readValue(rows.getString("confirmed_facts_json"), new TypeReference<>() {}),
                        rows.getString("created_at"), rows.getString("updated_at")));
            }
        } catch (Exception error) { throw new IllegalStateException("读取MySQL用户画像失败", error); }
    }

    public UserProfile replace(String userId, ProfileUpdate update) {
        validateUserId(userId);
        if (update == null) throw new IllegalArgumentException("画像内容不能为空");
        validateText(update.displayName(), 100);
        validateText(update.preferredLanguage(), 40);
        Map<String, String> preferences = update.preferences() == null ? Map.of() : update.preferences();
        List<String> facts = update.confirmedFacts() == null ? List.of() : update.confirmedFacts();
        if (preferences.size() > 30 || facts.size() > 50) throw new IllegalArgumentException("画像偏好最多30项，确认事实最多50项");
        preferences.forEach((key, value) -> { validateRequiredText(key, 80); validateRequiredText(value, 500); });
        facts.forEach(fact -> validateRequiredText(fact, 500));
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO agent_user_profile(user_id,display_name,preferred_language,preferences_json,
                confirmed_facts_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),preferred_language=VALUES(preferred_language),
                preferences_json=VALUES(preferences_json),confirmed_facts_json=VALUES(confirmed_facts_json),updated_at=VALUES(updated_at)
                """;
        try (var connection = database.connection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, update.displayName());
            statement.setString(3, update.preferredLanguage());
            statement.setString(4, mapper.writeValueAsString(preferences));
            statement.setString(5, mapper.writeValueAsString(facts));
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
        } catch (Exception error) { throw new IllegalStateException("保存MySQL用户画像失败", error); }
        return find(userId).orElseThrow();
    }

    public void delete(String userId) {
        validateUserId(userId);
        try (var connection = database.connection();
             var statement = connection.prepareStatement("DELETE FROM agent_user_profile WHERE user_id=?")) {
            statement.setString(1, userId);
            statement.executeUpdate();
        } catch (Exception error) { throw new IllegalStateException("删除MySQL用户画像失败", error); }
    }

    public static void validateUserId(String userId) {
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法用户 ID");
    }
    private static void validateText(String text, int limit) {
        if (text != null && text.length() > limit) throw new IllegalArgumentException("画像字段超过长度限制: " + limit);
    }
    private static void validateRequiredText(String text, int limit) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("画像条目不能为空");
        validateText(text, limit);
    }

    public record ProfileUpdate(String displayName, String preferredLanguage,
                                Map<String, String> preferences, List<String> confirmedFacts) {}
    public record UserProfile(String userId, String displayName, String preferredLanguage,
                              Map<String, String> preferences, List<String> confirmedFacts,
                              String createdAt, String updatedAt) {}
}
