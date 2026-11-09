package com.tianaiagent.infrastructure.adapter.repository.conversation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;

/** 保留工具调用结构的会话消息持久化模型。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StructuredChatMessageDTO implements Serializable {
    private String role;
    private String content;
    private ArrayList<ToolCallDTO> toolCalls;
    private ArrayList<ToolResponseDTO> toolResponses;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallDTO implements Serializable {
        private String id;
        private String type;
        private String name;
        private String arguments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResponseDTO implements Serializable {
        private String id;
        private String name;
        private String responseData;
    }
}
