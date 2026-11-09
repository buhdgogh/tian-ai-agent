package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.repository.McpServerStore;
import com.tianaiagent.domain.agent.model.valobj.McpServer;
import com.tianaiagent.infrastructure.dao.mysql.McpServerMapper;
import com.tianaiagent.infrastructure.dao.po.McpServerRow;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public class MysqlMcpServerStore implements McpServerStore {
    private final McpServerMapper rows;
    private final ObjectMapper mapper;
    private final ModelSettingsCipher cipher = new ModelSettingsCipher();

    public MysqlMcpServerStore(McpServerMapper rows, ObjectMapper mapper) {
        this.rows = rows;
        this.mapper = mapper;
    }

    @Override
    public List<McpServer> list(String ownerId) {
        return rows.list(ownerId).stream().map(this::read).toList();
    }

    @Override
    public Optional<McpServer> find(String ownerId, String id) {
        return Optional.ofNullable(rows.find(ownerId, id)).map(this::read);
    }

    @Override
    public void save(String ownerId, McpServer server) {
        try {
            byte[] token = server.token() == null ? null : cipher.seal(server.token().getBytes(StandardCharsets.UTF_8),
                    context(ownerId, server.id(), server.url()));
            rows.save(new McpServerRow(ownerId, server.id(), server.url(), token, server.enabled(),
                    mapper.writeValueAsString(server.tools())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存 MCP 工具定义", e);
        }
    }

    @Override
    public boolean delete(String ownerId, String id) {
        return rows.delete(ownerId, id) > 0;
    }

    private McpServer read(McpServerRow row) {
        try {
            String token = row.tokenCiphertext() == null ? null : new String(cipher.open(row.tokenCiphertext(),
                    context(row.ownerId(), row.serverId(), row.endpointUrl())), StandardCharsets.UTF_8);
            List<McpServer.Tool> tools = mapper.readValue(row.toolsJson(), new TypeReference<>() {});
            return new McpServer(row.serverId(), row.endpointUrl(), token, row.enabled(), tools);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取 MCP 工具定义", e);
        }
    }

    private static byte[] context(String owner, String id, String url) {
        return String.join("\n", "mcp-server", owner, id, url).getBytes(StandardCharsets.UTF_8);
    }
}
