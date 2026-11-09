package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.service.McpServerSettingsService;
import com.tianaiagent.domain.agent.service.ToolGateway;
import com.tianaiagent.infrastructure.adapter.repository.MysqlWorkspaceDatabase;
import com.tianaiagent.infrastructure.adapter.repository.agent.MysqlMcpServerStore;
import com.tianaiagent.infrastructure.dao.mysql.McpServerMapper;
import com.tianaiagent.infrastructure.gateway.mcp.StreamableMcpToolClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.persistence.mysql.enabled", havingValue = "true")
@EnableConfigurationProperties(PersonalMcpConfiguration.Settings.class)
class PersonalMcpConfiguration {
    @Bean
    McpServerSettingsService mcpServerSettingsService(MysqlWorkspaceDatabase database, ObjectMapper mapper,
                                                       ToolGateway gateway, Settings settings) {
        return new McpServerSettingsService(new MysqlMcpServerStore(database.mapper(McpServerMapper.class), mapper),
                new StreamableMcpToolClient(mapper), gateway, settings.allowedOrigins());
    }

    @ConfigurationProperties("agent.mcp")
    record Settings(List<String> allowedOrigins) {
        Settings { allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins); }
    }
}
