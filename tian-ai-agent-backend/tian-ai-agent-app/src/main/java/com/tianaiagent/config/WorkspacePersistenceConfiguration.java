package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.repository.AgentPresetStore;
import com.tianaiagent.domain.agent.adapter.repository.AgentRunRepository;
import com.tianaiagent.domain.agent.adapter.repository.ModelProviderStore;
import com.tianaiagent.domain.agent.adapter.repository.ToolCredentialStore;
import com.tianaiagent.infrastructure.adapter.repository.MysqlWorkspaceDatabase;
import com.tianaiagent.infrastructure.adapter.repository.agent.FileAgentPresetStore;
import com.tianaiagent.infrastructure.adapter.repository.agent.FileAgentRunRepository;
import com.tianaiagent.infrastructure.adapter.repository.agent.FileModelProviderStore;
import com.tianaiagent.infrastructure.adapter.repository.agent.EncryptedToolCredentialStore;
import com.tianaiagent.infrastructure.adapter.repository.agent.MysqlAgentPresetStore;
import com.tianaiagent.infrastructure.adapter.repository.agent.MysqlAgentRunRepository;
import com.tianaiagent.infrastructure.adapter.repository.agent.MysqlModelProviderStore;
import com.tianaiagent.infrastructure.dao.mysql.AgentRunMapper;
import com.tianaiagent.infrastructure.dao.mysql.ModelProviderMapper;
import com.tianaiagent.infrastructure.dao.mysql.ToolCredentialMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
class WorkspacePersistenceConfiguration {
    @Bean
    ToolCredentialStore toolCredentialStore(ObjectProvider<MysqlWorkspaceDatabase> mysql) {
        var database = mysql.getIfAvailable();
        return new EncryptedToolCredentialStore(database == null ? null : database.mapper(ToolCredentialMapper.class));
    }

    @Bean
    AgentPresetStore agentPresetStore(ObjectMapper mapper, ObjectProvider<MysqlWorkspaceDatabase> mysql) {
        var database = mysql.getIfAvailable();
        return database == null ? new FileAgentPresetStore(mapper) : new MysqlAgentPresetStore(database.dataSource());
    }

    @Bean
    @ConditionalOnProperty(name = "agent.persistence.mysql.enabled", havingValue = "true")
    MysqlWorkspaceDatabase mysqlWorkspaceDatabase(
            @Value("${agent.persistence.mysql.jdbc-url:}") String url,
            @Value("${agent.persistence.mysql.username:}") String username,
            @Value("${agent.persistence.mysql.password:${MYSQL_PASSWORD:}}") String password) {
        return new MysqlWorkspaceDatabase(url, username, password,
                schema("tian_ai_agent.sql"));
    }

    private static Resource schema(String filename) {
        Resource packaged = new ClassPathResource("db/mysql/" + filename);
        if (packaged.exists()) return packaged;

        // IDEA 直接运行时可从项目根目录或 app 模块的工作目录读取建表脚本。
        Resource local = new FileSystemResource("docs/mysql/" + filename);
        if (local.exists()) return local;
        Resource fromApp = new FileSystemResource("../docs/mysql/" + filename);
        if (fromApp.exists()) return fromApp;
        throw new IllegalStateException("找不到 MySQL 建表脚本: " + filename);
    }

    @Bean
    AgentRunRepository agentRunRepository(ObjectMapper mapper, ObjectProvider<MysqlWorkspaceDatabase> mysql) {
        var previous = new FileAgentRunRepository(mapper);
        var database = mysql.getIfAvailable();
        if (database == null) return previous;
        var repository = new MysqlAgentRunRepository(database.mapper(AgentRunMapper.class), database.transactions());
        repository.importLegacy(previous);
        return repository;
    }

    @Bean
    ModelProviderStore modelProviderStore(ObjectMapper mapper, ObjectProvider<MysqlWorkspaceDatabase> mysql) {
        var previous = new FileModelProviderStore(mapper);
        var database = mysql.getIfAvailable();
        if (database == null) return previous;
        var store = new MysqlModelProviderStore(database.mapper(ModelProviderMapper.class), database.transactions());
        store.importLegacy(previous);
        return store;
    }
}
