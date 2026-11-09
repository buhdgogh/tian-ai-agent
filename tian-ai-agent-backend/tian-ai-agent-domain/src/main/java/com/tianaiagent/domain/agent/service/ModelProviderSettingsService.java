package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.adapter.repository.ModelProviderStore;
import com.tianaiagent.domain.agent.model.valobj.ModelProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public class ModelProviderSettingsService {
    private final ModelProviderStore store;
    private final Predicate<ModelProvider> allowed;
    private final BiFunction<String, List<ModelProvider>, Runnable> prepareRoutes;
    private final Map<String, List<ModelProvider>> providers;
    private final Map<String, String> versions = new HashMap<>();
    private final String initialVersion = UUID.randomUUID().toString();

    public ModelProviderSettingsService(ModelProviderStore store, Predicate<ModelProvider> allowed,
                                        BiFunction<String, List<ModelProvider>, Runnable> prepareRoutes) {
        this.store = store;
        this.allowed = allowed;
        this.prepareRoutes = prepareRoutes;
        this.providers = new HashMap<>(store.load());
        providers.forEach((owner, rows) -> prepareRoutes.apply(owner, rows).run());
    }

    public synchronized Settings list(String owner) {
        List<View> rows = providers.getOrDefault(owner, List.of()).stream().map(View::from).toList();
        return new Settings(version(owner), rows);
    }

    public synchronized Settings save(String owner, String expectedVersion, List<ModelProvider> requested) {
        if (!version(owner).equals(expectedVersion)) throw new Conflict();
        if (requested == null || requested.size() > 20) throw new IllegalArgumentException("最多配置 20 个模型");

        Map<String, ModelProvider> previous = new HashMap<>();
        providers.getOrDefault(owner, List.of()).forEach(item -> previous.put(item.id(), item));
        var ids = new HashSet<String>();
        var resolved = new ArrayList<ModelProvider>();
        for (ModelProvider item : requested) {
            if (item == null) throw new IllegalArgumentException("模型配置不能为空");
            if (item.apiKey() == null || item.apiKey().isBlank()) {
                ModelProvider old = previous.get(item.id());
                if (old == null || old.protocol() != item.protocol() || !Objects.equals(old.baseUrl(), item.baseUrl())) {
                    throw new IllegalArgumentException("新增模型或修改协议、地址时必须重新输入 API Key");
                }
                item = item.withApiKey(old.apiKey());
            }
            item.validate();
            if (!ids.add(item.id())) throw new IllegalArgumentException("提供商 ID 必须唯一");
            if (item.enabled() && !allowed.test(item)) throw new OriginNotAllowed();
            resolved.add(item);
        }
        List<ModelProvider> rows = List.copyOf(resolved);
        Runnable activate = prepareRoutes.apply(owner, rows);
        store.save(owner, rows);
        activate.run();
        if (rows.isEmpty()) providers.remove(owner);
        else providers.put(owner, rows);
        versions.put(owner, UUID.randomUUID().toString());
        return list(owner);
    }

    private String version(String owner) { return versions.getOrDefault(owner, initialVersion); }

    public record Settings(String version, List<View> providers) {}

    public record View(String id, ModelProvider.Protocol protocol, String baseUrl, String model, int priority,
                       boolean enabled, boolean hasApiKey, String workspaceId, boolean streamUsage,
                       String reasoningEffort) {
        static View from(ModelProvider provider) {
            return new View(provider.id(), provider.protocol(), provider.baseUrl(), provider.model(), provider.priority(),
                    provider.enabled(), provider.apiKey() != null && !provider.apiKey().isBlank(),
                    provider.workspaceId(), provider.streamUsage(), provider.reasoningEffort());
        }
    }

    public static final class Conflict extends IllegalStateException {}
    public static final class OriginNotAllowed extends IllegalArgumentException {}
}
