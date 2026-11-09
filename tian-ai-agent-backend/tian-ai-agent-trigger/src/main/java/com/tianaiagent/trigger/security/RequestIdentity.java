package com.tianaiagent.trigger.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record RequestIdentity(String ownerId, Role role) {
    public static final String ATTRIBUTE = "tian.identity";

    public enum Role { ADMIN, USER, MCP_READER }

    public void requireAdmin() {
        if (role != Role.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
