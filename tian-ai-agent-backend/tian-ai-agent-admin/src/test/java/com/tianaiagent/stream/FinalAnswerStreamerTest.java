package com.tianaiagent.stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Sinks;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class FinalAnswerStreamerTest {
    @Test void deltasReachConsumerBeforeModelCompletes() throws Exception {
        ChatModel model = mock(ChatModel.class);
        var source = Sinks.many().multicast().<ChatResponse>onBackpressureBuffer();
        var subscribed = new CountDownLatch(1); var first = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenReturn(source.asFlux().doOnSubscribe(s -> subscribed.countDown()));
        var service = new FinalAnswerStreamer(model); var chunks = new CopyOnWriteArrayList<String>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> service.stream("问题", "已核验资料", delta -> { chunks.add(delta); first.countDown(); }, () -> {}));
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            source.tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("甲")))));
            assertTrue(first.await(5, TimeUnit.SECONDS)); assertFalse(result.isDone()); assertEquals(List.of("甲"), chunks);
            source.tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("乙"))))); source.tryEmitComplete();
            assertEquals("甲乙", result.get(5, TimeUnit.SECONDS));
        }
    }
    @Test void cancellationStopsBeforeAProviderRequest() {
        ChatModel model = mock(ChatModel.class); var service = new FinalAnswerStreamer(model);
        assertThrows(CancellationException.class, () -> service.stream("q", "draft", delta -> fail(), () -> { throw new CancellationException(); }));
        verify(model, never()).stream(any(Prompt.class));
    }
}
