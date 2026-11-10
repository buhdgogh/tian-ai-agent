package com.tianaiagent.domain.agent.adapter.repository;

import com.tianaiagent.domain.agent.model.valobj.AgentPreset;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset.Kind;

import java.util.List;

public interface AgentPresetStore {
    List<AgentPreset> list(String ownerId, Kind kind);
    void insert(String ownerId, Kind kind, AgentPreset preset);
    boolean update(String ownerId, Kind kind, AgentPreset preset);
    boolean delete(String ownerId, Kind kind, String id);
}
