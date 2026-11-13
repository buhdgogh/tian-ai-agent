package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.infrastructure.adapter.repository.knowledge.MybatisKnowledgeRepository;
import com.tianaiagent.infrastructure.dao.MybatisSessions;
import com.tianaiagent.infrastructure.dao.knowledge.KnowledgeMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.type.ByteArrayTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
@ConditionalOnExpression("${doc-mcp.enabled:true} || (${agent.memory.long-term.enabled:false} "
        + "&& !" + ConversationMemoryConfiguration.MILVUS_SELECTED + ")")
class KnowledgeSqliteConfiguration {
    @Bean
    HikariDataSource dataSource(DataSourceProperties properties) throws IOException {
        String url = properties.getUrl();
        String prefix = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefix) || url.length() == prefix.length()) {
            throw new IllegalArgumentException("知识库数据源必须配置 SQLite 文件或内存地址");
        }
        String database = url.substring(prefix.length());
        if (!":memory:".equals(database) && !database.startsWith("file:")) {
            Files.createDirectories(Path.of(database).toAbsolutePath().getParent());
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName("knowledge-sqlite");
        config.setMaximumPoolSize(1);
        config.setMaxLifetime(0);
        config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
        return new HikariDataSource(config);
    }

    @Bean
    MybatisKnowledgeRepository knowledgeRepository(DataSource dataSource, ObjectMapper mapper) {
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite/knowledge.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        if (!jdbc.queryForList("SELECT name FROM pragma_table_info('knowledge_spaces')", String.class)
                .contains("embedding_binding")) {
            jdbc.execute("ALTER TABLE knowledge_spaces ADD COLUMN embedding_binding TEXT");
        }
        // SQLite JDBC supports getBytes for BLOB values, rather than java.sql.Blob.
        var sessions = MybatisSessions.create(dataSource, configuration -> configuration.getTypeHandlerRegistry()
                .register(byte[].class, JdbcType.BLOB, ByteArrayTypeHandler.class), KnowledgeMapper.class);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new MybatisKnowledgeRepository(sessions.getMapper(KnowledgeMapper.class), transactions, mapper);
    }
}
