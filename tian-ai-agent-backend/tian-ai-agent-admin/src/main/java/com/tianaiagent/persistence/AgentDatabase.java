package com.tianaiagent.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Shared JDBC boundary. Database failures never silently fall back to volatile storage. */
@Component
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class AgentDatabase {
    private final String url;
    private final String username;
    private final String password;

    public AgentDatabase(@Value("${agent.persistence.mysql.jdbc-url}") String url,
                         @Value("${agent.persistence.mysql.username}") String username,
                         @Value("${agent.persistence.mysql.password}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @PostConstruct
    public void initialize() {
        try (var input = new ClassPathResource("db/agent-schema.sql").getInputStream();
             Connection connection = connection(); var statement = connection.createStatement()) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        } catch (Exception error) {
            throw new IllegalStateException("初始化数据库表失败，请检查 application.yml 中 agent.persistence.mysql 配置", error);
        }
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
