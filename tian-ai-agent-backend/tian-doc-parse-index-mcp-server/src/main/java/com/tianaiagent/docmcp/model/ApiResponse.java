package com.tianaiagent.docmcp.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record ApiResponse<T>(boolean ok, T data, Object error) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data, null); }
    public static ApiResponse<Void> error(String code, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("message", message);
        return new ApiResponse<>(false, null, value);
    }
}
