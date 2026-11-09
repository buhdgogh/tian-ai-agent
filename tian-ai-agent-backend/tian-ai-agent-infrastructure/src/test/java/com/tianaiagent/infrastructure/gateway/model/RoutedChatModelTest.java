package com.tianaiagent.infrastructure.gateway.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoutedChatModelTest {
    private static final Prompt PROMPT = new Prompt("test");
    private static final ChatResponse RESPONSE = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));

    @Test
    void failsOverBeforeTheFirstStreamResponse() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(unavailable()));
        when(backup.stream(any(Prompt.class))).thenReturn(Flux.just(RESPONSE));

        assertThat(router(primary, backup).stream(PROMPT).collectList().block()).containsExactly(RESPONSE);
    }

    @Test
    void neverRestartsAStreamAfterAResponseHasBeenEmitted() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(RESPONSE), Flux.error(unavailable())));
        var emitted = new AtomicInteger();

        assertThatThrownBy(() -> router(primary, backup).stream(PROMPT)
                .doOnNext(ignored -> emitted.incrementAndGet()).blockLast()).hasMessageContaining("HTTP_503");
        assertThat(emitted).hasValue(1);
        verifyNoInteractions(backup);
    }

    @Test
    void neverRepeatsAnAlreadyExecutedToolOnAnotherProvider() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel backup = mock(ChatModel.class);
        ToolCallback tool = mock(ToolCallback.class);
        when(primary.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt request = invocation.getArgument(0);
            ((ToolCallingChatOptions) request.getOptions()).getToolCallbacks().getFirst().call("{}");
            throw unavailable();
        });
        var request = new Prompt("test", ToolCallingChatOptions.builder().toolCallbacks(tool).build());
        assertThatThrownBy(() -> router(primary, backup).call(request)).hasMessageContaining("HTTP_503");
        verify(tool).call("{}");
        verifyNoInteractions(backup);
    }

    @Test
    void rotatesProvidersAtEqualPriorityAndDoesNotRetryBadRequests() {
        ChatModel first = mock(ChatModel.class);
        ChatModel second = mock(ChatModel.class);
        var badRequest = new RestClientResponseException("bad request", 400, "Bad Request", null, null, null);
        when(first.call(any(Prompt.class))).thenThrow(badRequest);
        when(second.call(any(Prompt.class))).thenReturn(RESPONSE);
        var router = new RoutedChatModel(List.of(target("first", 0, first), target("second", 0, second)),
                Duration.ofSeconds(1), Duration.ofSeconds(2));

        assertThatThrownBy(() -> router.call(PROMPT)).hasMessageContaining("HTTP_400");
        verifyNoInteractions(second);
        assertThat(router.call(PROMPT)).isSameAs(RESPONSE);
    }

    private static RoutedChatModel router(ChatModel first, ChatModel second) {
        return new RoutedChatModel(List.of(target("first", 0, first), target("second", 1, second)),
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private static RoutedChatModel.Target target(String id, int priority, ChatModel model) {
        return new RoutedChatModel.Target(id, id, priority, model);
    }

    private static RuntimeException unavailable() {
        return new RestClientResponseException("unavailable", 503, "Unavailable", null, null, null);
    }
}
