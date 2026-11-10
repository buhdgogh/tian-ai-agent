package com.tianaiagent.infrastructure.adapter.repository;

import com.tianaiagent.infrastructure.dao.MybatisSessions;
import com.tianaiagent.infrastructure.dao.mysql.AgentRunMapper;
import com.tianaiagent.infrastructure.dao.mysql.ConversationMapper;
import com.tianaiagent.infrastructure.dao.mysql.LegacyMemoryMapper;
import com.tianaiagent.infrastructure.dao.mysql.McpServerMapper;
import com.tianaiagent.infrastructure.dao.mysql.ModelProviderMapper;
import com.tianaiagent.infrastructure.dao.mysql.UserProfileMapper;
import com.tianaiagent.infrastructure.dao.mysql.ToolCredentialMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/** 工作台业务数据库，与内嵌知识库数据源分别装配。 */
public class MysqlWorkspaceDatabase implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final SqlSessionTemplate sessions;
    private final TransactionTemplate transactions;

    public MysqlWorkspaceDatabase(String url, String username, String password, Resource... schema) {
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            throw new IllegalArgumentException("agent.persistence.mysql.jdbc-url 必须使用 jdbc:mysql: URL");
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("workspace-mysql");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        dataSource = new HikariDataSource(config);
        try {
            new ResourceDatabasePopulator(schema).execute(dataSource);
            sessions = MybatisSessions.create(dataSource, ConversationMapper.class, ModelProviderMapper.class,
                    UserProfileMapper.class, LegacyMemoryMapper.class, AgentRunMapper.class, McpServerMapper.class,
                    ToolCredentialMapper.class);
        } catch (RuntimeException | Error e) {
            dataSource.close();
            throw e;
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public <T> T mapper(Class<T> type) { return sessions.getMapper(type); }
    public DataSource dataSource() { return dataSource; }
    public TransactionTemplate transactions() { return transactions; }
    @Override public void close() { dataSource.close(); }
}
