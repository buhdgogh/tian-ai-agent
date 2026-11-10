package com.tianaiagent.domain.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Correlates read-only browser requests with one authenticated Agent run. */
@Service
public class LocalWorkspaceReads {
    public static final String LIST_TOOL = "listWorkspaceFiles";
    public static final String READ_TOOL = "readWorkspaceFile";

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public void open(String runId, String ownerId, Consumer<Request> send) {
        if (sessions.putIfAbsent(runId, new Session(ownerId, send)) != null) {
            throw new IllegalStateException("工作区连接已存在");
        }
    }

    public boolean active(String runId) {
        return sessions.containsKey(runId);
    }

    public void close(String runId) {
        Session session = sessions.remove(runId);
        if (session != null) {
            session.pending.values().forEach(result ->
                    result.completeExceptionally(new IllegalStateException("浏览器工作区已断开")));
        }
    }

    public boolean reply(String ownerId, String runId, String requestId, JsonNode result) {
        Session session = sessions.get(runId);
        if (session == null || !session.ownerId.equals(ownerId)) return false;
        var pending = session.pending.get(requestId);
        if (pending == null || pending.isDone()) return false;
        if (!result.isObject() || !result.path("ok").isBoolean() || result.toString().length() > 64_000) {
            throw new IllegalArgumentException("工作区读取结果格式或大小无效");
        }
        return pending.complete(result);
    }

    public JsonNode request(String runId, Action action, String path, Integer offset) {
        String relative = path == null ? "" : path;
        if (relative.length() > 1024 || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.chars().anyMatch(Character::isISOControl)
                || (relative.isEmpty() ? action == Action.READ : invalidSegments(relative))) {
            throw new IllegalArgumentException("请使用工作区内的相对路径");
        }
        int start = offset == null ? 0 : offset;
        if (start < 0 || start > 1024 * 1024) throw new IllegalArgumentException("偏移量无效");

        Session session = sessions.get(runId);
        if (session == null) throw new IllegalStateException("浏览器工作区已断开");
        String requestId = UUID.randomUUID().toString();
        var result = new CompletableFuture<JsonNode>();
        session.pending.put(requestId, result);
        try {
            if (sessions.get(runId) != session) throw new IllegalStateException("浏览器工作区已断开");
            session.send.accept(new Request(requestId, action, relative, start));
            return result.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("浏览器工作区读取超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("浏览器工作区读取已中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("浏览器工作区已断开", e);
        } finally {
            session.pending.remove(requestId);
        }
    }

    private static boolean invalidSegments(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return true;
        }
        return false;
    }

    public enum Action { LIST, READ }

    public record Request(String requestId, Action action, String path, int offset) {}

    private record Session(String ownerId, Consumer<Request> send,
                           ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending) {
        Session(String ownerId, Consumer<Request> send) {
            this(ownerId, send, new ConcurrentHashMap<>());
        }
    }
}
