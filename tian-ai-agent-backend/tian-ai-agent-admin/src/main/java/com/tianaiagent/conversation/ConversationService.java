package com.tianaiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.task.TaskRecord;
import com.tianaiagent.task.TaskStatus;
import com.tianaiagent.task.TaskType;
import com.tianaiagent.user.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class ConversationService {
    private final ConversationRepository repository;
    private final ObjectMapper mapper;
    private final UserRepository users;
    public ConversationService(ConversationRepository repository, ObjectMapper mapper, UserRepository users) {
        this.repository = repository; this.mapper = mapper; this.users = users;
    }
    @PostConstruct
    public void recover() { repository.recoverInterrupted(); }
    public ConversationRepository repository() { return repository; }
    public String defaultUserId() { return users.defaultUser().userId(); }
    public ConversationRepository.Turn begin(String user, String chat, String type, String message) {
        return repository.begin(user, chat, type, message);
    }
    public void complete(ConversationRepository.Turn turn, String content) {
        boolean error = content != null && (content.startsWith("执行错误") || content.startsWith("❌ 错误"));
        repository.finish(turn.replyMessageId(), content, error ? "FAILED" : "COMPLETED", Map.of());
    }
    public void fail(ConversationRepository.Turn turn, String partial, Throwable error) {
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        repository.finish(turn.replyMessageId(), partial == null || partial.isBlank() ? detail : partial,
                "FAILED", Map.of("error", detail));
    }
    public Flux<String> recordStream(ConversationRepository.Turn turn, Flux<String> source) {
        return Flux.defer(() -> {
            StringBuilder text = new StringBuilder();
            return source.doOnNext(text::append).doOnComplete(() -> complete(turn, text.toString()))
                    .doOnError(error -> fail(turn, text.toString(), error))
                    .doOnCancel(() -> repository.finish(turn.replyMessageId(), text.toString(), "CANCELLED", Map.of()));
        });
    }
    /** Called on persistent task state changes; queued cancellations and submission failures also finish chat replies. */
    public void taskPersisted(TaskRecord task) {
        if (task.getType() == TaskType.AGENT_RESPONSE && task.getInput().get("conversationReplyId") instanceof Number reply) {
            TaskStatus status = task.getStatus();
            if (status == TaskStatus.RUNNING || status == TaskStatus.PENDING) {
                repository.updateRunningMetadata(reply.longValue(), Map.of("taskId", task.getId(), "workflowProgress",
                        Map.of("progress", task.getProgress(), "stage", task.getStage(), "status", status.name())));
            } else if (status == TaskStatus.COMPLETED) {
                var result = mapper.valueToTree(task.getResult());
                Map<String, Object> metadata = new java.util.LinkedHashMap<>(); metadata.put("taskId", task.getId());
                metadata.put("streamPhase", "completed"); metadata.put("agentTrace", result.path("trace"));
                if (result.has("workflow")) metadata.put("workflow", result.get("workflow"));
                repository.finish(reply.longValue(), result.path("answer").asText(""), "COMPLETED", metadata);
            } else {
                repository.finish(reply.longValue(), null, status.name(), Map.of("taskId", task.getId(),
                        "error", task.getError() == null ? "本轮已停止" : task.getError(), "streamPhase", "stopped"));
            }
            return;
        }
        if (task.getType() != TaskType.AGENT_WORKFLOW || !(task.getInput().get("conversationReplyId") instanceof Number reply)) return;
        TaskStatus status = task.getStatus();
        if (status == TaskStatus.RUNNING || status == TaskStatus.PENDING) {
            repository.updateRunningMetadata(reply.longValue(), Map.of("taskId", task.getId(),
                    "workflowProgress", Map.of("progress", task.getProgress(), "stage", task.getStage(), "status", status.name())));
        } else if (status == TaskStatus.COMPLETED) {
            var result = mapper.valueToTree(task.getResult());
            repository.finish(reply.longValue(), result.path("executionResult").asText(""), "COMPLETED",
                    Map.of("taskId", task.getId(), "workflow", result));
        } else if (status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
            repository.finish(reply.longValue(), task.getError() == null ? "任务已取消" : task.getError(), status.name(),
                    Map.of("taskId", task.getId()));
        }
    }
}
