package com.tianaiagent.domain.task.adapter.repository;

import com.tianaiagent.domain.task.model.aggregate.TaskRecord;

import java.io.IOException;
import java.util.List;

public interface TaskRepository {
    List<TaskRecord> loadAll() throws IOException;

    void save(TaskRecord task);
}
