package com.tianaiagent.trigger.security;

import com.tianaiagent.trigger.mcp.McpController;
import com.tianaiagent.trigger.http.HealthController;
import com.tianaiagent.trigger.security.RequestIdentity.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessConfiguration.Properties.class)
class AccessConfiguration implements WebMvcConfigurer {
    private final List<Credential> credentials;

    AccessConfiguration(Properties properties) {
        List<Credential> configured = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        add(configured, tokens, properties.adminToken(), "admin", Role.ADMIN);
        add(configured, tokens, properties.mcpToken(), "mcp-reader", Role.MCP_READER);
        Set<String> owners = new HashSet<>();
        if (properties.users() != null) {
            for (UserCredential user : properties.users()) {
                if (user.id() == null || !user.id().matches("[a-z0-9_]{1,32}")
                        || "admin".equals(user.id()) || !owners.add(user.id())
                        || user.token() == null || user.token().isBlank()) {
                    throw new IllegalArgumentException("Invalid or duplicate configured user identity");
                }
                add(configured, tokens, user.token(), user.id(), Role.USER);
            }
        }
        credentials = List.copyOf(configured);
    }

    private static void add(List<Credential> target, Set<String> tokens, String token, String owner, Role role) {
        if (token == null || token.isBlank()) return;
        if (!tokens.add(token) || token.length() < 24 || !token.matches("[\\x21-\\x7e]+")) {
            throw new IllegalArgumentException("Access tokens must be unique printable ASCII strings of at least 24 characters");
        }
        target.add(new Credential(token.getBytes(StandardCharsets.US_ASCII), new RequestIdentity(owner, role)));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws java.io.IOException {
                if (!(handler instanceof HandlerMethod method)
                        || HealthController.class.isAssignableFrom(method.getBeanType())
                        || "OPTIONS".equals(request.getMethod())
                        || "/error".equals(request.getServletPath())) return true;
                String header = request.getHeader("Authorization");
                RequestIdentity identity = authenticate(header);
                if (identity == null) {
                    response.setHeader("WWW-Authenticate", "Bearer");
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                boolean mcp = McpController.class.isAssignableFrom(method.getBeanType());
                if (mcp && identity.role() == Role.USER || !mcp && identity.role() == Role.MCP_READER) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return false;
                }
                request.setAttribute(RequestIdentity.ATTRIBUTE, identity);
                return true;
            }
        });
    }

    private RequestIdentity authenticate(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        byte[] token = header.substring(7).getBytes(StandardCharsets.UTF_8);
        return credentials.stream().filter(value -> MessageDigest.isEqual(value.token(), token))
                .map(Credential::identity).findFirst().orElse(null);
    }

    @ConfigurationProperties("agent.access")
    record Properties(String adminToken, String mcpToken, List<UserCredential> users) {}
    record UserCredential(String id, String token) {}
    private record Credential(byte[] token, RequestIdentity identity) {}
}
