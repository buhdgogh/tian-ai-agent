package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.model.entity.AgentRun;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 显式传递执行关联 ID，线程池复用时恢复原上下文。 */
public final class AgentRunContext {
    public static final Class<AgentRunAudit.Run> REACTOR_KEY = AgentRunAudit.Run.class;
    private static final ThreadLocal<AgentRunAudit.Run> CURRENT = new ThreadLocal<>();

    private AgentRunContext() {}

    public static AgentRunAudit.Run current() { return CURRENT.get(); }

    public static <T> T within(AgentRunAudit.Run run, Supplier<T> work) {
        AgentRunAudit.Run previous = CURRENT.get();
        if (run == null) CURRENT.remove(); else CURRENT.set(run);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static Runnable wrap(Runnable work) {
        var captured = current();
        return () -> within(captured, () -> { work.run(); return null; });
    }

    public static <T> T stage(String name, Supplier<T> work) {
        var run = current();
        if (run == null) return work.get();
        Instant start = Instant.now();
        long nanos = System.nanoTime();
        boolean success = false;
        try {
            T result = work.get();
            success = true;
            return result;
        } finally {
            run.event(new AgentRun.Event("STAGE", name, null, null, success ? "COMPLETED" : "FAILED",
                    start, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos), null, null, null, null));
        }
    }
}
