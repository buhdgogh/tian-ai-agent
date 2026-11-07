package com.tianaiagent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.controller.AiController;
import com.tianaiagent.service.MedicalAgentService;
import com.tianaiagent.task.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentSseControllerTest {
    @Test void sseSendsExecutionThenAnswerDeltasBeforeTerminalEvent() throws Exception {
        var controller = new AiController(); var stream = mock(AgentResponseStreamService.class); var agent = mock(MedicalAgentService.class);
        ReflectionTestUtils.setField(controller, "responseStream", stream); ReflectionTestUtils.setField(controller, "agentService", agent);
        when(agent.getChatMessages(anyString())).thenReturn(List.of());
        var emitter = new SseEmitter(10000L); var session = new LiveAnswerSession(emitter, new ObjectMapper());
        when(stream.open(anyString(), anyString(), anyString(), isNull(), anyList())).thenReturn(emitter);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var result = mvc.perform(post("/ai/medical/dispatch/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"问题\",\"chatId\":\"chat-1\",\"turnId\":\"turn1\"}"))
                .andExpect(request().asyncStarted()).andExpect(header().string("X-Accel-Buffering", "no")).andReturn();
        session.event("progress", Map.of("title", "检索资料", "detail", "核对证据"));
        session.event("answer_start", Map.of()); session.event("answer_delta", Map.of("delta", "甲"));
        String partial = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(partial.contains("answer_delta")); assertTrue(partial.contains("甲")); assertFalse(partial.contains("event:done"));
        session.event("answer_delta", Map.of("delta", "乙"));
        var task = new TaskRecord("t", TaskType.AGENT_RESPONSE, "u", Map.of()); task.setStatus(TaskStatus.COMPLETED); task.setResult(Map.of("answer", "甲乙"));
        session.terminal(task);
        var complete = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(complete.indexOf("event:progress") < complete.indexOf("event:answer_start"));
        assertTrue(complete.indexOf("event:answer_start") < complete.indexOf("event:answer_delta"));
        assertTrue(complete.contains("event:done")); assertEquals("甲乙", session.answer());
    }
}
