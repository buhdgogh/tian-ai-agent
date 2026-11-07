package com.tianaiagent.user;

import com.tianaiagent.persistence.AgentDatabase;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class UserRepository {
    private final AgentDatabase database;
    private final String defaultUsername;
    private final String defaultPassword;
    public UserRepository(AgentDatabase database,
                          @Value("${agent.user.default-username:zhangsan}") String username,
                          @Value("${agent.user.default-password:123456}") String password) {
        this.database = database;
        this.defaultUsername = username;
        this.defaultPassword = password;
    }
    @PostConstruct
    public void initialize() { createIfAbsent(defaultUsername, defaultPassword); }

    public UserView defaultUser() { return find(defaultUsername).orElseThrow(() -> new IllegalStateException("默认用户不存在")); }

    public UserView createIfAbsent(String username, String password) {
        validate(username);
        if (password == null || password.isBlank() || password.length() > 256) throw new IllegalArgumentException("密码不能为空或过长");
        if (find(username).isPresent()) return find(username).orElseThrow();
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                try (var statement = connection.prepareStatement("INSERT INTO agent_user(id,username,password_hash,created_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id")) {
                    statement.setString(1, username);
                    statement.setString(2, username);
                    statement.setString(3, PasswordHash.encode(password));
                    statement.setString(4, now);
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("INSERT INTO agent_user_profile(user_id,display_name,preferred_language,preferences_json,confirmed_facts_json,created_at,updated_at) VALUES(?,?,?,'{}','[]',?,?) ON DUPLICATE KEY UPDATE user_id=user_id")) {
                    statement.setString(1, username); statement.setString(2, username); statement.setString(3, "zh-CN");
                    statement.setString(4, now); statement.setString(5, now); statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception error) { connection.rollback(); throw error; }
        } catch (Exception error) { throw new IllegalStateException("初始化用户失败", error); }
        return find(username).orElseThrow();
    }

    public Optional<UserView> find(String username) {
        validate(username);
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT id,username,created_at,last_login_at FROM agent_user WHERE username=?")) {
            statement.setString(1, username);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new UserView(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))) : Optional.empty();
            }
        } catch (Exception error) { throw new IllegalStateException("读取用户失败", error); }
    }

    public Optional<UserView> authenticate(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_-]{1,128}") || password == null || password.length() > 256) return Optional.empty();
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT password_hash FROM agent_user WHERE username=?")) {
            statement.setString(1, username);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !PasswordHash.matches(password, rows.getString(1))) return Optional.empty();
            }
            return Optional.of(recordLogin(username));
        } catch (Exception error) { throw new IllegalStateException("登录失败", error); }
    }
    public UserView recordLogin(String username) {
        try (var connection = database.connection(); var update = connection.prepareStatement("UPDATE agent_user SET last_login_at=? WHERE username=?")) {
            update.setString(1, Instant.now().toString()); update.setString(2, username); update.executeUpdate();
        } catch (Exception error) { throw new IllegalStateException("保存登录时间失败", error); }
        return find(username).orElseThrow();
    }
    public static void validate(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法用户ID");
    }
    public record UserView(String userId, String username, String createdAt, String lastLoginAt) {}
}
