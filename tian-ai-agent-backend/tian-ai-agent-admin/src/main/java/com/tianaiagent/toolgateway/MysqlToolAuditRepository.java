package com.tianaiagent.toolgateway;

import com.tianaiagent.persistence.AgentDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class MysqlToolAuditRepository implements ToolAuditRepository {
    private final AgentDatabase database;
    public MysqlToolAuditRepository(AgentDatabase database) { this.database = database; }

    public void save(ToolAuditRecord audit) {
        String sql = "INSERT INTO agent_tool_audit(id,task_id,owner_id,chat_id,step_id,iteration,tool_call_id,"
                + "tool_name,status,duration_ms,argument_digest,error_text,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE id=id";
        try (var connection = database.connection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, audit.id());
            statement.setString(2, audit.taskId());
            statement.setString(3, audit.ownerId());
            statement.setString(4, audit.chatId());
            statement.setString(5, audit.stepId());
            statement.setObject(6, audit.iteration());
            statement.setString(7, audit.toolCallId());
            statement.setString(8, audit.toolName());
            statement.setString(9, audit.status());
            statement.setLong(10, audit.durationMs());
            statement.setString(11, audit.argumentDigest());
            statement.setString(12, audit.error());
            statement.setString(13, audit.createdAt());
            statement.executeUpdate();
        } catch (Exception error) { throw new IllegalStateException("保存MySQL工具审计失败", error); }
    }

    public List<ToolAuditRecord> find(String taskId, String ownerId, String chatId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM agent_tool_audit WHERE 1=1");
        List<String> values = new ArrayList<>();
        add(sql, values, "task_id", taskId);
        add(sql, values, "owner_id", ownerId);
        add(sql, values, "chat_id", chatId);
        add(sql, values, "status", status);
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?");
        try (var connection = database.connection(); var statement = connection.prepareStatement(sql.toString())) {
            int position = 1;
            for (String value : values) statement.setString(position++, value);
            statement.setInt(position++, Math.max(1, Math.min(500, limit)));
            statement.setInt(position, Math.max(0, offset));
            List<ToolAuditRecord> audits = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    audits.add(new ToolAuditRecord(rows.getString("id"), rows.getString("tool_name"),
                            rows.getString("status"), rows.getLong("duration_ms"), rows.getString("argument_digest"),
                            rows.getString("error_text"), rows.getString("created_at"), rows.getString("task_id"),
                            rows.getString("owner_id"), rows.getString("chat_id"), rows.getString("step_id"),
                            rows.getObject("iteration") == null ? null : rows.getInt("iteration"), rows.getString("tool_call_id")));
                }
            }
            return audits;
        } catch (Exception error) { throw new IllegalStateException("查询MySQL工具审计失败", error); }
    }

    private static void add(StringBuilder sql, List<String> values, String column, String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(column).append("=?"); values.add(value); }
    }
}
