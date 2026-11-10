package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.tianaiagent.domain.agent.adapter.repository.AgentPresetStore;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset.Kind;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

public class MysqlAgentPresetStore implements AgentPresetStore {
    private final JdbcTemplate jdbc;

    public MysqlAgentPresetStore(DataSource dataSource) { this.jdbc = new JdbcTemplate(dataSource); }

    @Override
    public List<AgentPreset> list(String ownerId, Kind kind) {
        return jdbc.query("""
                SELECT preset_id, name, description, content, enabled FROM agent_preset
                WHERE owner_id = ? AND preset_kind = ?
                """, (row, index) -> new AgentPreset(row.getString("preset_id"), row.getString("name"),
                row.getString("description"), row.getString("content"), row.getBoolean("enabled")), ownerId, kind.name());
    }

    @Override
    public void insert(String ownerId, Kind kind, AgentPreset preset) {
        jdbc.update("""
                INSERT INTO agent_preset (owner_id, preset_kind, preset_id, name, description, content, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, ownerId, kind.name(), preset.id(), preset.name(), preset.description(), preset.content(), preset.enabled());
    }

    @Override
    public boolean update(String ownerId, Kind kind, AgentPreset preset) {
        int changed = jdbc.update("""
                UPDATE agent_preset SET name = ?, description = ?, content = ?, enabled = ?
                WHERE owner_id = ? AND preset_kind = ? AND preset_id = ?
                """, preset.name(), preset.description(), preset.content(), preset.enabled(), ownerId, kind.name(), preset.id());
        return changed > 0 || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM agent_preset WHERE owner_id = ? AND preset_kind = ? AND preset_id = ?)
                """, Boolean.class, ownerId, kind.name(), preset.id()));
    }

    @Override
    public boolean delete(String ownerId, Kind kind, String id) {
        return jdbc.update("DELETE FROM agent_preset WHERE owner_id = ? AND preset_kind = ? AND preset_id = ?",
                ownerId, kind.name(), id) > 0;
    }
}
