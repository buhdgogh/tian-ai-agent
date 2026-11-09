package com.tianaiagent.domain.agent.adapter.repository;

import com.tianaiagent.domain.agent.model.valobj.ModelProvider;
import java.util.List;
import java.util.Map;

public interface ModelProviderStore {
    Map<String, List<ModelProvider>> load();
    void save(String owner, List<ModelProvider> providers);
}
