package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.tianaiagent.domain.agent.adapter.repository.ModelProviderStore;
import com.tianaiagent.domain.agent.model.valobj.ModelProvider;
import com.tianaiagent.infrastructure.dao.mysql.ModelProviderMapper;
import com.tianaiagent.infrastructure.dao.po.ModelProviderRow;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MysqlModelProviderStore implements ModelProviderStore {
    private final ModelProviderMapper rows;
    private final TransactionTemplate transactions;
    private final ModelSettingsCipher encryption = new ModelSettingsCipher();

    public MysqlModelProviderStore(ModelProviderMapper rows, TransactionTemplate transactions) {
        this.rows = rows;
        this.transactions = transactions;
    }

    public void importLegacy(ModelProviderStore legacy) {
        var initializedOwners = new java.util.HashSet<>(rows.initializedOwners());
        var legacySettings = legacy.load();
        legacySettings.forEach((owner, providers) -> {
            if (initializedOwners.contains(owner)) return;
            transactions.executeWithoutResult(status -> {
                int inserted = rows.initializeOwner(owner);
                if (inserted == 1) insertProviders(owner, providers);
            });
        });
    }

    @Override
    public Map<String, List<ModelProvider>> load() {
        Map<String, List<ModelProvider>> settings = new LinkedHashMap<>();
        for (var row : rows.list()) {
            var protocol = ModelProvider.Protocol.valueOf(row.protocol());
            byte[] bytes = encryption.open(row.apiKeyCiphertext(),
                    context(row.ownerId(), row.providerId(), protocol, row.baseUrl()));
            var provider = new ModelProvider(row.providerId(), protocol, row.baseUrl(),
                    new String(bytes, StandardCharsets.UTF_8), row.modelName(), row.priority(), row.enabled(),
                    row.workspaceId(), row.streamUsage(), row.reasoningEffort());
            settings.computeIfAbsent(row.ownerId(), ignored -> new ArrayList<>()).add(provider);
        }
        return settings;
    }

    @Override
    public void save(String owner, List<ModelProvider> providers) {
        transactions.executeWithoutResult(status -> {
            rows.touchOwner(owner);
            rows.deleteProviders(owner);
            insertProviders(owner, providers);
        });
    }

    private void insertProviders(String owner, List<ModelProvider> providers) {
        for (int position = 0; position < providers.size(); position++) {
            ModelProvider row = providers.get(position);
            byte[] sealed = encryption.seal(row.apiKey().getBytes(StandardCharsets.UTF_8),
                    context(owner, row.id(), row.protocol(), row.baseUrl()));
            rows.insert(new ModelProviderRow(owner, row.id(), position, row.protocol().name(), row.baseUrl(), row.model(),
                    sealed, row.priority(), row.enabled(), row.workspaceId(), row.streamUsage(), row.reasoningEffort()));
        }
    }

    private static byte[] context(String owner, String id, ModelProvider.Protocol protocol, String url) {
        return String.join("\n", owner, id, protocol.name(), url).getBytes(StandardCharsets.UTF_8);
    }
}
