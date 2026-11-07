package com.tianaiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.mcp.LlamaIndexRagClient;
import com.tianaiagent.user.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WikiControllerTest {
    LlamaIndexRagClient client = mock(LlamaIndexRagClient.class);
    CurrentUserService users = mock(CurrentUserService.class);
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.standaloneSetup(new WikiController(client, users)).build(); when(users.userId()).thenReturn("user-zhangsan"); }

    @Test void publicationUsesAuthenticatedReviewerInsteadOfSubmittedIdentity() throws Exception {
        when(client.wiki(eq("wiki_publish"), anyMap())).thenReturn(new ObjectMapper().readTree("{\"published\":true}"));
        mvc.perform(post("/knowledge-base/wiki/pages/topic-1/publish").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revisionId\":\"revision-1\",\"reviewer\":\"spoofed-user\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true));
        verify(client).wiki("wiki_publish", Map.of("page_id", "topic-1", "revision_id", "revision-1", "reviewer", "user-zhangsan"));
    }
    @Test void managementRequiresLogin() throws Exception {
        when(users.userId()).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(get("/knowledge-base/wiki/pages")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }
    @Test void compilationIsAcceptedAsynchronously() throws Exception {
        when(client.wiki("wiki_compile", Map.of("doc_id", "doc-1"))).thenReturn(new ObjectMapper().readTree("{\"job_id\":\"job-1\"}"));
        mvc.perform(post("/knowledge-base/wiki/compile").contentType(MediaType.APPLICATION_JSON).content("{\"docId\":\"doc-1\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.job_id").value("job-1"));
    }
    @Test void invalidPublicationIsReturnedAsBadRequest() throws Exception {
        when(client.wiki(eq("wiki_publish"), anyMap())).thenThrow(new IllegalArgumentException("版本已失效"));
        mvc.perform(post("/knowledge-base/wiki/pages/topic-1/publish").contentType(MediaType.APPLICATION_JSON).content("{\"revisionId\":\"stale-1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("版本已失效"));
    }
    @Test void markdownAndMetadataAreAvailableThroughRest() throws Exception {
        when(client.wiki("wiki_read_note", Map.of("page_id", "topic-1", "published_only", true)))
                .thenReturn(new ObjectMapper().readTree("{\"markdown\":\"# 主题\\n\\n内容\",\"tags\":[\"医疗\"],\"backlinks\":[]}"));
        mvc.perform(get("/knowledge-base/wiki/pages/topic-1/content").param("publishedOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags[0]").value("医疗"));
        mvc.perform(get("/knowledge-base/wiki/pages/topic-1/markdown").param("publishedOnly", "true"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string("# 主题\n\n内容"));
    }
}
