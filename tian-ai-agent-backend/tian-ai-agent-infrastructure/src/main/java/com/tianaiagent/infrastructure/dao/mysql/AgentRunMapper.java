package com.tianaiagent.infrastructure.dao.mysql;

import com.tianaiagent.infrastructure.dao.po.AgentRunRows;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentRunMapper {
    @Insert("""
            INSERT INTO agent_run(id, owner_id, kind, task_id, parent_run_id, status, started_at,
                elapsed_ms, event_count, input_tokens, output_tokens, total_tokens)
            VALUES (#{id},#{ownerId},#{kind},#{taskId},#{parentRunId},#{status},#{startedAt},
                #{elapsedMs},#{eventCount},#{inputTokens},#{outputTokens},#{totalTokens})
            """)
    int insert(AgentRunRows.Summary run);

    @Insert("""
            <script>
            INSERT INTO agent_request_log(run_id, event_no, kind, name, provider_id, model_name, status,
                started_at, elapsed_ms, input_tokens, output_tokens, total_tokens, reason)
            VALUES
            <foreach collection="events" item="e" separator=",">
                (#{e.runId},#{e.eventNo},#{e.kind},#{e.name},#{e.providerId},#{e.modelName},#{e.status},
                 #{e.startedAt},#{e.elapsedMs},#{e.inputTokens},#{e.outputTokens},#{e.totalTokens},#{e.reason})
            </foreach>
            </script>
            """)
    int insertEvents(@Param("events") List<AgentRunRows.Event> events);

    @Select("""
            SELECT * FROM agent_run WHERE owner_id=#{ownerId} ORDER BY started_at DESC, id DESC LIMIT #{limit}
            """)
    List<AgentRunRows.Summary> recent(@Param("ownerId") String ownerId, @Param("limit") int limit);

    @Select("SELECT * FROM agent_run WHERE id=#{id}")
    AgentRunRows.Summary find(@Param("id") String id);

    @Select("SELECT * FROM agent_request_log WHERE run_id=#{runId} ORDER BY event_no")
    List<AgentRunRows.Event> events(@Param("runId") String runId);
}
