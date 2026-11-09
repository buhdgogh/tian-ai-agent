package com.tianaiagent.domain.agent.adapter.repository;

import com.tianaiagent.domain.agent.model.valobj.McpServer;

import java.util.List;
import java.util.Optional;

public interface McpServerStore {
    List<McpServer> list(String ownerId);
    Optional<McpServer> find(String ownerId, String id);
    void save(String ownerId, McpServer server);
    boolean delete(String ownerId, String id);
}
