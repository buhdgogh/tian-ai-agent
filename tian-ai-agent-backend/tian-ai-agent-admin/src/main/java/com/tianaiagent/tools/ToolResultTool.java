package com.tianaiagent.tools;

import com.tianaiagent.toolgateway.ToolResultStore;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class ToolResultTool {
    private final ToolResultStore store;
    public ToolResultTool(ToolResultStore store) { this.store = store; }

    @Tool(description = "分页读取当前会话落盘的大工具结果。根据resultId读取；offset首次为0，后续使用nextOffset。返回内容受Token预算限制，hasMore=true表示仍有未读取内容。",
            resultConverter = RawJsonResultConverter.class)
    public String readToolResult(
            @ToolParam(description = "工具返回的resultId，不接受文件路径") String resultId,
            @ToolParam(description = "字符偏移，首次0，后续传nextOffset") int offset,
            @ToolParam(description = "本页Token预算，256到配置上限", required = false) Integer maxTokens,
            ToolContext context) {
        Object scope = context == null ? null : context.getContext().get(ToolResultStore.SCOPE_KEY);
        return store.read(scope instanceof String value ? value : null, resultId, offset, maxTokens);
    }

    /** store 已生成合法且预算内的 JSON，避免框架再次把它编码为 JSON 字符串。 */
    public static class RawJsonResultConverter implements org.springframework.ai.tool.execution.ToolCallResultConverter {
        @Override public String convert(Object result, java.lang.reflect.Type returnType) {
            return (String) result;
        }
    }
}
