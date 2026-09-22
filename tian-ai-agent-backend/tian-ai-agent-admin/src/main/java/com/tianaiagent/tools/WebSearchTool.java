package com.tianaiagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
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

    /** 构造方法 **/
    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "检索最新医疗信息。医疗问题应优先查询卫健部门、药品监管机构、指南发布机构和同行评议来源，并核对日期与版本")
    public String searchWeb(@ToolParam(description = "包含疾病/药物、目标人群、地区、指南或标签版本的检索词") String query) {
        if (query == null || query.isBlank()) return error("搜索词不能为空");
        if (apiKey == null || apiKey.isBlank()) return error("SEARCH_API_KEY未配置");
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
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
                    "warning", "搜索摘要不是临床证据，必须打开原始来源核对版本、适用人群和正文"));
        } catch (Exception e) {
            return error("医疗信息搜索失败: " + e.getMessage());
        }
    }

    private String sourceType(String url) {
        String value = url == null ? "" : url.toLowerCase();
        if (value.contains("gov.cn") || value.contains("nmpa.gov.cn") || value.contains("nhc.gov.cn")) return "REGULATOR";
        if (value.contains("who.int") || value.contains("pubmed.ncbi.nlm.nih.gov")) return "AUTHORITATIVE";
        return "UNVERIFIED_WEB";
    }

    private String error(String message) {
        return JSONUtil.toJsonStr(Map.of("ok", false, "error", message, "retrievedAt", OffsetDateTime.now().toString()));
    }
}
