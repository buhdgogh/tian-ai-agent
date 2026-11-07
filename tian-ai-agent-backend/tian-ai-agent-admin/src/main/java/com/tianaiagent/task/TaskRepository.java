package com.tianaiagent.task;

import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    void save(TaskRecord task);
    Optional<TaskRecord> find(String id);
    List<TaskRecord> list(String ownerId, TaskType type);
}
