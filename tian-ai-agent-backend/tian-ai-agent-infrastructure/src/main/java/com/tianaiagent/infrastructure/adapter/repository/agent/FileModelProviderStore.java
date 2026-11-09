package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.repository.ModelProviderStore;
import com.tianaiagent.domain.agent.model.valobj.ModelProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileModelProviderStore implements ModelProviderStore {
    private final Path config = ModelSettingsCipher.DIRECTORY.resolve("providers.enc");
    private final ModelSettingsCipher encryption = new ModelSettingsCipher();
    private final ObjectMapper mapper;

    public FileModelProviderStore(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public Map<String, List<ModelProvider>> load() {
        if (!Files.exists(config)) return Map.of();
        try {
            return mapper.readValue(encryption.open(Files.readAllBytes(config), null),
                    new TypeReference<Map<String, List<ModelProvider>>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("无法读取模型配置");
        }
    }

    @Override
    public synchronized void save(String owner, List<ModelProvider> rows) {
        Map<String, List<ModelProvider>> providers = new HashMap<>(load());
        if (rows.isEmpty()) providers.remove(owner);
        else providers.put(owner, rows);
        Path temporary = null;
        try {
            byte[] encrypted = encryption.seal(mapper.writeValueAsBytes(providers), null);
            temporary = Files.createTempFile(ModelSettingsCipher.DIRECTORY, ".providers-", ".tmp");
            Files.write(temporary, encrypted);
            Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("模型配置保存失败", e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }
}
