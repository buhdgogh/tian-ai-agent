package com.tianaiagent.domain.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.function.Consumer;

/** Correlates read-only browser requests with one authenticated Agent run. */
@Service
public class LocalWorkspaceReads {
    public static final String LIST_TOOL = "listWorkspaceFiles";
    public static final String READ_TOOL = "readWorkspaceFile";
    static final int DESKTOP_TEST_REPLY_SECONDS = 100;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public void open(String runId, String ownerId, Consumer<Request> send) {
        open(runId, ownerId, send, false);
    }

    public void openDesktop(String runId, String ownerId, Consumer<Request> send) {
        open(runId, ownerId, send, true);
    }

    private void open(String runId, String ownerId, Consumer<Request> send, boolean desktop) {
        if (sessions.putIfAbsent(runId, new Session(ownerId, send, desktop)) != null) {
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
        return reply(ownerId, runId, requestId, result, false);
    }

    public boolean replyDesktop(String ownerId, String runId, String requestId, JsonNode result) {
        return reply(ownerId, runId, requestId, result, true);
    }

    private boolean reply(String ownerId, String runId, String requestId, JsonNode result, boolean desktop) {
        Session session = sessions.get(runId);
        if (session == null || session.desktop != desktop || !session.ownerId.equals(ownerId)) return false;
        var pending = session.pending.get(requestId);
        if (pending == null || pending.isDone()) return false;
        if (!result.isObject() || !result.path("ok").isBoolean() || result.toString().length() > 64_000) {
            throw new IllegalArgumentException("工作区读取结果格式或大小无效");
        }
        return pending.complete(result);
    }

    public JsonNode request(String runId, Action action, String path, Integer offset) {
        if (!Role.INSPECT.allows(action)) throw new IllegalArgumentException("浏览器工作区仅支持读取");
        String relative = path == null ? "" : path;
        if (relative.length() > 1024 || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.chars().anyMatch(Character::isISOControl)
                || (relative.isEmpty() ? action == Action.READ : invalidSegments(relative))) {
            throw new IllegalArgumentException("请使用工作区内的相对路径");
        }
        int start = offset == null ? 0 : offset;
        if (start < 0 || start > 1024 * 1024) throw new IllegalArgumentException("偏移量无效");

        return exchange(runId, new Request(UUID.randomUUID().toString(), action, relative, start, null, null, null));
    }

    public JsonNode requestDesktop(String runId, Role role, Action action, String path, String text, String replacement) {
        if (!role.allows(action)) throw new IllegalArgumentException("工作区角色无此权限");
        String relative = path == null ? "" : path;
        if (relative.length() > 1024 || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.chars().anyMatch(Character::isISOControl)
                || (relative.isEmpty() ? action != Action.LIST && action != Action.TEST : invalidSegments(relative))) {
            throw new IllegalArgumentException("请使用工作区内的相对路径");
        }
        return exchange(runId, new Request(UUID.randomUUID().toString(), action, relative, 0, role, text, replacement));
    }

    public void prepareDesktop(String runId) {
        var reply = exchange(runId, new Request(UUID.randomUUID().toString(), Action.PREPARE, "", 0,
                Role.INSPECT, null, null));
        if (!reply.path("ok").asBoolean()) throw new IllegalStateException("桌面沙箱启动失败");
    }

    public List<Receipt> receipts(String runId) {
        Session session = sessions.get(runId);
        return session == null ? List.of() : List.copyOf(session.receipts);
    }

    private JsonNode exchange(String runId, Request request) {
        Session session = sessions.get(runId);
        boolean desktop = request.role != null;
        if (session == null || session.desktop != desktop) throw new IllegalStateException("工作区已断开");
        var result = new CompletableFuture<JsonNode>();
        session.pending.put(request.requestId, result);
        try {
            if (sessions.get(runId) != session) throw new IllegalStateException("工作区已断开");
            session.send.accept(request);
            int timeout = switch (request.action) {
                case PREPARE -> 150;
                case TEST -> DESKTOP_TEST_REPLY_SECONDS;
                default -> desktop ? 25 : 15;
            };
            JsonNode reply = result.get(timeout, TimeUnit.SECONDS);
            if (desktop && request.action != Action.PREPARE) {
                boolean test = request.action == Action.TEST;
                session.receipts.add(new Receipt(request.role, request.action, request.path,
                        reply.path("ok").asBoolean(), reply.path("exitCode").isIntegralNumber()
                                ? reply.path("exitCode").asInt() : null,
                        test ? request.text : null,
                        test ? testOutput(reply) : null));
            }
            return reply;
        } catch (TimeoutException e) {
            throw new IllegalStateException("工作区工具超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工作区工具已中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("工作区已断开", e);
        } finally {
            session.pending.remove(request.requestId);
        }
    }

    private static String testOutput(JsonNode reply) {
        String output = reply.path("stdout").asText("").strip();
        String error = reply.path("stderr").asText(reply.path("message").asText("")).strip();
        if (output.isEmpty()) return error;
        return error.isEmpty() ? output : output + "\n" + error;
    }

    private static boolean invalidSegments(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return true;
        }
        return false;
    }

    public enum Action { PREPARE, LIST, READ, WRITE, REPLACE, TEST }

    public enum Role {
        INSPECT, EDIT, TEST, REVIEW;
        public boolean allows(Action action) {
            return action == Action.LIST || action == Action.READ
                    || this == EDIT && (action == Action.WRITE || action == Action.REPLACE)
                    || this == TEST && action == Action.TEST;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(String requestId, Action action, String path, int offset, Role role, String text, String replacement) {}
    public record Receipt(Role role, Action action, String path, boolean ok, Integer exitCode,
                          String command, String output) {}

    private record Session(String ownerId, Consumer<Request> send, boolean desktop,
                           ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending,
                           ConcurrentLinkedQueue<Receipt> receipts) {
        Session(String ownerId, Consumer<Request> send, boolean desktop) {
            this(ownerId, send, desktop, new ConcurrentHashMap<>(), new ConcurrentLinkedQueue<>());
        }
    }
}
