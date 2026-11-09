package com.tianaiagent.trigger.http;

import com.tianaiagent.domain.task.service.AsyncTaskCenter;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.domain.task.model.valobj.TaskType;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.server.ResponseStatusException;
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
    public TaskRecord get(@PathVariable String taskId, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return taskCenter.getOwned(taskId, identity.ownerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    public List<TaskRecord> list(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity,
                                 @RequestParam(required = false) TaskType type) {
        return taskCenter.list(identity.ownerId(), type);
    }

    @DeleteMapping("/{taskId}")
    public TaskRecord cancel(@PathVariable String taskId, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return taskCenter.cancelOwned(taskId, identity.ownerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
