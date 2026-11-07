package com.tianaiagent.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.mcp.McpToolClient;
import com.tianaiagent.observability.ExecutionContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/** LLM chooses whether to follow links; Java validates provenance, depth, deduplication and page budget. */
@Component
public class WikiTraversalGuard {
    private final McpToolClient mcp;
    private final ObjectMapper mapper;
    private final int maxDepth;
    private final int maxPages;

    public WikiTraversalGuard(McpToolClient mcp, ObjectMapper mapper,
                              @Value("${agent.wiki.max-depth:2}") int maxDepth,
                              @Value("${agent.wiki.max-pages:6}") int maxPages) {
        if (maxDepth < 0 || maxPages < 1) throw new IllegalArgumentException("Wiki读取限制配置非法");
        this.mcp = mcp; this.mapper = mapper; this.maxDepth = maxDepth; this.maxPages = maxPages;
    }

    public String search(String query, String tag, ToolContext context) {
        WikiTraversalState state = state(context);
        if (state == null) return missingScope();
        synchronized (state) {
            if (state.visited.size() >= maxPages) return decision("limit_reached", "max_pages", "", state, "已达到Wiki读取总页数上限，不再扩展搜索。", "");
        }
        String response = mcp.call("wiki_search", Map.of("query", query == null ? "" : query,
                "tag", tag == null ? "" : tag, "limit", 20, "published_only", true));
        JsonNode data = businessData(response);
        synchronized (state) {
            for (JsonNode page : data.path("pages")) registerRoot(state, page.path("page_id").asText(""));
        }
        return response;
    }

    /** The bounded, automatic RAG injection remains separate; its returned page IDs are valid starting points. */
    public void registerSeeds(JsonNode result, ToolContext context) {
        WikiTraversalState state = state(context);
        if (state == null || result == null) return;
        synchronized (state) {
            for (JsonNode page : result.path("wiki_pages")) registerRoot(state, page.path("page_id").asText(""));
        }
    }

    private void registerRoot(WikiTraversalState state, String id) {
        if (!id.isBlank()) state.depths.putIfAbsent(id, 0); // A later search cannot reset an already discovered path.
    }

    public String read(String pageId, String fromPageId, ToolContext context) {
        if (pageId == null || pageId.isBlank()) throw new IllegalArgumentException("pageId不能为空");
        WikiTraversalState state = state(context);
        if (state == null) return missingScope();
        // This also prevents concurrent workers from fetching the same page or exceeding the shared page cap.
        synchronized (state) {
            WikiTraversalState.Visit previous = state.visited.get(pageId);
            if (previous != null && previous.deliveredTo().add(consumer(context)))
                return json(Map.of("ok", true, "status", "cached", "cached", true, "data", previous.data(), "traversal", budget(state, state.depths.get(pageId))));
            if (previous != null) return decision("already_visited", "duplicate_page", pageId, state,
                    "本轮已读取此页，不重复读取或注入正文；请复用已有证据或上游任务结果。", previous.revisionId());
            boolean hasParent = fromPageId != null && !fromPageId.isBlank();
            Integer depth = state.depths.get(pageId);
            if (hasParent) {
                WikiTraversalState.Visit parent = state.visited.get(fromPageId);
                if (parent == null || !parent.neighbors().contains(pageId))
                    return decision("rejected", "invalid_link", pageId, state, "跳转必须来自已读页面的有效入链或出链。", "");
                int discovered = state.depths.get(fromPageId) + 1;
                state.depths.merge(pageId, discovered, Math::min);
                depth = state.depths.get(pageId);
            } else if (depth != null && depth > 0) {
                return decision("rejected", "parent_required", pageId, state, "读取关联页时请提供已读的fromPageId，不要将关联页当作新的起始页。", "");
            }
            if (depth == null) return decision("rejected", "not_discovered", pageId, state, "先搜索Wiki，使用搜索结果或已读页面中的真实页面ID。", "");
            if (depth > maxDepth) return decision("limit_reached", "max_depth", pageId, state, "已达到Wiki跳转深度上限，请基于已有证据回答或说明信息不足。", "");
            if (state.visited.size() >= maxPages) return decision("limit_reached", "max_pages", pageId, state, "已达到本轮Wiki读取总页数上限，请汇总已有证据，不再扩展Wiki。", "");
            String response = mcp.call("wiki_read_note", Map.of("page_id", pageId, "published_only", true));
            JsonNode data = businessData(response);
            if (!pageId.equals(data.path("page_id").asText()) || data.path("revision_id").asText("").isBlank())
                throw new IllegalStateException("Wiki返回的页面标识不匹配");
            Set<String> neighbors = new HashSet<>();
            for (JsonNode link : data.path("links")) {
                if (link.path("resolved").asBoolean(false)) addNeighbor(neighbors, link.path("page_id").asText(""));
            }
            for (JsonNode link : data.path("backlinks")) addNeighbor(neighbors, link.path("page_id").asText(""));
            for (String target : neighbors) state.depths.merge(target, depth + 1, Math::min);
            state.visited.put(pageId, new WikiTraversalState.Visit(data.path("revision_id").asText(), data.path("title").asText(""), Set.copyOf(neighbors), data,
                    new HashSet<>(Set.of(consumer(context)))));
            return json(Map.of("ok", true, "status", "read", "data", data, "traversal", budget(state, depth)));
        }
    }

    private static void addNeighbor(Set<String> neighbors, String id) { if (!id.isBlank()) neighbors.add(id); }
    private WikiTraversalState state(ToolContext context) {
        if (context != null && context.getContext().get(WikiTraversalState.CONTEXT_KEY) instanceof WikiTraversalState state) return state;
        return ExecutionContext.current().wikiTraversal();
    }
    private String consumer(ToolContext context) {
        if (context != null && context.getContext().get(WikiTraversalState.CONSUMER_KEY) instanceof String id) return id;
        var trace = ExecutionContext.current();
        return trace.stepId() == null ? "root" : "step:" + trace.stepId() + ":" + trace.iteration();
    }
    private Map<String, Object> budget(WikiTraversalState state, int depth) {
        return Map.of("depth", depth, "max_depth", maxDepth, "pages_read", state.visited.size(),
                "max_pages", maxPages, "remaining_pages", Math.max(0, maxPages - state.visited.size()));
    }
    private String decision(String status, String reason, String pageId, WikiTraversalState state, String message, String revision) {
        return json(Map.of("ok", false, "status", status, "reason", reason, "page_id", pageId, "revision_id", revision,
                "message", message, "traversal", budget(state, state.depths.getOrDefault(pageId, -1))));
    }
    private String missingScope() { return json(Map.of("ok", false, "status", "rejected", "reason", "missing_execution_scope", "message", "Wiki读取缺少本轮执行上下文，不能创建无限制的独立读取。")); }
    private JsonNode businessData(String response) {
        try {
            JsonNode root = mapper.readTree(response);
            if (root == null || root.has("ok") && !root.path("ok").asBoolean()) throw new IllegalStateException("Wiki工具返回业务错误");
            return root.has("ok") ? root.path("data") : root;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Wiki响应不是合法JSON", e); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Wiki状态序列化失败", e); } }
}
