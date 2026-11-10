package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.adapter.repository.ToolCredentialStore;
import com.tianaiagent.domain.agent.model.valobj.PersonalTool;
import org.junit.jupiter.api.Test;

import static com.tianaiagent.domain.agent.model.valobj.PersonalTool.MINERU;
import static com.tianaiagent.domain.agent.model.valobj.PersonalTool.SEARCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCredentialServiceTest {
    @Test
    void prefersPersonalCredentialsAndLimitsEnvironmentFallbackToDevelopmentAdmin() {
        var store = mock(ToolCredentialStore.class);
        var development = new ToolCredentialService(store, true, "'local-mineru'", "local-search");
        var deployed = new ToolCredentialService(store, false, "local-mineru", "local-search");

        when(store.find("alice", MINERU)).thenReturn("alice-mineru");
        when(store.find("alice", SEARCH)).thenReturn("alice-search");
        when(store.find("admin", SEARCH)).thenReturn("personal-admin-search");
        when(store.exists("admin", SEARCH)).thenReturn(true);

        assertEquals("alice-mineru", development.resolve("alice", MINERU));
        assertEquals("alice-search", development.resolve("alice", SEARCH));
        for (PersonalTool tool : PersonalTool.values()) {
            assertEquals("", development.resolve("bob", tool));
            assertEquals("", development.resolve(null, tool));
        }
        assertEquals("local-mineru", development.resolve("admin", MINERU));
        assertEquals("personal-admin-search", development.resolve("admin", SEARCH));
        assertEquals(ToolCredentialService.Source.PERSONAL, development.source("admin", SEARCH));
        assertEquals(ToolCredentialService.Source.DEVELOPMENT, development.source("admin", MINERU));
        assertEquals("", deployed.resolve("admin", MINERU));
        assertEquals(ToolCredentialService.Source.UNCONFIGURED, deployed.source("admin", MINERU));
    }
}
