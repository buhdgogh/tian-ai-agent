package com.tianaiagent.agent;

import com.tianaiagent.agent.model.AgentState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentStreamHistoryTest {
    @Test void streamReportsGeneratedOutputBeforeCompleting() throws Exception {
        var agent = new BaseAgent() {
            @Override public String step() { setState(AgentState.FINISHED); return "最终回复"; }
        };
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        agent.setStreamObserver((text, failure) -> { result.set(text); error.set(failure); observed.countDown(); });
        agent.runStream("用户问题");
        assertTrue(observed.await(3, TimeUnit.SECONDS));
        assertEquals("最终回复", result.get()); assertNull(error.get());
    }
}
