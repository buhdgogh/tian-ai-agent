package com.tianaiagent.task;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskRecord {
    private String id;
    private TaskType type;
    private TaskStatus status;
    private String ownerId;
    private int progress;
    private String stage;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Object result;
    private String error;
    private List<String> events = new ArrayList<>();
    private String createdAt;
    private String startedAt;
    private String finishedAt;

    public TaskRecord() {}

    public TaskRecord(String id, TaskType type, String ownerId, Map<String, Object> input) {
        this.id = id;
        this.type = type;
        this.ownerId = ownerId;
        this.input = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        this.status = TaskStatus.PENDING;
        this.stage = "等待执行";
        this.createdAt = Instant.now().toString();
    }

    public TaskRecord copy() {
        TaskRecord copy = new TaskRecord();
        copy.id = id;
        copy.type = type;
        copy.status = status;
        copy.ownerId = ownerId;
        copy.progress = progress;
        copy.stage = stage;
        copy.input = new LinkedHashMap<>(input);
        copy.result = result;
        copy.error = error;
        copy.events = new ArrayList<>(events);
        copy.createdAt = createdAt;
        copy.startedAt = startedAt;
        copy.finishedAt = finishedAt;
        return copy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public TaskType getType() { return type; }
    public void setType(TaskType type) { this.type = type; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> input) { this.input = input; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public List<String> getEvents() { return events; }
    public void setEvents(List<String> events) { this.events = events; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
}
