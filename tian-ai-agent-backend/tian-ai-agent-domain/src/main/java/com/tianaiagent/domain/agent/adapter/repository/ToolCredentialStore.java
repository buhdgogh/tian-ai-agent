package com.tianaiagent.domain.agent.adapter.repository;

import com.tianaiagent.domain.agent.model.valobj.PersonalTool;

public interface ToolCredentialStore {
    boolean exists(String ownerId, PersonalTool tool);
    String find(String ownerId, PersonalTool tool);
    void save(String ownerId, PersonalTool tool, String value);
    void delete(String ownerId, PersonalTool tool);
}
