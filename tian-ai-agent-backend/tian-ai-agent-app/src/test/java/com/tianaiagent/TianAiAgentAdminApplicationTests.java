package com.tianaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;
import com.tianaiagent.domain.knowledge.adapter.port.DocumentIndexer;
import com.tianaiagent.domain.task.service.AsyncTaskCenter;
import com.tianaiagent.domain.task.model.valobj.TaskType;
import com.tianaiagent.domain.conversation.adapter.port.ConversationMemoryRegistry;
import com.tianaiagent.domain.conversation.service.ConversationScope;
import org.springframework.ai.chat.messages.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.persistence.mysql.enabled=false",
        "doc-mcp.enabled=false",
        "spring.ai.dashscope.api-key=",
        "spring.ai.dashscope.chat.api-key=",
        "agent.access.users[0].id=alice", "agent.access.users[0].token=alice-test-token-1234567890",
        "agent.access.users[1].id=bob", "agent.access.users[1].token=bob-test-token-123456789000"
})
@AutoConfigureMockMvc
class TianAiAgentAdminApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired AsyncTaskCenter tasks;
    @Autowired ObjectMapper mapper;
    @Autowired ConversationMemoryRegistry conversations;
    private static final String ALICE = "Bearer alice-test-token-1234567890";
    private static final String BOB = "Bearer bob-test-token-123456789000";

    @Test
    void disabledMcpLeavesAdminAvailableWithoutDocumentBeans() throws Exception {
        assertThat(context.getBeansOfType(KnowledgeIndex.class)).isEmpty();
        assertThat(context.getBeansOfType(DocumentIndexer.class)).isEmpty();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/knowledge-base/retrieve").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", ALICE)
                .content("{\"question\":\"test\",\"topK\":3}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MCP_DISABLED"));
    }

    @Test
    void acceptsOfficialEndpointsForTheCommonProviderPresets() throws Exception {
        var existing = mvc.perform(get("/model-providers").header("Authorization", BOB))
                .andExpect(status().isOk()).andReturn().getResponse();
        var payload = mapper.createObjectNode()
                .put("version", mapper.readTree(existing.getContentAsString()).path("version").asText());
        var providers = payload.putArray("providers");
        var endpoints = List.of(
                Map.entry("deepseek", "https://api.deepseek.com"),
                Map.entry("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                Map.entry("openai", "https://api.openai.com/v1"),
                Map.entry("claude", "https://api.anthropic.com/v1"));
        for (int index = 0; index < endpoints.size(); index++) {
            var provider = endpoints.get(index);
            providers.addObject().put("id", provider.getKey()).put("baseUrl", provider.getValue())
                    .put("protocol", "OPENAI_COMPATIBLE").put("model", "test-model")
                    .put("apiKey", "test-provider-key").put("priority", index);
        }
        mvc.perform(put("/model-providers").header("Authorization", BOB)
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(4))
                .andExpect(jsonPath("$.providers[3].hasApiKey").value(true))
                .andExpect(jsonPath("$.providers[3].apiKey").doesNotExist());
    }

    @Test
    void dispatchStopsAndAuditsFailureWhenNoModelIsConfigured() throws Exception {
        var response = mvc.perform(post("/ai/dispatch").header("Authorization", ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"missing-model\",\"message\":\"归纳资料中的关键条款\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"))
                .andReturn().getResponse();
        String runId = mapper.readTree(response.getContentAsString()).path("runId").asText();
        assertThat(runId).isNotBlank();

        var audit = mvc.perform(get("/agent-runs/{id}", runId).header("Authorization", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("FAILED"))
                .andReturn().getResponse();
        assertThat(mapper.readTree(audit.getContentAsString()).path("events").findValuesAsText("name"))
                .contains("ReAct 1").doesNotContain("ReAct 2");
    }

    @Test
    void bindsTasksAndSessionsToTheAuthenticatedOwner() throws Exception {
        String taskId = tasks.submit(TaskType.AGENT_WORKFLOW, "alice", Map.of(), ignored -> "ok").getId();
        mvc.perform(get("/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/tasks").param("ownerId", "alice").header("Authorization", BOB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/tasks/{taskId}", taskId).header("Authorization", BOB)).andExpect(status().isNotFound());
        mvc.perform(delete("/tasks/{taskId}", taskId).header("Authorization", BOB)).andExpect(status().isNotFound());
        mvc.perform(get("/tasks/{taskId}", taskId).header("Authorization", ALICE)).andExpect(status().isOk());

        var memory = conversations.chatMemory(ConversationMemoryRegistry.MEDICAL_ADVISOR);
        String storageId = ConversationScope.storageId("alice", "same-chat");
        String scopedId = ConversationScope.storageId("alice", "same-chat", "medical");
        memory.add(storageId, List.of(new UserMessage("private session")));
        memory.add(scopedId, List.of(new UserMessage("space session")));
        try {
            mvc.perform(get("/ai/sessions").header("Authorization", ALICE))
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("same-chat"));
            mvc.perform(get("/ai/sessions").param("spaceId", "medical").header("Authorization", ALICE))
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("same-chat"));
            mvc.perform(get("/ai/sessions").header("Authorization", BOB))
                    .andExpect(jsonPath("$.length()").value(0));
            mvc.perform(get("/ai/sessions/same-chat/messages").header("Authorization", ALICE))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("private session"));
            mvc.perform(get("/ai/sessions/same-chat/messages").param("spaceId", "medical").header("Authorization", ALICE))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("space session"));
            mvc.perform(get("/ai/sessions/same-chat/messages").header("Authorization", BOB))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
            mvc.perform(get("/ai/sessions/{chatId}/messages", storageId).header("Authorization", BOB))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete("/ai/sessions/same-chat").header("Authorization", BOB)).andExpect(status().isOk());
            assertThat(memory.get(storageId)).hasSize(1);
        } finally {
            memory.clear(storageId);
            memory.clear(scopedId);
        }
    }
}
