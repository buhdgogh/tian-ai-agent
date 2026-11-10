package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.repository.AgentPresetStore;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset.Kind;
import com.tianaiagent.types.storage.FileConstant;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class FileAgentPresetStore implements AgentPresetStore {
    private final Path root = Path.of(FileConstant.FILE_SAVE_DIR, "agent-presets");
    private final ObjectMapper mapper;

    public FileAgentPresetStore(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public synchronized List<AgentPreset> list(String ownerId, Kind kind) {
        Path directory = directory(ownerId, kind);
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            List<AgentPreset> result = new ArrayList<>();
            for (Path file : files) result.add(mapper.readValue(file.toFile(), AgentPreset.class));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent 配置失败", e);
        }
    }

    @Override
    public synchronized void insert(String ownerId, Kind kind, AgentPreset preset) {
        Path target = directory(ownerId, kind).resolve(preset.id() + ".json");
        if (Files.exists(target)) throw new IllegalStateException("配置 ID 已存在");
        write(target, preset);
    }

    @Override
    public synchronized boolean update(String ownerId, Kind kind, AgentPreset preset) {
        Path target = directory(ownerId, kind).resolve(preset.id() + ".json");
        if (!Files.exists(target)) return false;
        write(target, preset);
        return true;
    }

    @Override
    public synchronized boolean delete(String ownerId, Kind kind, String id) {
        try {
            return Files.deleteIfExists(directory(ownerId, kind).resolve(id + ".json"));
        } catch (IOException e) {
            throw new IllegalStateException("删除 Agent 配置失败", e);
        }
    }

    private Path directory(String ownerId, Kind kind) {
        return root.resolve(ownerId).resolve(kind.name());
    }

    private void write(Path target, AgentPreset preset) {
        try {
            Path directory = Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(directory, ".preset-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), preset);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存 Agent 配置失败", e);
        }
    }
}
