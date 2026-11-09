package com.tianaiagent.domain.knowledge.adapter.repository;

import com.tianaiagent.domain.knowledge.model.aggregate.ParseTask;

import java.util.Optional;
import java.util.List;

public interface ParseTaskRepository {
    void saveTask(ParseTask task);

    Optional<ParseTask> findTask(String taskId);

    List<String> findCacheKeysBySpace(String spaceId);
}
