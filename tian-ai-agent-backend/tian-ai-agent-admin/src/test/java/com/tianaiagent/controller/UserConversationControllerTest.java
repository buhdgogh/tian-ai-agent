package com.tianaiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import com.tianaiagent.user.*;
import com.tianaiagent.conversation.*;
import com.tianaiagent.service.MedicalAdvisorService;
import com.tianaiagent.service.MedicalAgentService;
import com.tianaiagent.service.MedicalIntentRouter;
import com.tianaiagent.workflow.AgentWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserConversationControllerTest {
    private MockMvc mvc;
    private ConversationRepository repository;
    private MedicalAdvisorService advisor;
    private MedicalAgentService agent;
    private MockHttpSession session;
    private MedicalIntentRouter intentRouter;
    private AgentWorkflowService workflows;
    private com.tianaiagent.task.AsyncTaskCenter taskCenter;
    @BeforeEach void setup() {
        var database = new AgentDatabase("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.initialize();
        var users = new UserRepository(database, "zhangsan", "123456"); users.initialize(); users.createIfAbsent("lisi", "654321");
        var current = new CurrentUserService(users);
        var mapper = new ObjectMapper();
        repository = new ConversationRepository(database, mapper);
        var controller = new AiController();
        advisor = mock(MedicalAdvisorService.class);
        ReflectionTestUtils.setField(controller, "advisorService", advisor);
        agent = mock(MedicalAgentService.class);
        ReflectionTestUtils.setField(controller, "agentService", agent);
        intentRouter = mock(MedicalIntentRouter.class);
        workflows = mock(AgentWorkflowService.class);
        ReflectionTestUtils.setField(controller, "intentRouter", intentRouter);
        ReflectionTestUtils.setField(controller, "workflowService", workflows);
        taskCenter = mock(com.tianaiagent.task.AsyncTaskCenter.class);
        ReflectionTestUtils.setField(controller, "taskCenter", taskCenter);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "currentUser", current);
        ReflectionTestUtils.setField(controller, "conversations", new ConversationService(repository, mapper, users));
        mvc = MockMvcBuilders.standaloneSetup(controller, new AuthController(current))
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
        try {
            session = (MockHttpSession) mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"zhangsan\",\"password\":\"123456\"}"))
                    .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        } catch (Exception error) { throw new AssertionError("测试账号登录失败", error); }
    }

    @Test void loggedInUserStoresIndependentConversations() throws Exception {
        mvc.perform(get("/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("zhangsan"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        when(advisor.doChat(eq("问题1"), anyString())).thenReturn("回答1");
        mvc.perform(post("/ai/medical/chat").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"问题1\",\"chatId\":\"chat1\"}"))
                .andExpect(status().isOk()).andExpect(content().string("回答1"));
        mvc.perform(post("/ai/sessions").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"chatId\":\"chat2\",\"type\":\"medical\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("zhangsan"));
        mvc.perform(get("/ai/sessions").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/ai/sessions/chat1/messages").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messageType").value("USER")).andExpect(jsonPath("$[1].text").value("回答1"));
        String memoryId = repository.find("zhangsan", "chat1", "medical").sessionId();
        assertNotEquals("chat1", memoryId);
        verify(advisor).doChat("问题1", memoryId);
    }

    @Test void loginChecksPasswordAndSessionOwnershipCannotBeForged() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"zhangsan\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        var loggedIn = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lisi\",\"password\":\"654321\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) loggedIn.getRequest().getSession();
        repository.ensure("zhangsan", "private", "medical", "张三的会话");
        mvc.perform(get("/ai/sessions/private/messages").session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/ai/medical/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"问题\",\"chatId\":\"chat1\",\"userId\":\"zhangsan\"}"))
                .andExpect(status().isForbidden());
        assertTrue(repository.list("lisi", "medical").isEmpty());
    }

    @Test void applicationErrorsArePersistedAsFailedReplies() {
        when(advisor.doChat(anyString(), anyString())).thenThrow(new IllegalStateException("模型故障"));
        assertThrows(Exception.class, () -> mvc.perform(post("/ai/medical/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"问题\",\"chatId\":\"failure\"}")));
        var reply = repository.messages("zhangsan", "failure", "medical").get(1);
        assertEquals("FAILED", reply.status()); assertEquals("模型故障", reply.metadata().get("error"));
    }

    @Test void streamingChatStoresCompleteReplyAndDeleteRemovesSession() throws Exception {
        when(advisor.doChatByStream(anyString(), anyString())).thenReturn(reactor.core.publisher.Flux.just("你好", "张三"));
        var request = mvc.perform(post("/ai/medical/chat/stream").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"chatId\":\"stream\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        assertEquals("你好张三", repository.messages("zhangsan", "stream", "medical").get(1).text());
        mvc.perform(delete("/ai/sessions/stream").session(session)).andExpect(status().isOk());
        assertTrue(repository.list("zhangsan", "medical").isEmpty());
    }

    @Test void agentStreamObserverPersistsReply() throws Exception {
        doAnswer(invocation -> {
            java.util.function.BiConsumer<String, Throwable> observer = invocation.getArgument(2);
            observer.accept("Agent完成结果", null);
            var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
            emitter.send("Agent完成结果"); emitter.complete();
            return emitter;
        }).when(agent).doChatByStream(anyString(), anyString(), any());
        mvc.perform(post("/ai/medical-agent/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"执行任务\",\"chatId\":\"agent1\"}")).andExpect(status().isOk());
        assertEquals("Agent完成结果", repository.messages("zhangsan", "agent1", "agent").get(1).text());
    }

    @Test void anonymousRequestsRequireLoginAndDoNotCreateConversations() throws Exception {
        var result = mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized()).andReturn();
        assertNull(result.getRequest().getSession(false));
        mvc.perform(get("/ai/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/ai/medical/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"问题\",\"chatId\":\"anonymous\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(advisor);
        assertTrue(repository.list("zhangsan", "medical").isEmpty());
    }

    @Test void logoutInvalidatesSessionAndRequiresLoginAgain() throws Exception {
        mvc.perform(post("/auth/logout").session(session)).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout")).andExpect(status().isNoContent());
        var loggedIn = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhangsan\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/auth/me").session((MockHttpSession) loggedIn.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("zhangsan"));
    }

    @Test void switchingUserReplacesOldSession() throws Exception {
        var loggedIn = mvc.perform(post("/auth/login").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"lisi\",\"password\":\"654321\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(session.isInvalid());
        var replacement = (MockHttpSession) loggedIn.getRequest().getSession(false);
        assertNotEquals(session.getId(), replacement.getId());
        mvc.perform(get("/auth/me").session(replacement)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("lisi"));
    }

    @Test void dispatchAnswersSimpleQuestionsInOneConversation() throws Exception {
        var route = new MedicalIntentRouter.RoutingDecision("完整健康问题", java.util.List.of("GENERAL_HEALTH"), 0.9, "KEYWORD", false, "ROUTINE");
        when(intentRouter.route(anyString(), anyList())).thenReturn(route);
        when(agent.doConversationChat(eq("完整健康问题"), anyString(), eq(route), anyList(), any())).thenReturn("健康信息回答");
        mvc.perform(post("/ai/medical/dispatch").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"健康问题\",\"chatId\":\"auto\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("REACT"))
                .andExpect(jsonPath("$.answer").value("健康信息回答"));
        assertEquals("健康信息回答", repository.messages("zhangsan", "auto", "agent").get(1).text());
        verifyNoInteractions(workflows);
    }

    @Test void dispatchUsesCompletedHistoryAndChainsRewrittenGoalInLegacyConversation() throws Exception {
        var oldTurn = repository.begin("zhangsan", "legacy", "medical", "比较两份指南");
        repository.finish(oldTurn.replyMessageId(), "已对比两份指南的证据", "COMPLETED", java.util.Map.of());
        var route = new MedicalIntentRouter.RoutingDecision("将两份指南对比结果整理成报告", java.util.List.of("REPORT_GENERATION"), 0.9, "LLM", true, "ROUTINE");
        when(intentRouter.route(anyString(), anyList())).thenReturn(route);
        var task = new com.tianaiagent.task.TaskRecord("task-auto", com.tianaiagent.task.TaskType.AGENT_WORKFLOW, "zhangsan", java.util.Map.of());
        when(workflows.submitForConversation(any(), any())).thenReturn(task);
        mvc.perform(post("/ai/medical/dispatch").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"整理成报告\",\"chatId\":\"legacy\",\"sessionType\":\"medical\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("WORKFLOW"))
                .andExpect(jsonPath("$.task.id").value("task-auto"));
        verify(intentRouter).route(eq("整理成报告"), argThat(history -> history.size() == 2 && history.get(1).getText().contains("指南的证据")));
        verify(workflows).submitForConversation(argThat(request -> request.objective().equals(route.standaloneQuestion()) && request.reviewEnabled()),
                argThat(turn -> turn.session().type().equals("medical")));
        assertTrue(repository.list("zhangsan", "agent").isEmpty());
    }

    @Test void dispatchRejectsUnknownSessionTypeBeforeExecution() throws Exception {
        mvc.perform(post("/ai/medical/dispatch").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"问题\",\"chatId\":\"invalid\",\"sessionType\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(intentRouter, agent, workflows);
    }

    @Test void stoppedQuestionCanBeRevisedWithoutOldQuestionEnteringContext() throws Exception {
        var old = repository.begin("zhangsan", "editable", "agent", "旧提问", "old", null);
        repository.updateRunningMetadata(old.replyMessageId(), java.util.Map.of("taskId", "old-task"));
        mvc.perform(post("/ai/sessions/editable/turns/old/interrupt").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(taskCenter).cancel("old-task");
        var route = new MedicalIntentRouter.RoutingDecision("新提问", java.util.List.of("GENERAL_HEALTH"), 0.9, "KEYWORD", false, "ROUTINE");
        when(intentRouter.route(anyString(), anyList())).thenReturn(route);
        when(agent.doConversationChat(eq("新提问"), anyString(), eq(route), anyList(), any())).thenReturn("新的回答");
        mvc.perform(post("/ai/medical/dispatch").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"新提问\",\"chatId\":\"editable\",\"turnId\":\"revision\",\"replacesTurnId\":\"old\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("新的回答"));
        verify(intentRouter).route(eq("新提问"), argThat(java.util.List::isEmpty));
        verify(agent).clearChat(old.session().sessionId());
        assertEquals("SUPERSEDED", repository.messages("zhangsan", "editable", "agent").get(0).status());
        assertEquals("新的回答", repository.messages("zhangsan", "editable", "agent").get(3).text());
    }

    @Test void interruptDuringClassificationPreventsStartingTheOldExecution() throws Exception {
        when(intentRouter.route(anyString(), anyList())).thenAnswer(invocation -> {
            repository.interrupt("zhangsan", "pending", "agent", "request-turn");
            return new MedicalIntentRouter.RoutingDecision("旧提问", java.util.List.of("GENERAL_HEALTH"), 0.9, "LLM", false, "ROUTINE");
        });
        mvc.perform(post("/ai/medical/dispatch").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"旧提问\",\"chatId\":\"pending\",\"turnId\":\"request-turn\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("INTERRUPTED"));
        verifyNoInteractions(agent, workflows);
        assertEquals("CANCELLED", repository.messages("zhangsan", "pending", "agent").get(1).status());
    }
}
