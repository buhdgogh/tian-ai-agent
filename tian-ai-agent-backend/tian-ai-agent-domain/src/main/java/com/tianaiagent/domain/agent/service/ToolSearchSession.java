package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.model.entity.AgentRun;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springaicommunity.tool.search.ToolReference;
import org.springaicommunity.tool.search.ToolSearchRequest;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class ToolSearchSession {
    static final String TOOL_NAME = "toolSearchTool";
    static final String INSTRUCTIONS = """
            需要工具时先调用 toolSearchTool，查询可包含工具名、MCP 主机名或描述能力的关键词。
            用户明确指定工具时，按工具名搜索并根据实际调用结果回答。
            下一步会收到最多五个工具的定义，请按其中的参数定义调用。
            如需其他工具或搜索未匹配，重新搜索，最新结果会替换上一批工具。
            信息充足时直接回答。完成任务后给出最终答案。
            """;
    private static final int TOP_K = 5;
    private static final String INDEX = "current-run";
    private final boolean searchEnabled;
    private final Map<String, ToolCallback> catalog = new LinkedHashMap<>();
    private final ToolCallback discovery = ToolCallbacks.from(this)[0];
    private List<ToolCallback> selected = List.of();

    ToolSearchSession(ToolCallback[] candidates) {
        this(candidates, true);
    }

    ToolSearchSession(ToolCallback[] candidates, boolean searchEnabled) {
        this.searchEnabled = searchEnabled;
        for (ToolCallback candidate : candidates) {
            String name = candidate.getToolDefinition().name();
            if (!TOOL_NAME.equals(name)) catalog.putIfAbsent(name, candidate);
        }
    }

    List<ToolCallback> callbacks() {
        return searchEnabled ? Stream.concat(Stream.of(discovery), selected.stream()).toList()
                : List.copyOf(catalog.values());
    }

    String instructions() { return searchEnabled ? INSTRUCTIONS : ""; }

    String unavailableToolMessage() {
        return searchEnabled ? "请先用 toolSearchTool 查找并加载工具" : "当前工作区角色未获授权使用该工具";
    }

    @Tool(name = TOOL_NAME, description = "搜索本轮可用工具。每次按相关性提供最多五个工具。")
    public List<String> search(@ToolParam(description = "工具名称、MCP 主机名或描述所需能力的关键词") String query) {
        var run = AgentRunContext.current();
        if (run == null) return searchCatalog(query);
        Instant started = Instant.now();
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            List<String> result = searchCatalog(query);
            success = true;
            return result;
        } finally {
            run.event(new AgentRun.Event("TOOL", TOOL_NAME, null, null, success ? "SUCCESS" : "FAILED",
                    started, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), null, null, null,
                    null, UUID.randomUUID().toString().replace("-", ""), ToolGateway.digest(query)));
        }
    }

    private List<String> searchCatalog(String query) {
        if (query == null || query.isBlank() || catalog.isEmpty()) {
            selected = List.of();
            return List.of();
        }
        try (var searcher = new LuceneToolSearcher(0.0f)) {
            catalog.forEach((name, callback) -> {
                String keywords = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
                searcher.indexTool(INDEX, new ToolReference(name, null,
                        name + " " + keywords + " " + callback.getToolDefinition().description()));
            });
            searcher.commit(INDEX);
            var result = searcher.search(new ToolSearchRequest(INDEX, query, TOP_K, null));
            selected = result.toolReferences().stream().map(tool -> catalog.get(tool.toolName())).limit(TOP_K).toList();
            return selected.stream().map(callback -> callback.getToolDefinition().name()).toList();
        } catch (IOException error) {
            throw new UncheckedIOException("关闭工具搜索索引失败", error);
        }
    }
}
