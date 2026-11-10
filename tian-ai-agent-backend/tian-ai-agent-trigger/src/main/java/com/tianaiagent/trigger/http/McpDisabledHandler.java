package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.knowledge.service.SpaceEmbeddings;
import com.tianaiagent.types.exception.McpDisabledException;
import com.tianaiagent.types.exception.ModelUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
class McpDisabledHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalidRequest(IllegalArgumentException error) {
        return Map.of("code", "INVALID_ARGUMENT", "message", "请求参数无效");
    }

    @ExceptionHandler(SpaceEmbeddings.Unavailable.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    Map<String, String> embeddingUnavailable(SpaceEmbeddings.Unavailable error) {
        return Map.of("code", "EMBEDDING_UNAVAILABLE", "message", error.getMessage());
    }

    @ExceptionHandler(ModelUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> modelUnavailable(ModelUnavailableException error) {
        var response = new LinkedHashMap<>(Map.of("code", error.code(), "message", error.getMessage()));
        if (error.runId() != null) response.put("runId", error.runId());
        return response;
    }

    @ExceptionHandler(McpDisabledException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> disabled(McpDisabledException error) {
        return Map.of("code", "MCP_DISABLED", "message", error.getMessage());
    }
}
