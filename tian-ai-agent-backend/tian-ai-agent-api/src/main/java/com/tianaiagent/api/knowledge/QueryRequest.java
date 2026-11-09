package com.tianaiagent.api.knowledge;

public record QueryRequest(String question, Integer topK, String spaceId) {}
