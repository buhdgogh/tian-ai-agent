package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.repository.AgentRunRepository;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.types.storage.FileConstant;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class FileAgentRunRepository implements AgentRunRepository {
    private final Path directory = Path.of(FileConstant.FILE_SAVE_DIR, "agent-runs");
    private final ObjectMapper mapper;

    public FileAgentRunRepository(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public void save(AgentRun run) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(run.summary().id() + ".json");
            Path temporary = Files.createTempFile(directory, ".run-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), run);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Agent 运行审计保存失败", e);
        }
    }

    @Override
    public List<AgentRun.Summary> recent(String ownerId, int limit) {
        try (var files = runFiles()) {
            return files.flatMap(path -> read(path).stream()).map(AgentRun::summary)
                    .filter(summary -> ownerId.equals(summary.ownerId()))
                    .sorted(Comparator.comparing(AgentRun.Summary::startedAt).reversed()).limit(limit).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Agent 运行审计读取失败", e);
        }
    }

    void forEachRun(Consumer<AgentRun> action) {
        try (var files = runFiles()) {
            files.forEach(path -> read(path).ifPresent(action));
        } catch (IOException e) {
            throw new IllegalStateException("Agent 运行审计读取失败", e);
        }
    }

    private Stream<Path> runFiles() throws IOException {
        if (!Files.isDirectory(directory)) return Stream.empty();
        return Files.list(directory).filter(path -> path.getFileName().toString().matches("[0-9a-f]{32}\\.json"));
    }

    @Override
    public Optional<AgentRun> find(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return Optional.empty();
        return read(directory.resolve(id + ".json"));
    }

    private Optional<AgentRun> read(Path path) {
        try {
            return Optional.of(mapper.readValue(path.toFile(), AgentRun.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
