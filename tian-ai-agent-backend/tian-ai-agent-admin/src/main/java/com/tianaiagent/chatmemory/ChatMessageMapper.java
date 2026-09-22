package com.tianaiagent.chatmemory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 在 Spring AI 消息与可持久化 DTO 之间转换。 */
final class ChatMessageMapper {
    private ChatMessageMapper() {}

    static StructuredChatMessageDTO toDto(Message message) {
        ArrayList<StructuredChatMessageDTO.ToolCallDTO> toolCalls = new ArrayList<>();
        ArrayList<StructuredChatMessageDTO.ToolResponseDTO> toolResponses = new ArrayList<>();
        if (message instanceof AssistantMessage assistant) {
            assistant.getToolCalls().forEach(call -> toolCalls.add(
                    new StructuredChatMessageDTO.ToolCallDTO(
                            call.id(), call.type(), call.name(), call.arguments())));
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            toolResponse.getResponses().forEach(response -> toolResponses.add(
                    new StructuredChatMessageDTO.ToolResponseDTO(
                            response.id(), response.name(), response.responseData())));
        }
        return new StructuredChatMessageDTO(message.getMessageType().name(),
                safe(message.getText()), toolCalls, toolResponses);
    }

    static Message fromDto(StructuredChatMessageDTO dto) {
        String role = normalizeRole(dto.getRole());
        String content = safe(dto.getContent());
        if ("ASSISTANT".equals(role)) {
            List<AssistantMessage.ToolCall> calls = safeList(dto.getToolCalls()).stream()
                    .map(call -> new AssistantMessage.ToolCall(
                            safe(call.getId()), safe(call.getType()), safe(call.getName()),
                            safe(call.getArguments())))
                    .toList();
            return new AssistantMessage(content, Map.of(), calls);
        }
        if ("TOOL".equals(role)) {
            List<ToolResponseMessage.ToolResponse> responses = safeList(dto.getToolResponses()).stream()
                    .map(response -> new ToolResponseMessage.ToolResponse(
                            safe(response.getId()), safe(response.getName()),
                            safe(response.getResponseData())))
                    .toList();
            return new ToolResponseMessage(responses, Map.of());
        }
        if ("SYSTEM".equals(role)) return new SystemMessage(content);
        return new UserMessage(content);
    }

    static Message fromLegacyDto(ChatMessageDTO dto) {
        String role = dto.getRole();
        String content = dto.getContent();
        // 兼容旧版本把 content/role 构造参数写反的数据。
        if (!isRole(role) && isRole(content)) {
            String oldRole = role;
            role = content;
            content = oldRole;
        }
        return fromDto(new StructuredChatMessageDTO(
                normalizeRole(role), safe(content), new ArrayList<>(), new ArrayList<>()));
    }

    private static String normalizeRole(String value) {
        return isRole(value) ? value.toUpperCase() : "USER";
    }

    private static boolean isRole(String value) {
        return "USER".equalsIgnoreCase(value) || "ASSISTANT".equalsIgnoreCase(value)
                || "SYSTEM".equalsIgnoreCase(value) || "TOOL".equalsIgnoreCase(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }
}
