package com.tianaiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.persistence.AgentDatabase;
import com.tianaiagent.profile.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserProfileControllerTest {
    @Test
    void supportsCrudAndRejectsInvalidProfile() throws Exception {
        var database = new AgentDatabase("jdbc:h2:mem:profile_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.initialize();
        var mvc = MockMvcBuilders.standaloneSetup(new UserProfileController(
                new UserProfileRepository(database, new ObjectMapper()))).build();
        mvc.perform(get("/users/user1/profile")).andExpect(status().isNotFound());
        mvc.perform(put("/users/user1/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredLanguage\":\"zh-CN\",\"preferences\":{\"format\":\"table\"},\"confirmedFacts\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.preferences.format").value("table"));
        mvc.perform(get("/users/user1/profile")).andExpect(status().isOk());
        mvc.perform(put("/users/user1/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmedFacts\":[null]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/users/user1/profile")).andExpect(status().isNoContent());
        mvc.perform(get("/users/user1/profile")).andExpect(status().isNotFound());
    }
}
