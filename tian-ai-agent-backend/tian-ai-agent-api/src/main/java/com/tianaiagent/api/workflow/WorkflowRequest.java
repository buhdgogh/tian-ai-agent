package com.tianaiagent.api.workflow;

public record WorkflowRequest(String objective, String chatId, String ownerId, boolean reviewEnabled, String spaceId) {}
