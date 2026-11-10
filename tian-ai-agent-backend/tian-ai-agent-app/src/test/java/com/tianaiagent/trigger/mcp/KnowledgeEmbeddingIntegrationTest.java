package com.tianaiagent.trigger.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianaiagent.TianAiAgentAdminApplication;
import com.tianaiagent.domain.agent.adapter.repository.ModelProviderStore;
import com.tianaiagent.domain.knowledge.service.IndexService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TianAiAgentAdminApplication.class, properties = {
        "agent.persistence.mysql.enabled=false",
        "agent.access.admin-token=embedding-admin-token-123456789",
        "agent.rag.semantic-cache.enabled=false",
        "doc-mcp.enabled=true",
        "doc-mcp.vector-store=local",
        "doc-mcp.embedding.provider=local",
        "doc-mcp.embedding.dimensions=128",
        "knowledge-base.storage.mode=local"
})
@AutoConfigureMockMvc
@DirtiesContext
class KnowledgeEmbeddingIntegrationTest {
    private static final String ADMIN = "Bearer embedding-admin-token-123456789";
    private static final String MODEL = "test-embedding-model";
    private static final String PROVIDER_ID = "integration-embedding";
    private static final String LEGACY_ID = "a".repeat(32);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentLinkedQueue<String> AUTH = new ConcurrentLinkedQueue<>();
    private static HttpServer upstream;
    @TempDir static Path data;

    @Autowired MockMvc mvc;
    @Autowired IndexService index;
    @MockitoBean ModelProviderStore providerStore;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        String databaseUrl = "jdbc:sqlite:" + data.resolve("legacy.sqlite").toAbsolutePath();
        try (var connection = DriverManager.getConnection(databaseUrl); var sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE knowledge_spaces(
                        space_id TEXT PRIMARY KEY, name TEXT NOT NULL, medical INTEGER NOT NULL,
                        deleting INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            sql.execute("INSERT INTO knowledge_spaces(space_id,name,medical) VALUES ('" + LEGACY_ID + "','Legacy',0)");
        }
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/embeddings", exchange -> {
            try (exchange) {
                AUTH.add(exchange.getRequestHeaders().getFirst("Authorization"));
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                boolean supported = MODEL.equals(request.path("model").asText());
                var response = JSON.createObjectNode();
                var vectors = response.putArray("data");
                for (int i = request.path("input").size() - 1; i >= 0; i--) {
                    vectors.addObject().put("index", i).putArray("embedding").add(0.2).add(0.4).add(0.8);
                }
                byte[] body = JSON.writeValueAsBytes(response);
                exchange.sendResponseHeaders(supported ? 200 : 404, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        upstream.start();
        registry.add("spring.datasource.url", () -> databaseUrl);
        registry.add("agent.models.allowed-user-origins[0]", KnowledgeEmbeddingIntegrationTest::origin);
        registry.add("agent.models.allowed-user-origins[1]", () -> "https://*.cn-beijing.maas.aliyuncs.com");
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void beijingWorkspaceEmbeddingUsesCompatibleUrlWithoutExpandingChatAccess() throws Exception {
        String host = "llm-test-workspace.cn-beijing.maas.aliyuncs.com";
        String version = settings().path("version").asText();
        var row = new java.util.HashMap<>(provider("qwen3.7-text-embedding", "workspace-test-key"));
        try {
            for (String invalidUrl : List.of("https://[workspace-id].cn-beijing.maas.aliyuncs.com/api/v1",
                    "https://" + host + ".example.org/api/v1", "http://" + host + "/api/v1",
                    "https://" + host + ":444/api/v1", "https://" + host + "/other")) {
                row.put("baseUrl", invalidUrl);
                mvc.perform(put("/model-providers").header("Authorization", ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(Map.of("version", version, "providers", List.of(row)))))
                        .andExpect(status().isBadRequest());
            }
            row.put("baseUrl", "https://" + host + "/api/v1");
            row.put("protocol", "OPENAI_COMPATIBLE");
            mvc.perform(put("/model-providers").header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of("version", version, "providers", List.of(row)))))
                    .andExpect(status().isBadRequest());

            row.put("protocol", "OPENAI_EMBEDDING");
            var saved = saveSettings(version, List.of(row));
            version = saved.path("version").asText();
            assertThat(saved.path("providers").get(0).path("baseUrl").asText())
                    .isEqualTo("https://" + host + "/compatible-mode/v1");

            row.remove("apiKey");
            version = saveSettings(version, List.of(row)).path("version").asText();
        } finally {
            saveSettings(version, List.of());
        }
    }

    @Test
    void personalEmbeddingIsBoundToTheSpaceAndUsesTheCurrentKey() throws Exception {
        String version = settings().path("version").asText();
        try {
            mvc.perform(post("/knowledge-base/spaces").header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content(createRequest(PROVIDER_ID)))
                    .andExpect(status().isUnprocessableEntity());
            var saved = saveSettings(version, List.of(provider(MODEL, "personal-test-key")));
            version = saved.path("version").asText();
            assertThat(saved.toString()).doesNotContain("personal-test-key");

            var created = mvc.perform(post("/knowledge-base/spaces").header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content(createRequest(PROVIDER_ID)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.embeddingModel").value(MODEL))
                    .andExpect(jsonPath("$.embeddingDimensions").value(3))
                    .andExpect(jsonPath("$.embedding").doesNotExist())
                    .andExpect(content().string(not(containsString(origin()))))
                    .andReturn().getResponse();
            String spaceId = JSON.readTree(created.getContentAsString()).path("id").asText();
            assertThat(index.space(spaceId).embedding().model()).isEqualTo(MODEL);
            int probeCalls = AUTH.size();
            assertThat(probeCalls).isEqualTo(1);

            index.upsert(spaceId, "custom-doc", "# 产品手册\n\n此文档说明如何设置控制面板。", Map.of());
            assertThat(AUTH).hasSizeGreaterThan(probeCalls).containsOnly("Bearer personal-test-key");
            version = saveSettings(version, List.of(provider(MODEL, "rotated-test-key"))).path("version").asText();
            var search = Map.of("spaceId", spaceId, "question", "如何设置控制面板");
            mvc.perform(post("/knowledge-base/retrieve").header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(search)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[0].documentId").value("custom-doc"));
            assertThat(AUTH).contains("Bearer personal-test-key", "Bearer rotated-test-key");

            version = saveSettings(version, List.of(provider("changed-model", ""))).path("version").asText();
            mvc.perform(post("/knowledge-base/retrieve").header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(search)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("EMBEDDING_UNAVAILABLE"));
            int calls = AUTH.size();
            index.upsert(LEGACY_ID, "legacy-doc", "# Legacy\n\n旧知识库使用原来的嵌入模型。", Map.of());
            assertThat(index.retrieve(LEGACY_ID, "旧知识库", 5).sources()).isNotEmpty();
            assertThat(AUTH).hasSize(calls);
        } finally {
            saveSettings(version, List.of());
        }
    }

    private JsonNode settings() throws Exception {
        var response = mvc.perform(get("/model-providers").header("Authorization", ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse();
        return JSON.readTree(response.getContentAsString());
    }

    private JsonNode saveSettings(String version, List<?> providers) throws Exception {
        var response = mvc.perform(put("/model-providers").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("version", version, "providers", providers))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return JSON.readTree(response.getContentAsString());
    }

    private static Map<String, String> provider(String model, String key) {
        return Map.of("id", PROVIDER_ID, "protocol", "OPENAI_EMBEDDING",
                "baseUrl", origin() + "/v1", "model", model, "apiKey", key);
    }

    private static String createRequest(String id) throws Exception {
        return JSON.writeValueAsString(Map.of("name", "Personal embedding", "embeddingProviderId", id));
    }

    private static String origin() { return "http://127.0.0.1:" + upstream.getAddress().getPort(); }
}
