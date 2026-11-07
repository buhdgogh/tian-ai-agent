package com.tianaiagent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.task.TaskRecord;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;

/** Thread-safe SSE transport with a bounded, public execution timeline and throttled persistence. */
public class LiveAnswerSession implements AgentOutput {
    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    private final StringBuilder answer = new StringBuilder();
    private final LinkedHashMap<String, Map<String, Object>> trace = new LinkedHashMap<>();
    private BiConsumer<String, Map<String, Object>> checkpoint = (text, metadata) -> {};
    private boolean connected = true, closed, answering;
    private long sequence, lastSave;

    public LiveAnswerSession(SseEmitter emitter, ObjectMapper mapper) {
        this.emitter = emitter; this.mapper = mapper;
        emitter.onCompletion(() -> disconnect()); emitter.onError(error -> disconnect()); emitter.onTimeout(() -> { disconnect(); emitter.complete(); });
    }
    private synchronized void disconnect() { connected = false; }
    public synchronized void checkpointWith(BiConsumer<String, Map<String, Object>> consumer) { checkpoint = consumer; }
    public synchronized String answer() { return answer.toString(); }
    public synchronized List<Map<String, Object>> trace() { return new ArrayList<>(trace.values()); }
    public synchronized Map<String, Object> metadata() { return Map.of("agentTrace", trace(), "streamPhase", answering ? "answer" : "analysis"); }

    @Override public synchronized void event(String type, Map<String, Object> data) {
        if (closed) return;
        if (type.equals("answer_start")) answering = true;
        if (type.equals("answer_delta")) answer.append(data.getOrDefault("delta", ""));
        if (type.equals("progress") || type.equals("tool_start") || type.equals("tool_end")) {
            String id = String.valueOf(data.getOrDefault("id", "stage-" + (sequence + 1)));
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", id); step.put("type", type.startsWith("tool_") ? "tool" : "thinking");
            step.put("title", data.getOrDefault("title", "正在处理")); step.put("detail", data.getOrDefault("detail", ""));
            step.put("status", data.getOrDefault("status", type.equals("tool_start") ? "running" : "completed"));
            if (data.containsKey("tool")) step.put("tool", data.get("tool"));
            if (data.containsKey("durationMs")) step.put("durationMs", data.get("durationMs"));
            trace.put(id, step);
            if (trace.size() > 100) trace.remove(trace.keySet().iterator().next());
        }
        Map<String, Object> event = new LinkedHashMap<>(data);
        event.put("type", type); event.put("seq", ++sequence); event.put("time", Instant.now().toString());
        send(type, event);
        if (!type.equals("done") && (type.equals("answer_start") || type.equals("progress") || type.startsWith("tool_") || System.currentTimeMillis() - lastSave >= 300)) save();
    }
    private void send(String type, Map<String, Object> event) {
        if (!connected) return;
        try { emitter.send(SseEmitter.event().id(String.valueOf(sequence)).name(type).data(event, MediaType.APPLICATION_JSON)); }
        catch (Exception error) { connected = false; } // Persistent task continues; refresh can recover it.
    }
    private void save() { checkpoint.accept(answer.toString(), metadata()); lastSave = System.currentTimeMillis(); }
    @Override public synchronized void checkpoint() { if (!closed) save(); }
    public synchronized void heartbeat() {
        if (closed || !connected) return;
        try { emitter.send(SseEmitter.event().comment("heartbeat")); }
        catch (Exception error) { connected = false; }
    }
    public synchronized boolean connected() { return connected && !closed; }
    @Override public synchronized void terminal(TaskRecord task) {
        if (closed) return;
        var result = mapper.valueToTree(task.getResult());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("status", task.getStatus().name()); event.put("taskId", task.getId()); event.put("trace", trace());
        event.put("answer", result == null ? answer() : result.path("answer").asText(answer()));
        if (result != null && result.has("workflow")) event.put("workflow", result.get("workflow"));
        if (task.getError() != null) event.put("error", "任务执行未完成，请重试或查看任务状态。");
        event("done", event); closed = true; emitter.complete();
    }
    public synchronized void failBeforeSubmission() {
        if (closed) return;
        event("done", Map.of("status", "FAILED", "error", "任务提交失败，请稍后重试。")); closed = true; emitter.complete();
    }
}
