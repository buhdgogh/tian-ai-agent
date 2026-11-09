package com.tianaiagent.infrastructure.tool;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 描述：终止工具（作用是让自主规划智能体能够合理地中断）
 */
public class TerminateTool {
    @Tool(description = """
            Call this tool ONLY after you have fully completed all the user's requests
            and have already provided a clear final answer in your response text.
            Do NOT call this tool if a tool call failed or returned no results —
            try alternative approaches or explain the limitation to the user first.
            """)
    public String doTerminate()
    {
        return "任务结束";
    }
}
