package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.adapter.repository.ToolCredentialStore;
import com.tianaiagent.domain.agent.model.valobj.PersonalTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ToolCredentialService {
    private final ToolCredentialStore store;
    private final boolean adminEnvFallback;
    private final String mineruToken;
    private final String searchApiKey;

    public ToolCredentialService(ToolCredentialStore store,
            @Value("${PERSONAL_SETTINGS_ADMIN_ENV_FALLBACK:false}") boolean adminEnvFallback,
            @Value("${doc-mcp.mineru.token:${MINERU_TOKEN:}}") String mineruToken,
            @Value("${search-api.api-key:${SEARCH_API_KEY:}}") String searchApiKey) {
        this.store = store;
        this.adminEnvFallback = adminEnvFallback;
        this.mineruToken = normalizeEnv(mineruToken);
        this.searchApiKey = normalizeEnv(searchApiKey);
    }

    public String resolve(String ownerId, PersonalTool tool) {
        if (ownerId == null) return "";
        String personal = store.find(ownerId, tool);
        return personal == null ? environment(ownerId, tool) : personal;
    }

    public Source source(String ownerId, PersonalTool tool) {
        if (store.exists(ownerId, tool)) return Source.PERSONAL;
        return environment(ownerId, tool).isEmpty() ? Source.UNCONFIGURED : Source.DEVELOPMENT;
    }

    public void save(String ownerId, PersonalTool tool, String input) {
        String value = input == null ? "" : input.strip();
        if (value.isEmpty() || value.length() > 4096 || !value.matches("[\\x21-\\x7e]+")) {
            throw new IllegalArgumentException("凭据必须为 1 至 4096 个不含空格的可打印 ASCII 字符");
        }
        store.save(ownerId, tool, value);
    }

    public void clear(String ownerId, PersonalTool tool) { store.delete(ownerId, tool); }

    private String environment(String ownerId, PersonalTool tool) {
        if (!adminEnvFallback || !"admin".equals(ownerId)) return "";
        return tool == PersonalTool.MINERU ? mineruToken : searchApiKey;
    }

    private static String normalizeEnv(String value) {
        String text = value.strip();
        if (text.length() > 1 && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) return text.substring(1, text.length() - 1).strip();
        return text;
    }

    public enum Source { PERSONAL, DEVELOPMENT, UNCONFIGURED }
}
