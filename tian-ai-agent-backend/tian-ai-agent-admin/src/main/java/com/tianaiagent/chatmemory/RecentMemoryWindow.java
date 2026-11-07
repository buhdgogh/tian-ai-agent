package com.tianaiagent.chatmemory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/** 只裁剪模型输入，不修改持久化记录；整段用户回合保留，避免拆散工具调用与返回。 */
public final class RecentMemoryWindow {
    private RecentMemoryWindow() {}

    public static List<Message> select(List<Message> messages, int maxMessages, int maxChars) {
        List<Message> selected = new ArrayList<>();
        int chars = 0;
        int end = messages.size();
        while (end > 0) {
            int start = end - 1;
            while (start >= 0 && !(messages.get(start) instanceof UserMessage)) start--;
            if (start < 0) break; // 不注入存储窗口截断后留下的孤立工具结果。
            List<Message> turn = messages.subList(start, end);
            int turnChars = turn.stream().mapToInt(message ->
                    ChatMessageMapper.toDto(message).toString().length()).sum();
            if (selected.size() + turn.size() > maxMessages || chars + turnChars > maxChars) break;
            selected.addAll(0, turn);
            chars += turnChars;
            end = start;
        }
        return selected;
    }
}
