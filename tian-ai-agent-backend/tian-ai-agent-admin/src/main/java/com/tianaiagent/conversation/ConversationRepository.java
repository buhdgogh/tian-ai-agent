package com.tianaiagent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import com.tianaiagent.user.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Durable user-visible history. Redis/File remains an independent, bounded model context. */
@Repository
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class ConversationRepository {
    private final AgentDatabase database;
    private final ObjectMapper mapper;
    public ConversationRepository(AgentDatabase database, ObjectMapper mapper) { this.database = database; this.mapper = mapper; }

    public Session ensure(String userId, String chatId, String type, String title) {
        validate(userId, chatId, type);
        try (var connection = database.connection()) { return ensure(connection, userId, chatId, type, title); }
        catch (Exception error) { throw storage("创建会话", error); }
    }

    private Session ensure(Connection connection, String userId, String chatId, String type, String title) throws Exception {
        String now = Instant.now().toString();
        try (var insert = connection.prepareStatement("INSERT INTO agent_chat_session(id,user_id,chat_id,type,title,created_at,updated_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id")) {
            insert.setString(1, uuid()); insert.setString(2, userId); insert.setString(3, chatId); insert.setString(4, type);
            insert.setString(5, clipped(title == null || title.isBlank() ? "新对话" : title, 100));
            insert.setString(6, now); insert.setString(7, now); insert.executeUpdate();
        }
        return find(connection, userId, chatId, type, true);
    }

    public Turn begin(String userId, String chatId, String type, String question) {
        return begin(userId, chatId, type, question, null, null);
    }

    /** A revision is a new immutable attempt; replacement and reservation commit together. */
    public Turn begin(String userId, String chatId, String type, String question, String requestedTurnId, String replacesTurnId) {
        validate(userId, chatId, type);
        if (question == null || question.isBlank()) throw new IllegalArgumentException("消息不能为空");
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                Session session = ensure(connection, userId, chatId, type, question);
                String turnId = requestedTurnId == null ? uuid() : requestedTurnId;
                if (!turnId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法轮次ID");
                try (var check = connection.prepareStatement("SELECT id FROM agent_chat_message WHERE session_id=? AND turn_id=?")) {
                    check.setString(1, session.sessionId()); check.setString(2, turnId);
                    try (var rows = check.executeQuery()) { if (rows.next()) throw conflict("本轮请求已提交，请刷新会话"); }
                }
                boolean revised = replacesTurnId != null;
                if (revised) {
                    ChatMessage previous = latestReply(connection, session.sessionId(), replacesTurnId);
                    if (!List.of("CANCELLED", "FAILED").contains(previous.status())) throw conflict("请先停止上一轮回答；已完成的回答不能在此修改");
                    try (var select = connection.prepareStatement("SELECT id,status,metadata_json FROM agent_chat_message WHERE session_id=? AND turn_id=? FOR UPDATE")) {
                        select.setString(1, session.sessionId()); select.setString(2, replacesTurnId);
                        try (var rows = select.executeQuery()) {
                            while (rows.next()) {
                                Map<String, Object> metadata = new LinkedHashMap<>(mapper.readValue(rows.getString("metadata_json"), new TypeReference<Map<String, Object>>() {}));
                                metadata.put("supersededBy", turnId); metadata.put("previousStatus", rows.getString("status"));
                                try (var replace = connection.prepareStatement("UPDATE agent_chat_message SET status='SUPERSEDED',metadata_json=?,updated_at=? WHERE id=?")) {
                                    replace.setString(1, mapper.writeValueAsString(metadata)); replace.setString(2, Instant.now().toString()); replace.setLong(3, rows.getLong("id")); replace.executeUpdate();
                                }
                            }
                        }
                    }
                }
                String now = Instant.now().toString();
                long userMessage = insertMessage(connection, session.sessionId(), turnId, "USER", question, "COMPLETED", now);
                long reply = insertMessage(connection, session.sessionId(), turnId, "ASSISTANT", "", "RUNNING", now);
                if (revised) {
                    try (var meta = connection.prepareStatement("UPDATE agent_chat_message SET metadata_json=? WHERE session_id=? AND turn_id=?")) {
                        meta.setString(1, mapper.writeValueAsString(Map.of("replacesTurnId", replacesTurnId)));
                        meta.setString(2, session.sessionId()); meta.setString(3, turnId); meta.executeUpdate();
                    }
                }
                try (var update = connection.prepareStatement("UPDATE agent_chat_session SET title=CASE WHEN message_count=0 AND title='新对话' THEN ? ELSE title END,message_count=message_count+2,updated_at=? WHERE id=?")) {
                    update.setString(1, clipped(question, 100)); update.setString(2, now); update.setString(3, session.sessionId()); update.executeUpdate();
                }
                connection.commit();
                return new Turn(session, turnId, userMessage, reply);
            } catch (Exception error) { connection.rollback(); throw error; }
        } catch (Exception error) { throw storage("保存用户消息", error); }
    }

    public InterruptedTurn interrupt(String userId, String chatId, String type, String turnId) {
        validate(userId, chatId, type);
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                Session session = find(connection, userId, chatId, type, true);
                ChatMessage reply = latestReply(connection, session.sessionId(), turnId);
                if ("RUNNING".equals(reply.status())) {
                    try (var update = connection.prepareStatement("UPDATE agent_chat_message SET status='CANCELLED',updated_at=? WHERE id=? AND status='RUNNING'")) {
                        update.setString(1, Instant.now().toString()); update.setLong(2, reply.id()); update.executeUpdate();
                    }
                }
                connection.commit();
                return new InterruptedTurn(turnId, "RUNNING".equals(reply.status()) ? "CANCELLED" : reply.status(),
                        reply.metadata().get("taskId") instanceof String taskId ? taskId : null);
            } catch (Exception error) { connection.rollback(); throw error; }
        } catch (Exception error) { throw storage("中断回答", error); }
    }

    private ChatMessage latestReply(Connection connection, String sessionId, String turnId) throws Exception {
        try (var select = connection.prepareStatement("SELECT * FROM agent_chat_message WHERE session_id=? AND status<>'SUPERSEDED' ORDER BY id DESC LIMIT 1 FOR UPDATE")) {
            select.setString(1, sessionId);
            try (var row = select.executeQuery()) {
                if (!row.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本轮提问尚未保存，请稍后重试");
                if (!turnId.equals(row.getString("turn_id"))) throw conflict("只能修改最近一轮提问，请刷新会话");
                return new ChatMessage(row.getLong("id"), turnId, row.getString("role"), row.getString("content"), row.getString("status"),
                        row.getString("created_at"), row.getString("updated_at"), mapper.readValue(row.getString("metadata_json"), new TypeReference<>() {}));
            }
        }
    }

    public boolean isRunning(long replyId) {
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT status FROM agent_chat_message WHERE id=?")) {
            statement.setLong(1, replyId);
            try (var row = statement.executeQuery()) { return row.next() && "RUNNING".equals(row.getString(1)); }
        } catch (Exception error) { throw storage("检查回答状态", error); }
    }

    private long insertMessage(Connection connection, String session, String turn, String role, String content,
                               String status, String now) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO agent_chat_message(session_id,turn_id,role,content,status,metadata_json,created_at,updated_at) VALUES(?,?,?,?,?,'{}',?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, session); statement.setString(2, turn); statement.setString(3, role);
            statement.setString(4, content); statement.setString(5, status); statement.setString(6, now); statement.setString(7, now);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) { if (!keys.next()) throw new IllegalStateException("未返回消息ID"); return keys.getLong(1); }
        }
    }

    /** First terminal outcome wins, including stream timeout/cancel versus a late completion. */
    public void finish(long replyId, String content, String status, Map<String, Object> metadata) {
        if (!List.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) throw new IllegalArgumentException("非法消息状态");
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                // Use the same session -> reply lock order as interrupt/revise.
                try (var lock = connection.prepareStatement("SELECT id FROM agent_chat_session WHERE id=(SELECT session_id FROM agent_chat_message WHERE id=?) FOR UPDATE")) {
                    lock.setLong(1, replyId); try (var ignored = lock.executeQuery()) { while (ignored.next()) { /* acquire lock */ } }
                }
                try (var statement = connection.prepareStatement("UPDATE agent_chat_message SET content=COALESCE(?,content),status=?,metadata_json=?,updated_at=? WHERE id=? AND status='RUNNING'")) {
                    statement.setString(1, content); statement.setString(2, status);
                    Map<String, Object> merged = new LinkedHashMap<>();
                    try (var existing = connection.prepareStatement("SELECT metadata_json FROM agent_chat_message WHERE id=?")) {
                        existing.setLong(1, replyId); try (var row = existing.executeQuery()) { if (row.next()) merged.putAll(mapper.readValue(row.getString(1), new TypeReference<Map<String, Object>>() {})); }
                    }
                    merged.putAll(metadata);
                    merged.remove("workflowProgress");
                    statement.setString(3, mapper.writeValueAsString(merged)); statement.setString(4, now); statement.setLong(5, replyId);
                    if (statement.executeUpdate() > 0) {
                        try (var update = connection.prepareStatement("UPDATE agent_chat_session SET updated_at=? WHERE id=(SELECT session_id FROM agent_chat_message WHERE id=?)")) {
                            update.setString(1, now); update.setLong(2, replyId); update.executeUpdate();
                        }
                    }
                }
                connection.commit();
            } catch (Exception error) { connection.rollback(); throw error; }
        } catch (Exception error) { throw storage("保存助手消息", error); }
    }

    public void updateRunningMetadata(long replyId, Map<String, Object> metadata) {
        updateRunningContent(replyId, null, metadata);
    }

    public void updateRunningContent(long replyId, String content, Map<String, Object> metadata) {
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> merged = new LinkedHashMap<>();
                try (var select = connection.prepareStatement("SELECT metadata_json FROM agent_chat_message WHERE id=? AND status='RUNNING' FOR UPDATE")) {
                    select.setLong(1, replyId);
                    try (var row = select.executeQuery()) {
                        if (!row.next()) { connection.commit(); return; }
                        merged.putAll(mapper.readValue(row.getString(1), new TypeReference<Map<String, Object>>() {}));
                    }
                }
                merged.putAll(metadata);
                try (var statement = connection.prepareStatement("UPDATE agent_chat_message SET content=COALESCE(?,content),metadata_json=?,updated_at=? WHERE id=? AND status='RUNNING'")) {
                    statement.setString(1, content); statement.setString(2, mapper.writeValueAsString(merged)); statement.setString(3, Instant.now().toString()); statement.setLong(4, replyId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception error) { connection.rollback(); throw error; }
        } catch (Exception error) { throw storage("保存运行中任务信息", error); }
    }

    public Session find(String userId, String chatId, String type) {
        validate(userId, chatId, type);
        try (var connection = database.connection()) { return find(connection, userId, chatId, type, false); }
        catch (Exception error) { throw storage("查询会话", error); }
    }
    private Session find(Connection connection, String userId, String chatId, String type, boolean lock) throws Exception {
        try (var statement = connection.prepareStatement("SELECT * FROM agent_chat_session WHERE user_id=? AND chat_id=? AND type=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, userId); statement.setString(2, chatId); statement.setString(3, type);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
                return session(rows);
            }
        }
    }

    public List<Session> list(String userId, String type) {
        validate(userId, "list", type);
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT * FROM agent_chat_session WHERE user_id=? AND type=? ORDER BY updated_at DESC,id DESC")) {
            statement.setString(1, userId); statement.setString(2, type);
            List<Session> result = new ArrayList<>();
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(session(rows)); }
            return result;
        } catch (Exception error) { throw storage("查询会话列表", error); }
    }

    public List<ChatMessage> messages(String userId, String chatId, String type) {
        Session session = find(userId, chatId, type);
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT * FROM agent_chat_message WHERE session_id=? ORDER BY id")) {
            statement.setString(1, session.sessionId());
            List<ChatMessage> result = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new ChatMessage(rows.getLong("id"), rows.getString("turn_id"), rows.getString("role"),
                        rows.getString("content"), rows.getString("status"), rows.getString("created_at"), rows.getString("updated_at"),
                        mapper.readValue(rows.getString("metadata_json"), new TypeReference<>() {})));
            }
            return result;
        } catch (Exception error) { throw storage("查询会话消息", error); }
    }

    public void delete(String userId, String chatId, String type) {
        validate(userId, chatId, type);
        try (var connection = database.connection(); var statement = connection.prepareStatement("DELETE FROM agent_chat_session WHERE user_id=? AND chat_id=? AND type=?")) {
            statement.setString(1, userId); statement.setString(2, chatId); statement.setString(3, type); statement.executeUpdate();
        } catch (Exception error) { throw storage("删除会话", error); }
    }

    public void recoverInterrupted() {
        try (var connection = database.connection(); var statement = connection.prepareStatement("UPDATE agent_chat_message SET status='FAILED',content=CASE WHEN content='' THEN '服务重启导致本轮对话中断' ELSE content END,updated_at=? WHERE status='RUNNING'")) {
            statement.setString(1, Instant.now().toString()); statement.executeUpdate();
        } catch (Exception error) { throw storage("恢复中断会话", error); }
    }

    private static Session session(ResultSet row) throws Exception {
        return new Session(row.getString("chat_id"), row.getString("id"), row.getString("user_id"), row.getString("type"),
                row.getString("title"), row.getInt("message_count"), row.getString("created_at"), row.getString("updated_at"));
    }
    private static void validate(String userId, String chatId, String type) {
        UserRepository.validate(userId);
        if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("非法会话ID");
        if (!List.of("medical", "agent", "rag").contains(type)) throw new IllegalArgumentException("会话type只支持medical、agent、rag");
    }
    private static RuntimeException storage(String operation, Exception error) {
        return error instanceof ResponseStatusException status ? status : new IllegalStateException(operation + "失败", error);
    }
    private static String clipped(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
    private static String uuid() { return UUID.randomUUID().toString().replace("-", ""); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    // id is the existing frontend chatId. sessionId is also the isolated model-memory key.
    public record Session(String id, String sessionId, String userId, String type, String title,
                          int messageCount, String createdAt, String updatedAt) {}
    public record Turn(Session session, String turnId, long userMessageId, long replyMessageId) {}
    public record InterruptedTurn(String turnId, String status, String taskId) {}
    public record ChatMessage(long id, String turnId, String messageType, String text, String status,
                              String createdAt, String updatedAt, Map<String, Object> metadata) {}
}
