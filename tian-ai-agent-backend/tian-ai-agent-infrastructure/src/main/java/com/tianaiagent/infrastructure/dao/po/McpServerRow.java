package com.tianaiagent.infrastructure.dao.po;

public record McpServerRow(String ownerId, String serverId, String endpointUrl, byte[] tokenCiphertext,
                           boolean enabled, String toolsJson) {}
