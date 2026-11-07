package com.tianaiagent.observability;

/** Immutable trace identity with shared execution-local Wiki state, explicitly propagated across worker pools. */
public final class ExecutionContext {
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private ExecutionContext() {}
    public record Trace(String taskId, String ownerId, String chatId, String stepId, Integer iteration, String toolCallId,
                        com.tianaiagent.wiki.WikiTraversalState wikiTraversal, com.tianaiagent.stream.AgentOutput output) {
        public Trace(String taskId, String ownerId, String chatId, String stepId, Integer iteration, String toolCallId) {
            this(taskId, ownerId, chatId, stepId, iteration, toolCallId, new com.tianaiagent.wiki.WikiTraversalState(), null);
        }
        public Trace(String taskId, String ownerId, String chatId, String stepId, Integer iteration, String toolCallId, com.tianaiagent.wiki.WikiTraversalState wikiTraversal) {
            this(taskId, ownerId, chatId, stepId, iteration, toolCallId, wikiTraversal, null);
        }
        public Trace step(String id, int round) { return new Trace(taskId, ownerId, chatId, id, round, null, wikiTraversal, output); }
        public Trace tool(String id) { return new Trace(taskId, ownerId, chatId, stepId, iteration, id, wikiTraversal, output); }
        public Trace output(com.tianaiagent.stream.AgentOutput sink) { return new Trace(taskId, ownerId, chatId, stepId, iteration, toolCallId, wikiTraversal, sink); }
    }
    public static Trace current() {
        Trace trace = CURRENT.get();
        return trace == null ? new Trace(null, null, null, null, null, null, null) : trace;
    }
    public static Scope open(Trace trace) {
        Trace previous = CURRENT.get();
        if (trace == null) CURRENT.remove(); else CURRENT.set(trace);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static Runnable wrap(Runnable runnable) {
        Trace snapshot = current();
        return () -> { try (Scope ignored = open(snapshot)) { runnable.run(); } };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
