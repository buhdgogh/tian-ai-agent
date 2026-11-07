package com.tianaiagent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.task.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompletableTaskCancellationTest {
    @Test void cancellationInterruptsWorkerAndPreventsQueuedWorkFromStarting() throws Exception {
        var repo = mock(TaskRepository.class); var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var queuedStarted = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var tasks = new AsyncTaskCenter(new ObjectMapper(), executor, repo);
            var running = tasks.submit(TaskType.AGENT_RESPONSE, "u", Map.of(), context -> {
                started.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException error) { interrupted.countDown(); throw error; }
                return "unreachable";
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var queued = tasks.submit(TaskType.AGENT_RESPONSE, "u", Map.of(), context -> { queuedStarted.set(true); return "bad"; });
            assertEquals(TaskStatus.CANCELLED, tasks.cancel(queued.getId()).getStatus());
            assertEquals(TaskStatus.CANCELLED, tasks.cancel(running.getId()).getStatus());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS); assertFalse(queuedStarted.get());
        }
    }
}
