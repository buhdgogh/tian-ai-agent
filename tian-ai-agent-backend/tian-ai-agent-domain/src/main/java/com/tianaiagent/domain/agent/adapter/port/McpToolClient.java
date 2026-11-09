package com.tianaiagent.domain.agent.adapter.port;

import com.tianaiagent.domain.agent.model.valobj.McpServer;

import java.util.List;

public interface McpToolClient {
    List<McpServer.Tool> discover(String url, String token);
    String call(String url, String token, String name, String arguments);

    class Unavailable extends RuntimeException {}
}
