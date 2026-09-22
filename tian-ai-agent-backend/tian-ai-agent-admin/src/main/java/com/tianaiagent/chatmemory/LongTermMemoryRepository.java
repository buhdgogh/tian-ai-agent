package com.tianaiagent.chatmemory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 可选 MySQL 长期结构化记忆；关闭时不会建立数据库连接。 */
@Repository
public class LongTermMemoryRepository {
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public LongTermMemoryRepository(ObjectMapper mapper,
                                    @Value("${agent.memory.long-term.enabled:false}") boolean enabled,
                                    @Value("${agent.memory.long-term.mysql.jdbc-url:}") String jdbcUrl,
                                    @Value("${agent.memory.long-term.mysql.username:}") String username,
                                    @Value("${agent.memory.long-term.mysql.password:}") String password) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    @PostConstruct
    void initialize() {
        if (!enabled) return;
        if (jdbcUrl.isBlank()) throw new IllegalStateException(
                "启用长期记忆时必须配置 AGENT_MEMORY_MYSQL_URL");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS agent_long_term_memory(
                      owner_id VARCHAR(300) PRIMARY KEY,
                      summary_text LONGTEXT NOT NULL,
                      facts_json LONGTEXT NOT NULL,
                      source_message_count INT NOT NULL,
                      updated_at VARCHAR(40) NOT NULL)
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("初始化MySQL长期记忆失败: " + e.getMessage(), e);
        }
    }

    public boolean enabled() { return enabled; }

    public Optional<StructuredMemory> find(String ownerId) {
        if (!enabled) return Optional.empty();
        String sql = "SELECT summary_text,facts_json,source_message_count,updated_at "
                + "FROM agent_long_term_memory WHERE owner_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new StructuredMemory(ownerId, result.getString(1),
                        mapper.readValue(result.getString(2), new TypeReference<List<String>>() {}),
                        result.getInt(3), result.getString(4)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取MySQL长期记忆失败: " + e.getMessage(), e);
        }
    }

    public void upsert(String ownerId, String summary, List<String> facts, int sourceMessageCount) {
        if (!enabled) return;
        String sql = "INSERT INTO agent_long_term_memory(owner_id,summary_text,facts_json,source_message_count,updated_at) "
                + "VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE summary_text=VALUES(summary_text),"
                + "facts_json=VALUES(facts_json),source_message_count=VALUES(source_message_count),"
                + "updated_at=VALUES(updated_at)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, summary);
            statement.setString(3, mapper.writeValueAsString(facts == null ? List.of() : facts));
            statement.setInt(4, sourceMessageCount);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入MySQL长期记忆失败: " + e.getMessage(), e);
        }
    }

    /** 删除指定会话的长期摘要；用户级画像使用不同 owner 时不会受到影响。 */
    public int delete(String ownerId) {
        if (!enabled) return 0;
        String sql = "DELETE FROM agent_long_term_memory WHERE owner_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("删除MySQL长期记忆失败: " + e.getMessage(), e);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    public record StructuredMemory(String ownerId, String summary, List<String> confirmedFacts,
                                   int sourceMessageCount, String updatedAt) {}
}
