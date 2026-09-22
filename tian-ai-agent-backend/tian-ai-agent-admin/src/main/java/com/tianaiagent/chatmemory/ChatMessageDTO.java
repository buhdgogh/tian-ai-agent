package com.tianaiagent.chatmemory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 简单的对话消息 DTO，用于持久化
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageDTO implements Serializable {
    private String role; // USER, ASSISTANT, SYSTEM
    private String content;
}
