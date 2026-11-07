package com.tianaiagent.stream;

import com.tianaiagent.observability.ExecutionContext;
import com.tianaiagent.task.TaskRecord;
import java.util.Map;

/** Public execution summaries only; model reasoning and raw tool arguments are never sent here. */
public interface AgentOutput {
    void event(String type, Map<String, Object> data);
    void terminal(TaskRecord task);
    default void checkpoint() {}
    static AgentOutput current() { return ExecutionContext.current().output(); }
    static void progress(String title, String detail) {
        var output = current();
        if (output != null) output.event("progress", Map.of("title", title, "detail", detail));
    }
}
