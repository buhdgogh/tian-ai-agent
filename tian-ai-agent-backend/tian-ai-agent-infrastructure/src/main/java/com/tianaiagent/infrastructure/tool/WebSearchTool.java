package com.tianaiagent.infrastructure.tool;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 描述：网页搜索工具
 */
public class WebSearchTool {
    /** SearchAPI 的搜索接口地址 **/
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    /** API密钥 **/
    private final String apiKey;
    private final String endpoint;

    /** 构造方法 **/
    public WebSearchTool(String apiKey) {
        this(apiKey, SEARCH_API_URL);
    }

    WebSearchTool(String apiKey, String endpoint) {
        String key = apiKey == null ? "" : apiKey.strip();
        if (key.length() > 1 && (key.startsWith("\"") && key.endsWith("\"")
                || key.startsWith("'") && key.endsWith("'"))) {
            key = key.substring(1, key.length() - 1).strip();
        }
        this.apiKey = key;
        this.endpoint = endpoint;
    }

    @Tool(description = "检索公开网页。优先选择权威原始来源，并核对日期与版本。")
    public String searchWeb(@ToolParam(description = "包含主题及所需地区、日期或版本等约束的检索词") String query) {
        if (query == null || query.isBlank()) return error("SEARCH_QUERY_EMPTY", "搜索词不能为空");
        if (apiKey.isBlank()) return error("SEARCH_API_KEY_MISSING", "SEARCH_API_KEY 未配置");
        try (HttpResponse response = HttpRequest.get(endpoint).header("Authorization", "Bearer " + apiKey)
                .form("engine", "baidu").form("q", query.trim()).timeout(10_000).execute()) {
            int status = response.getStatus();
            if (status < 200 || status >= 300) {
                String message = switch (status) {
                    case 401, 403 -> "SearchAPI 拒绝认证，请检查密钥和账号权限";
                    case 402 -> "SearchAPI 额度不足，请检查账户用量";
                    case 429 -> "SearchAPI 请求过于频繁，请稍后重试";
                    default -> "SearchAPI 请求失败（HTTP " + status + "）";
                };
                return error("SEARCH_API_HTTP_" + status, message);
            }
            JSONObject jsonObject = JSONUtil.parseObj(response.body());
            if (jsonObject.getStr("error") != null) {
                return error("SEARCH_API_ERROR", "SearchAPI 返回检索错误，请检查账户和检索请求");
            }
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            List<Map<String, Object>> results = new ArrayList<>();
            if (organicResults != null) for (int i = 0; i < Math.min(5, organicResults.size()); i++) {
                Object obj = organicResults.get(i);
                JSONObject tmpJSONObject = (JSONObject) obj;
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("title", tmpJSONObject.getStr("title", ""));
                item.put("url", tmpJSONObject.getStr("link", ""));
                item.put("snippet", tmpJSONObject.getStr("snippet", ""));
                item.put("date", tmpJSONObject.getStr("date", ""));
                item.put("sourceType", sourceType(tmpJSONObject.getStr("link", "")));
                results.add(item);
            }
            return JSONUtil.toJsonStr(Map.of(
                    "ok", true, "query", query.trim(), "retrievedAt", OffsetDateTime.now().toString(),
                    "results", results,
                    "warning", "搜索摘要仅供定位，引用前应打开原始来源核对正文、日期与适用范围"));
        } catch (Exception e) {
            return error("SEARCH_API_REQUEST_FAILED", "网页搜索失败，请检查网络连接或 SearchAPI 服务状态");
        }
    }

    private String sourceType(String url) {
        String value = url == null ? "" : url.toLowerCase();
        if (value.contains("gov.cn") || value.contains("nmpa.gov.cn") || value.contains("nhc.gov.cn")) return "REGULATOR";
        if (value.contains("who.int") || value.contains("pubmed.ncbi.nlm.nih.gov")) return "AUTHORITATIVE";
        return "UNVERIFIED_WEB";
    }

    private String error(String code, String message) {
        return JSONUtil.toJsonStr(Map.of("ok", false, "errorCode", code, "error", message,
                "retrievedAt", OffsetDateTime.now().toString()));
    }
}
