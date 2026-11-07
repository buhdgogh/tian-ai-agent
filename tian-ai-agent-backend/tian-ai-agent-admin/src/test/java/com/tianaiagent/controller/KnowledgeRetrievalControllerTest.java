package com.tianaiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeRetrievalControllerTest {
    private final KnowledgeBaseService service = mock(KnowledgeBaseService.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        var controller = new KnowledgeBaseController();
        ReflectionTestUtils.setField(controller, "knowledgeBaseService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void keywordOnlyModeReachesTheRetrievalService() throws Exception {
        when(service.retrieve("HbA1c", 5, "keyword")).thenReturn(new ObjectMapper().readTree("{\"retrieval_mode\":\"milvus-bm25-mmr\",\"sources\":[]}"));
        mvc.perform(post("/knowledge-base/retrieve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"HbA1c\",\"topK\":5,\"retrievalMode\":\"keyword\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retrieval_mode").value("milvus-bm25-mmr"));
        verify(service).retrieve("HbA1c", 5, "keyword");
    }

    @Test void invalidRetrievalModeIsRejectedBeforeCallingService() throws Exception {
        mvc.perform(post("/knowledge-base/retrieve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"HbA1c\",\"retrievalMode\":\"not-a-mode\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
