package com.tianaiagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.constant.FileConstant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Explicit development compatibility mode, never an automatic MySQL fallback. */
@Repository
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "file")
public class FileTaskRepository implements TaskRepository {
    private final ObjectMapper mapper;
    private final Path directory;

    @org.springframework.beans.factory.annotation.Autowired
    public FileTaskRepository(ObjectMapper mapper) {
        this(mapper, Path.of(FileConstant.FILE_SAVE_DIR, "tasks"));
    }

    public FileTaskRepository(ObjectMapper mapper, Path directory) {
        this.mapper = mapper;
        this.directory = directory;
    }

    public synchronized void save(TaskRecord task) {
        try {
            Files.createDirectories(directory);
            Path target = path(task.getId());
            Path temp = directory.resolve(task.getId() + ".json.tmp");
            mapper.writeValue(temp.toFile(), task);
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException error) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw new IllegalStateException("任务状态持久化失败", error); }
    }

    public Optional<TaskRecord> find(String id) {
        Path target = path(id);
        if (!Files.exists(target)) return Optional.empty();
        try { return Optional.of(mapper.readValue(target.toFile(), TaskRecord.class)); }
        catch (IOException error) { throw new IllegalStateException("读取任务失败", error); }
    }

    public List<TaskRecord> list(String ownerId, TaskType type) {
        List<TaskRecord> tasks = new ArrayList<>();
        if (!Files.isDirectory(directory)) return tasks;
        try (var paths = Files.list(directory)) {
            paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{32}\\.json"))
                    .forEach(p -> {
                        try { tasks.add(mapper.readValue(p.toFile(), TaskRecord.class)); }
                        catch (IOException ignored) { /* Legacy corrupt files are skipped. */ }
                    });
        } catch (IOException error) { throw new IllegalStateException("查询任务失败", error); }
        return tasks.stream().filter(t -> ownerId == null || ownerId.equals(t.getOwnerId()))
                .filter(t -> type == null || type == t.getType())
                .sorted(Comparator.comparing(TaskRecord::getCreatedAt).reversed()).toList();
    }

    private Path path(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("非法任务 ID");
        return directory.resolve(id + ".json");
    }
}
