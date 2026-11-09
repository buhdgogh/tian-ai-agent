package com.tianaiagent.infrastructure.adapter.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.domain.task.adapter.repository.TaskRepository;
import com.tianaiagent.types.storage.FileConstant;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Repository
public class FileTaskRepository implements TaskRepository {
    private final Path taskDir = Path.of(FileConstant.FILE_SAVE_DIR, "tasks");
    private final ObjectMapper objectMapper;

    public FileTaskRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<TaskRecord> loadAll() throws IOException {
        Files.createDirectories(taskDir);
        List<TaskRecord> tasks = new ArrayList<>();
        try (var files = Files.list(taskDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    tasks.add(objectMapper.readValue(path.toFile(), TaskRecord.class));
                } catch (IOException ignored) {
                    // 单个损坏任务不阻断应用启动。
                }
            });
        }
        return tasks;
    }

    @Override
    public void save(TaskRecord task) {
        try {
            Files.createDirectories(taskDir);
            Path target = taskDir.resolve(task.getId() + ".json");
            Path temp = taskDir.resolve(task.getId() + ".json.tmp");
            objectMapper.writeValue(temp.toFile(), task);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("任务状态持久化失败", e);
        }
    }
}
