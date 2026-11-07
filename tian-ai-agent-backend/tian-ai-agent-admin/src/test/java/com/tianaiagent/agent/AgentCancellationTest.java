package com.tianaiagent.agent;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentCancellationTest {
    @Test void cancellationAfterThinkingPreventsToolActionsAndFurtherModelSteps() {
        var cancelled = new AtomicBoolean();
        var thoughts = new AtomicInteger();
        var actions = new AtomicInteger();
        var agent = new ReActAgent() {
            @Override public String think() { thoughts.incrementAndGet(); cancelled.set(true); return "等待执行工具"; }
            @Override public boolean shouldAct() { return true; }
            @Override public String act() { actions.incrementAndGet(); return "不应执行"; }
        };
        agent.setMaxSteps(5);
        agent.setCancellationRequested(cancelled::get);
        assertTrue(agent.run("原问题").contains("本轮已中断"));
        assertEquals(1, thoughts.get());
        assertEquals(0, actions.get());
    }

    @Test void anAlreadyCancelledTurnDoesNotStartAnyModelStep() {
        var calls = new AtomicInteger();
        var agent = new BaseAgent() {
            @Override public String step() { calls.incrementAndGet(); return "不应执行"; }
        };
        agent.setCancellationRequested(() -> true);
        assertTrue(agent.run("原问题").contains("本轮已中断"));
        assertEquals(0, calls.get());
    }
}
