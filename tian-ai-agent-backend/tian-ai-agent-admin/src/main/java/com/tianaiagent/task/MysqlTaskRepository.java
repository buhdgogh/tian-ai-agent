package com.tianaiagent.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class MysqlTaskRepository implements TaskRepository {
    private final AgentDatabase database;
    private final ObjectMapper mapper;

    public MysqlTaskRepository(AgentDatabase database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    public void save(TaskRecord task) { write(task, false); }

    /** Explicit migration is idempotent and must not overwrite newer database records. */
    public void importIfAbsent(TaskRecord task) { write(task, true); }

    private void write(TaskRecord task, boolean insertOnly) {
        String sql = "INSERT INTO agent_task(id,type,status,owner_id,progress,stage,input_json,result_json,"
                + "error_text,events_json,created_at,started_at,finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + (insertOnly ? "ON DUPLICATE KEY UPDATE id=id" : """
                ON DUPLICATE KEY UPDATE status=VALUES(status),progress=VALUES(progress),stage=VALUES(stage),
                result_json=VALUES(result_json),error_text=VALUES(error_text),events_json=VALUES(events_json),
                started_at=VALUES(started_at),finished_at=VALUES(finished_at)
                """);
        try (var connection = database.connection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.getId());
            statement.setString(2, task.getType().name());
            statement.setString(3, task.getStatus().name());
            statement.setString(4, task.getOwnerId());
            statement.setInt(5, task.getProgress());
            statement.setString(6, task.getStage());
            statement.setString(7, mapper.writeValueAsString(task.getInput()));
            statement.setString(8, task.getResult() == null ? null : mapper.writeValueAsString(task.getResult()));
            statement.setString(9, task.getError());
            statement.setString(10, mapper.writeValueAsString(task.getEvents()));
            statement.setString(11, task.getCreatedAt());
            statement.setString(12, task.getStartedAt());
            statement.setString(13, task.getFinishedAt());
            statement.executeUpdate();
        } catch (Exception error) { throw new IllegalStateException("保存MySQL任务失败", error); }
    }

    public Optional<TaskRecord> find(String id) {
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT * FROM agent_task WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        } catch (Exception error) { throw new IllegalStateException("读取MySQL任务失败", error); }
    }

    public List<TaskRecord> list(String ownerId, TaskType type) {
        String sql = "SELECT * FROM agent_task WHERE 1=1"
                + (ownerId == null ? "" : " AND owner_id=?") + (type == null ? "" : " AND type=?")
                + " ORDER BY created_at DESC,id DESC";
        try (var connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int position = 1;
            if (ownerId != null) statement.setString(position++, ownerId);
            if (type != null) statement.setString(position, type.name());
            List<TaskRecord> tasks = new ArrayList<>();
            try (var rows = statement.executeQuery()) { while (rows.next()) tasks.add(read(rows)); }
            return tasks;
        } catch (Exception error) { throw new IllegalStateException("查询MySQL任务失败", error); }
    }

    private TaskRecord read(ResultSet row) throws Exception {
        TaskRecord task = new TaskRecord();
        task.setId(row.getString("id"));
        task.setType(TaskType.valueOf(row.getString("type")));
        task.setStatus(TaskStatus.valueOf(row.getString("status")));
        task.setOwnerId(row.getString("owner_id"));
        task.setProgress(row.getInt("progress"));
        task.setStage(row.getString("stage"));
        task.setInput(mapper.readValue(row.getString("input_json"), new TypeReference<>() {}));
        String result = row.getString("result_json");
        task.setResult(result == null ? null : mapper.readValue(result, Object.class));
        task.setError(row.getString("error_text"));
        task.setEvents(mapper.readValue(row.getString("events_json"), new TypeReference<>() {}));
        task.setCreatedAt(row.getString("created_at"));
        task.setStartedAt(row.getString("started_at"));
        task.setFinishedAt(row.getString("finished_at"));
        return task;
    }
}
