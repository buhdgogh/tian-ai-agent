package com.tianaiagent.controller;

import com.tianaiagent.task.AsyncTaskCenter;
import com.tianaiagent.task.TaskRecord;
import com.tianaiagent.task.TaskType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tasks")
public class TaskController {
    private final AsyncTaskCenter taskCenter;

    public TaskController(AsyncTaskCenter taskCenter) { this.taskCenter = taskCenter; }

    @GetMapping("/{taskId}")
    public TaskRecord get(@PathVariable String taskId) { return taskCenter.get(taskId); }

    @GetMapping
    public List<TaskRecord> list(@RequestParam(required = false) String ownerId,
                                 @RequestParam(required = false) TaskType type) {
        return taskCenter.list(ownerId, type);
    }

    @DeleteMapping("/{taskId}")
    public TaskRecord cancel(@PathVariable String taskId) { return taskCenter.cancel(taskId); }
}
