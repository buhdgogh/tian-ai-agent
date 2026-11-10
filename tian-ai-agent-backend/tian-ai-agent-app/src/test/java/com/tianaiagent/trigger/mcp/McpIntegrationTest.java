package com.tianaiagent.trigger.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.TianAiAgentAdminApplication;
import com.tianaiagent.domain.task.service.AsyncTaskCenter;
import com.tianaiagent.domain.agent.adapter.port.AgentTools;
import com.tianaiagent.domain.knowledge.service.document.DocumentParseService;
import com.tianaiagent.domain.knowledge.service.IndexService;
import com.tianaiagent.domain.knowledge.adapter.repository.KnowledgeRepository;
import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;
import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import com.tianaiagent.domain.conversation.adapter.port.SemanticMemoryGateway;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;
import com.tianaiagent.domain.knowledge.adapter.port.UploadRateLimiter;
import com.tianaiagent.domain.knowledge.adapter.port.MultimodalDocumentParser;
import com.tianaiagent.domain.task.model.valobj.TaskStatus;
import com.tianaiagent.types.exception.ModelUnavailableException;
import com.tianaiagent.types.storage.FileConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TianAiAgentAdminApplication.class, properties = {
        "agent.persistence.mysql.enabled=false",
        "agent.access.mcp-token=mcp-reader-test-token-123456789",
        "agent.access.admin-token=admin-test-token-123456789",
        "agent.access.users[0].id=reader",
        "agent.access.users[0].token=user-test-token-123456789",
        "agent.access.users[1].id=toolsettings_test_reader",
        "agent.access.users[1].token=toolsettings-reader-test-token-123456789",
        "PERSONAL_SETTINGS_ADMIN_ENV_FALLBACK=true",
        "MINERU_TOKEN=development-mineru-test",
        "SEARCH_API_KEY=development-search-test",
        "agent.rag.semantic-cache.enabled=false",
        "doc-mcp.enabled=true",
        "doc-mcp.mineru.enabled=false",
        "doc-mcp.chat.api-key=",
        "knowledge-base.storage.mode=local",
        "knowledge-base.mcp.parse-batch-window-ms=0",
        "knowledge-base.mcp.parse-poll-interval-ms=200",
        "spring.datasource.url=jdbc:sqlite::memory:",
        "doc-mcp.vector-store=local",
        "doc-mcp.embedding.provider=local",
        "doc-mcp.embedding.dimensions=128",
        "doc-mcp.chunk-sizes=64,24,10"
})
@AutoConfigureMockMvc
class McpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired IndexService indexing;
    @Autowired DocumentParseService parsing;
    @Autowired KnowledgeIndex knowledgeIndex;
    @Autowired KnowledgeRepository repository;
    @Autowired TextEmbedding embeddings;
    @Autowired SemanticMemoryGateway semanticMemory;
    @Autowired AsyncTaskCenter tasks;
    @Autowired ObjectMapper mapper;
    @Autowired AgentTools agentTools;
    @Autowired com.tianaiagent.types.config.DocMcpProperties docMcpProperties;
    @MockitoBean(name = "wikiAnswerModel") com.tianaiagent.domain.knowledge.adapter.port.AnswerModel answerModel;
    @MockitoBean UploadRateLimiter uploadRateLimiter;
    @MockitoBean MultimodalDocumentParser mineru;

    @BeforeEach
    void bindMineru() {
        when(mineru.withToken(anyString())).thenReturn(mineru);
    }

    @Test
    void toolSettingsArePrivateAndDevelopmentCredentialsAreAdminOnly() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String reader = "Bearer toolsettings-reader-test-token-123456789";
        mvc.perform(get("/tool-settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/tool-settings").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.search").value("DEVELOPMENT"));
        try {
            mvc.perform(get("/tool-settings").header("Authorization", reader)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.search").value("UNCONFIGURED"));
            mvc.perform(put("/tool-settings/search").header("Authorization", reader)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"personal-search-test\"}"))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/tool-settings").header("Authorization", reader)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.search").value("PERSONAL"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("personal-search-test"))));
            mvc.perform(get("/tool-settings").header("Authorization", admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.search").value("DEVELOPMENT"));
        } finally {
            mvc.perform(delete("/tool-settings/search").header("Authorization", reader)).andExpect(status().isNoContent());
        }
        mvc.perform(get("/tool-settings").header("Authorization", reader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.search").value("UNCONFIGURED"));
    }

    private static final Map<String, Object> APPROVED = Map.of(
            "issuer", "Test", "version", "1", "publishedAt", "2026-01-01",
            "audience", "All", "effectiveStatus", "ACTIVE", "reviewStatus", "APPROVED",
            "documentType", "guideline");

    @Test
    void requiresAuthenticationAndExposesOnlyReadTools() throws Exception {
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(initialize))
                .andExpect(status().isUnauthorized());
        mvc.perform(mcp(initialize)).andExpect(status().isOk())
                .andExpect(header().exists("Mcp-Session-Id"));
        mvc.perform(mcp("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """)).andExpect(jsonPath("$.result.tools.length()").value(5));
        for (String denied : List.of("index_document", "parse_document", "delete_document", "search_long_term_memory")) {
            mvc.perform(mcp("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                    """.formatted(denied))).andExpect(jsonPath("$.error.code").value(-32601));
        }
    }

    @Test
    void enforcesPublicationForWritesAndLegacyReadsAndDeletesIdempotently() throws Exception {
        assertThatThrownBy(() -> indexing.upsert("medical", "unapproved", "未审核", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var request = new DocumentParseService.ParseRequest(List.of("draft.txt"), Map.of(), Map.of(), Map.of("draft.txt", "medical"),
                Map.of(), "tika", "vlm", false, true, true, "ch", true);
        assertThatThrownBy(() -> parsing.submit(request)).isInstanceOf(IllegalArgumentException.class);

        String legacyId = "0".repeat(64);
        String question = "花岗岩分布在哪里";
        var legacy = new ChunkRecord(legacyId, "legacy", null, 0, true, 0, question, null, null,
                question, Map.of(), embeddings.embedDocuments(List.of(question)).getFirst());
        repository.replaceDocument("medical", new KnowledgeRepository.DocumentRecord("legacy", question, null, null, Map.of()),
                List.of(legacy));
        indexing.upsert("medical", "geo-1", "# 地质报告\n\n花岗岩主要分布在研究区北部。", APPROVED);

        mvc.perform(mcp("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"retrieve_knowledge","arguments":{"space_id":"medical","question":"花岗岩分布在哪里","top_k":3}}}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("geo-1")));
        assertThat(knowledgeIndex.retrieve("medical", question, 3).sources())
                .extracting(KnowledgeIndex.Evidence::documentId).containsOnly("geo-1");
        assertThatThrownBy(() -> indexing.getChunkContext("medical", legacyId)).isInstanceOf(IllegalArgumentException.class);

        assertThat(knowledgeIndex.delete("medical", "geo-1").alreadyAbsent()).isFalse();
        assertThat(knowledgeIndex.delete("medical", "geo-1").alreadyAbsent()).isTrue();
        assertThat(knowledgeIndex.retrieve("medical", question, 3).sources()).isEmpty();
        assertThatThrownBy(() -> indexing.upsert("medical", "geo-1", question, APPROVED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accessesMemoryThroughTheInternalTypedPort() {
        String owner = "medical-agent:chat1";
        semanticMemory.upsert(owner, "summary_1", "用户正在整理高血压随访资料", Map.of());
        assertThat(semanticMemory.search(owner, "高血压随访", 3))
                .extracting(SemanticMemoryGateway.Match::text).containsExactly("用户正在整理高血压随访资料");
        semanticMemory.delete(owner);
        assertThat(semanticMemory.search(owner, "高血压随访", 3)).isEmpty();
    }

    @Test
    void uploadsAndIndexesThroughTheInternalTypedPipeline() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        var file = new MockMultipartFile("file", "test.txt", "text/plain",
                "本资料描述玄武岩的分布和观测记录。".getBytes(StandardCharsets.UTF_8));
        var response = mvc.perform(multipart("/knowledge-base/upload").file(file)
                        .param("spaceId", "medical").param("issuer", "Test").param("version", "1").param("publishedAt", "2026-01-01")
                        .param("audience", "All").param("documentType", "guideline")
                        .header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        var upload = mapper.readTree(response.getContentAsString());
        String docId = upload.path("id").asText();
        String taskId = upload.path("taskId").asText();
        assertThat(docId).isNotBlank();
        assertThat(taskId).isNotBlank();
        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(tasks.get(taskId).getStatus()).isEqualTo(TaskStatus.COMPLETED));
            assertThat(knowledgeIndex.retrieve("medical", "玄武岩的分布和观测记录", 3).sources())
                    .extracting(KnowledgeIndex.Evidence::documentId).contains(docId);
            mvc.perform(mcp("""
                    {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"query_knowledge","arguments":{"space_id":"medical","question":"玄武岩的分布和观测记录"}}}
                    """)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].text", containsString("\"documentId\":\"" + docId + "\"")));
        } finally {
            mvc.perform(delete("/knowledge-base/files/{id}", docId).param("spaceId", "medical").header("Authorization", admin))
                    .andExpect(status().isOk());
        }
        assertThat(knowledgeIndex.retrieve("medical", "玄武岩的分布和观测记录", 3).sources())
                .extracting(KnowledgeIndex.Evidence::documentId).doesNotContain(docId);
    }

    @Test
    void cleansParsedMarkdownBeforePublishingOrIndexingAndOnParseCacheHit() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String space = createSpace(admin, "文档清洗验证");
        String raw = """
                # 操作说明

                水印：内部样稿

                正文证据包括具体的设备维护步骤和巡检要求。
                """;
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        when(mineru.isAvailable()).thenReturn(true);
        when(mineru.parseUpload(any(), any())).thenReturn(raw);
        String first = uploadWikiSource(admin, space, "first.md", raw);
        verify(mineru).withToken("development-mineru-test");
        assertThat(repository.loadDocumentChunks(space, first)).extracting(ChunkRecord::text)
                .allMatch(text -> !text.contains("内部样稿"))
                .anyMatch(text -> text.contains("正文证据"));
        mvc.perform(get("/knowledge-base/files").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].timings.documentMcp.cleaning.removedWatermarkLines").value(1));
        var parsed = java.nio.file.Path.of(docMcpProperties.getParsedDir(), space, first + ".md");
        assertThat(java.nio.file.Files.readString(parsed)).doesNotContain("内部样稿").contains("正文证据");

        mvc.perform(delete("/knowledge-base/files/{id}", first).param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk());
        String second = uploadWikiSource(admin, space, "second.md", raw);
        assertThat(repository.loadDocumentChunks(space, second)).extracting(ChunkRecord::text)
                .allMatch(text -> !text.contains("内部样稿"));
        mvc.perform(get("/knowledge-base/files").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].timings.documentMcp.pythonTask.cache_hit_documents").value(1))
                .andExpect(jsonPath("$[0].timings.documentMcp.cleaning.removedWatermarkLines").value(1));
    }

    @Test
    void autoParsingFallsBackToTikaAndKeepsMineruCacheSeparate() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String space = createSpace(admin, "解析降级验证");
        String source = "# 通用文档\n\n这段文字用于检查文档解析降级。";
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        when(mineru.isAvailable()).thenReturn(true);
        when(mineru.parseUpload(any(), any()))
                .thenThrow(new IllegalStateException("mock MinerU unavailable"))
                .thenReturn("# MinerU 恢复\n\n已通过多模态解析提取文本。");

        String fallback = uploadWikiSource(admin, space, "fallback.md", source);
        mvc.perform(get("/knowledge-base/files").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(fallback))
                .andExpect(jsonPath("$[0].status").value("INDEXED_DEGRADED"));
        mvc.perform(delete("/knowledge-base/files/{id}", fallback).param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk());

        String recovered = uploadWikiSource(admin, space, "recovered.md", source);
        mvc.perform(get("/knowledge-base/files").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(recovered))
                .andExpect(jsonPath("$[0].status").value("INDEXED"));
    }

    @Test
    void isolatesGeneralSpacesInUploadRetrievalAndDeletion() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String reader = "Bearer user-test-token-123456789";
        mvc.perform(post("/knowledge-base/spaces").header("Authorization", reader)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"禁止创建\"}"))
                .andExpect(status().isForbidden());
        String first = createSpace(admin, "工程资料");
        String second = createSpace(admin, "操作手册");
        mvc.perform(get("/knowledge-base/spaces").header("Authorization", reader))
                .andExpect(jsonPath("$[?(@.id == '%s')].name".formatted(first)).isNotEmpty());

        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        var file = new MockMultipartFile("file", "manual.txt", "text/plain",
                "压缩机检修需要首先关闭电源。".getBytes(StandardCharsets.UTF_8));
        var upload = mvc.perform(multipart("/knowledge-base/upload").file(file).param("spaceId", first)
                        .header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        var result = mapper.readTree(upload.getContentAsString());
        String id = result.path("id").asText();
        String taskId = result.path("taskId").asText();
        String question = "压缩机检修";
        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(tasks.get(taskId).getStatus()).isEqualTo(TaskStatus.COMPLETED));
            indexing.upsert(second, "space-other-manual", "压缩机检修需要查阅气压记录。", Map.of());
            var evidence = knowledgeIndex.retrieve(first, question, 8).sources();
            assertThat(evidence).extracting(KnowledgeIndex.Evidence::documentId).containsOnly(id);
            assertThat(knowledgeIndex.retrieve(second, question, 8).sources())
                    .extracting(KnowledgeIndex.Evidence::documentId).containsOnly("space-other-manual");
            assertThatThrownBy(() -> indexing.getChunkContext(second, evidence.getFirst().chunkId()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(knowledgeIndex.delete(second, id).alreadyAbsent()).isTrue();
            mvc.perform(delete("/knowledge-base/files/{id}", id).param("spaceId", second).header("Authorization", admin))
                    .andExpect(status().isOk());
            mvc.perform(get("/knowledge-base/files").param("spaceId", first).header("Authorization", admin))
                    .andExpect(jsonPath("$[0].id").value(id));
            mvc.perform(get("/knowledge-base/files").param("spaceId", second).header("Authorization", admin))
                    .andExpect(jsonPath("$.length()").value(0));
            mvc.perform(post("/knowledge-base/retrieve").header("Authorization", reader)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"压缩机检修\"}"))
                    .andExpect(status().isBadRequest());
            assertThat(agentTools.forSpace(null)).noneMatch(tool ->
                    tool.getToolDefinition().name().equals("queryApprovedMedicalKnowledge"));
            var tool = java.util.Arrays.stream(agentTools.forSpace(first))
                    .filter(callback -> callback.getToolDefinition().name().equals("queryApprovedMedicalKnowledge"))
                    .findFirst().orElseThrow();
            assertThat(tool.call("{\"question\":\"压缩机检修\",\"topK\":5}"))
                    .contains(id).doesNotContain("space-other-manual");

            var evaluation = mvc.perform(post("/rag-evaluations").header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                            "spaceId", first, "cases", List.of(Map.of("id", "case-1", "question", question,
                                    "expectedDocumentIds", List.of(id)))))))
                    .andExpect(status().isAccepted()).andReturn().getResponse();
            String evaluationId = mapper.readTree(evaluation.getContentAsString()).path("id").asText();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(tasks.get(evaluationId).getStatus()).isEqualTo(TaskStatus.COMPLETED));
            var evaluationResult = mapper.valueToTree(tasks.get(evaluationId).getResult());
            assertThat(evaluationResult.path("stageMetrics").size()).isEqualTo(3);
            assertThat(evaluationResult.path("cases").get(0).path("retrievedDocumentIds").toString())
                    .contains(id).doesNotContain("space-other-manual");

            indexing.upsert(first, "another-manual-in-first-space", "压缩机检修应先检查电路。", Map.of());
            var allSources = mvc.perform(post("/knowledge-base/retrieve").header("Authorization", reader)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                                    "spaceId", first, "question", question, "topK", 20))))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(mapper.readTree(allSources.getContentAsString()).path("sources")
                    .findValuesAsText("documentId")).containsOnly(id, "another-manual-in-first-space");
        } finally {
            indexing.deleteDocument(first, "another-manual-in-first-space");
            mvc.perform(delete("/knowledge-base/files/{id}", id).param("spaceId", first).header("Authorization", admin))
                    .andExpect(status().isOk());
        }
        assertThat(knowledgeIndex.retrieve(first, question, 8).sources()).isEmpty();
        assertThat(knowledgeIndex.retrieve(second, question, 8).sources()).isNotEmpty();
    }

    @Test
    void createsTypedMedicalSpaceAlongsideLegacySpaceAndAppliesTriageOnlyThere() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        var created = mvc.perform(post("/knowledge-base/spaces").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"药品说明书\",\"medical\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.medical").value(true))
                .andReturn().getResponse();
        String spaceId = mapper.readTree(created.getContentAsString()).path("id").asText();
        mvc.perform(get("/knowledge-base/spaces").header("Authorization", admin))
                .andExpect(jsonPath("$[?(@.id == 'medical')].medical").value(org.hamcrest.Matchers.contains(true)));
        assertThat(knowledgeIndex.space(spaceId).medical()).isTrue();

        mvc.perform(post("/ai/chat/rag").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "chatId", "medical-profile-" + spaceId, "message", "我呼吸困难", "spaceId", spaceId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.riskLevel").value("EMERGENCY"));
        assertThat(java.util.Arrays.stream(agentTools.forSpace(null, false))
                .map(callback -> callback.getToolDefinition().name()))
                .doesNotContain("queryApprovedMedicalKnowledge", "searchPubMed");
        assertThat(java.util.Arrays.stream(agentTools.forSpace(spaceId, true))
                .map(callback -> callback.getToolDefinition().name()))
                .contains("queryApprovedMedicalKnowledge", "searchPubMed");
    }

    @Test
    void deletesSpaceOnlyWithTheAuthenticatedAdminName() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String reader = "Bearer user-test-token-123456789";
        String space = createSpace(admin, "待删除知识库");
        String other = createSpace(admin, "保留知识库");
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        String doc = uploadWikiSource(admin, space, "delete.md", "# 待删除页面\n\n内部知识资料。");
        String keep = uploadWikiSource(admin, other, "keep.md", "# 保留页面\n\n其它空间的资料。");
        var original = java.nio.file.Path.of(FileConstant.FILE_SAVE_DIR, "knowledge-base", "spaces", space);
        var parsed = java.nio.file.Path.of(docMcpProperties.getParsedDir(), space, doc + ".md");
        var wiki = java.nio.file.Path.of(docMcpProperties.getRuntimeDir(), "wiki", space);
        var response = mvc.perform(get("/knowledge-base/files").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse();
        var parseTaskId = mapper.readTree(response.getContentAsString()).get(0).path("documentMcpTaskId").asText();
        String cacheKey = parsing.get(parseTaskId).getItems().getFirst().getCacheKey();
        var cache = java.nio.file.Path.of(docMcpProperties.getRuntimeDir(), "parse-cache", cacheKey + ".md");
        assertThat(cache).exists();
        when(answerModel.generate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.of("""
                        {"pages":[{"kind":"CONCEPTS","title":"待删除页面","markdown":"测试内容。","links":[]}]}
                        """));
        response = mvc.perform(post("/knowledge-base/wiki-compile").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String taskId = mapper.readTree(response.getContentAsString()).path("id").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(tasks.get(taskId).getStatus()).isEqualTo(TaskStatus.COMPLETED));

        mvc.perform(delete("/knowledge-base/spaces/{id}", space)).andExpect(status().isUnauthorized());
        mvc.perform(deleteSpace(space, reader, "reader")).andExpect(status().isForbidden());
        mvc.perform(delete("/knowledge-base/spaces/{id}", space).header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(deleteSpace(space, admin, "wrong")).andExpect(status().isBadRequest());
        mvc.perform(deleteSpace("medical", admin, "admin")).andExpect(status().isBadRequest());
        assertThat(parsed).exists();
        assertThat(wiki.resolve("current")).exists();
        assertThat(repository.findSpace(space)).hasValueSatisfying(value -> assertThat(value.deleting()).isFalse());

        // 非工作台管理的文件阻止原文目录清理，确认重试可以继续完成同一次删除。
        var blocker = java.nio.file.Files.writeString(original.resolve("unmanaged.txt"), "block");
        mvc.perform(deleteSpace(space, admin, "admin")).andExpect(status().isServiceUnavailable());
        assertThat(repository.findSpace(space)).hasValueSatisfying(value -> assertThat(value.deleting()).isTrue());
        assertThatThrownBy(() -> knowledgeIndex.space(space)).isInstanceOf(IllegalStateException.class);
        java.nio.file.Files.delete(blocker);
        mvc.perform(deleteSpace(space, admin, "admin")).andExpect(status().isNoContent());
        mvc.perform(deleteSpace(space, admin, "admin")).andExpect(status().isNoContent());
        assertThat(repository.findSpace(space)).isEmpty();
        assertThat(repository.wikiSources(space, 10)).isEmpty();
        assertThat(repository.loadDocumentChunks(space, doc)).isEmpty();
        assertThat(original).doesNotExist();
        assertThat(parsed).doesNotExist();
        assertThat(cache).doesNotExist();
        assertThat(wiki).doesNotExist();
        assertThat(repository.findSpace(other)).isPresent();
        assertThat(repository.loadDocumentChunks(other, keep)).isNotEmpty();
    }

    private MockHttpServletRequestBuilder deleteSpace(String space, String token, String accountName) throws Exception {
        return delete("/knowledge-base/spaces/{id}", space).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("accountName", accountName)));
    }

    @Test
    void rejectsConcurrentDuplicateContentWithinTheSameSpace() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String space = createSpace(admin, "导入去重");
        String other = createSpace(admin, "导入去重隔离");
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        String raw = "# 相同原文\n同一知识库只登记一次。";
        var first = CompletableFuture.supplyAsync(() -> uploadResponse(admin, space, "first.md", raw));
        var second = CompletableFuture.supplyAsync(() -> uploadResponse(admin, space, "renamed.md", raw));
        var responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertThat(responses.stream().map(response -> response.getStatus())).containsExactlyInAnyOrder(202, 409);
        var accepted = mapper.readTree(responses.stream().filter(response -> response.getStatus() == 202)
                .findFirst().orElseThrow().getContentAsString());
        var duplicate = mapper.readTree(responses.stream().filter(response -> response.getStatus() == 409)
                .findFirst().orElseThrow().getContentAsString());
        assertThat(duplicate.path("code").asText()).isEqualTo("DUPLICATE_DOCUMENT");
        assertThat(duplicate.path("documentId").asText()).isEqualTo(accepted.path("id").asText());
        mvc.perform(get("/knowledge-base/files").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(tasks.get(accepted.path("taskId").asText()).getStatus()).isEqualTo(TaskStatus.COMPLETED));
        assertThat(uploadResponse(admin, space, "again.md", raw).getStatus()).isEqualTo(409);
        uploadWikiSource(admin, other, "same.md", raw);
        mvc.perform(delete("/knowledge-base/files/{id}", accepted.path("id").asText())
                        .param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk());
        uploadWikiSource(admin, space, "restored.md", raw);
    }

    @Test
    void rejectsIdenticalLegacyFilesWithoutAnUploadFingerprint() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String space = createSpace(admin, "旧版导入去重");
        String raw = "# 旧版原文\n文件早于 SHA-256 登记功能。";
        String id = "a".repeat(32);
        var folder = java.nio.file.Path.of(FileConstant.FILE_SAVE_DIR, "knowledge-base", "spaces", space);
        java.nio.file.Files.createDirectories(folder);
        java.nio.file.Files.writeString(folder.resolve(id + ".md"), raw);
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));

        var duplicate = uploadResponse(admin, space, "new-name.md", raw);
        assertThat(duplicate.getStatus()).isEqualTo(409);
        assertThat(mapper.readTree(duplicate.getContentAsString()).path("documentId").asText()).isEqualTo(id);
    }

    @Test
    void compilesTwoSegmentsOfOneDocumentInParallel() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String space = createSpace(admin, "单文档并行编译");
        String docId = "two-segments";
        repository.replaceDocument(space, new KnowledgeRepository.DocumentRecord(docId, "两段原文", "", "", Map.of()), List.of());
        var parsed = java.nio.file.Path.of(docMcpProperties.getParsedDir(), space);
        java.nio.file.Files.createDirectories(parsed);
        java.nio.file.Files.writeString(parsed.resolve(docId + ".md"), "甲".repeat(12_000) + "第二段原文");
        var entered = new CountDownLatch(2);
        when(answerModel.generate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    entered.countDown();
                    if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("两段抽取未并发执行");
                    return java.util.Optional.of("""
                            {"pages":[{"kind":"CONCEPTS","title":"原文概念","markdown":"根据资料提取。","links":[]}]}
                            """);
                });
        var response = mvc.perform(post("/knowledge-base/wiki-compile").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String taskId = mapper.readTree(response.getContentAsString()).path("id").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var task = tasks.get(taskId);
            assertThat(task.getStatus()).as(task.getError()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(mapper.valueToTree(task.getResult()).path("segmentCount").asInt()).isEqualTo(2);
        });
    }

    private org.springframework.mock.web.MockHttpServletResponse uploadResponse(String admin, String space, String name, String raw) {
        var file = new MockMultipartFile("file", name, "text/markdown", raw.getBytes(StandardCharsets.UTF_8));
        try {
            return mvc.perform(multipart("/knowledge-base/upload").file(file).param("spaceId", space)
                    .header("Authorization", admin)).andReturn().getResponse();
        } catch (Exception e) {
            throw new IllegalStateException("上传测试请求失败", e);
        }
    }

    @Test
    void compilesSourceMarkdownIntoVersionedWikiAndRestrictsPagesToTheirSpace() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String reader = "Bearer user-test-token-123456789";
        String space = createSpace(admin, "Wiki 工程手册");
        String other = createSpace(admin, "Wiki 其他资料");
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        String marker = "RAW_ONE_" + "A".repeat(90);
        String first = uploadWikiSource(admin, space, "worker.md", "# Worker\n\n" + marker + " 使用异步索引。");
        uploadWikiSource(admin, space, "index.md", "# 异步索引\n\nRAW_TWO 说明任务队列的工作原理。");
        var sourceInputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var consolidationUnavailable = new AtomicBoolean();
        when(answerModel.generate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    String prompt = call.getArgument(0);
                    String input = call.getArgument(1);
                    if (prompt.contains("给出的页面目录")) {
                        if (consolidationUnavailable.get()) {
                            throw new ModelUnavailableException("MODEL_UNAVAILABLE", "模型提供商暂不可用");
                        }
                        var ids = mapper.readTree(input).findValuesAsText("id");
                        return java.util.Optional.of(mapper.writeValueAsString(Map.of("links", List.of(),
                                "syntheses", List.of(Map.of("title", "索引运行综述", "markdown", "Worker 使用队列执行索引。",
                                        "references", ids)))));
                    }
                    sourceInputs.add(input);
                    return java.util.Optional.of(input.contains("RAW_ONE") ? """
                            {"pages":[{"kind":"ENTITIES","title":"Worker","markdown":"负责执行异步索引。",
                              "links":[{"kind":"CONCEPTS","title":"异步索引"},{"kind":"CONCEPTS","title":"空间外页面"}]}]}
                            """ : """
                            {"pages":[{"kind":"CONCEPTS","title":"异步索引","markdown":"由任务队列调度。","links":[]}]}
                            """);
                });

        mvc.perform(get("/knowledge-base/wiki-graph").param("spaceId", space).header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodes").isEmpty());
        mvc.perform(post("/knowledge-base/wiki-compile").param("spaceId", space).header("Authorization", reader))
                .andExpect(status().isForbidden());
        var response = mvc.perform(post("/knowledge-base/wiki-compile").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String taskId = mapper.readTree(response.getContentAsString()).path("id").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var task = tasks.get(taskId);
            assertThat(task.getStatus()).as(task.getError()).isEqualTo(TaskStatus.COMPLETED);
        });
        assertThat(sourceInputs).anyMatch(input -> input.contains(marker));

        var graphResponse = mvc.perform(get("/knowledge-base/wiki-graph").param("spaceId", space).header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodes.length()").value(3))
                .andExpect(jsonPath("$.edges.length()").value(3))
                .andExpect(jsonPath("$.stale").value(false)).andReturn().getResponse();
        var graph = mapper.readTree(graphResponse.getContentAsString());
        var worker = graph.path("nodes").get(0);
        for (var node : graph.path("nodes")) if ("entities".equals(node.path("kind").asText())) worker = node;
        String pageId = worker.path("id").asText();
        var release = java.nio.file.Path.of(docMcpProperties.getRuntimeDir(), "wiki", space, taskId);
        for (var node : graph.path("nodes")) {
            assertThat(release.resolve(node.path("path").asText())).exists();
        }
        mvc.perform(get("/knowledge-base/wiki-pages/{pageId}", pageId).param("spaceId", space).header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].id").value(first))
                .andExpect(jsonPath("$.markdown").value(containsString("[[concepts/")));
        mvc.perform(get("/knowledge-base/wiki-pages/{pageId}", pageId).param("spaceId", other).header("Authorization", reader))
                .andExpect(status().isNotFound());

        consolidationUnavailable.set(true);
        var degraded = mvc.perform(post("/knowledge-base/wiki-compile").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String degradedId = mapper.readTree(degraded.getContentAsString()).path("id").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var task = tasks.get(degradedId);
            assertThat(task.getStatus()).as(task.getError()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(mapper.valueToTree(task.getResult()).path("consolidationWarning").asText()).contains("已保留");
        });
        assertThat(java.nio.file.Files.readString(release.getParent().resolve("current"))).isEqualTo(taskId);

        when(answerModel.generate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.of("bad-json"));
        var retry = mvc.perform(post("/knowledge-base/wiki-compile").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String failedId = mapper.readTree(retry.getContentAsString()).path("id").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(tasks.get(failedId).getStatus()).isEqualTo(TaskStatus.FAILED));
        assertThat(java.nio.file.Files.readString(release.getParent().resolve("current"))).isEqualTo(taskId);

        mvc.perform(delete("/knowledge-base/files/{id}", first).param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/knowledge-base/wiki-graph").param("spaceId", space).header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodes.length()").value(1))
                .andExpect(jsonPath("$.edges").isEmpty()).andExpect(jsonPath("$.stale").value(true));
    }

    @Test
    void publishesExtractedWikiWhenConsolidationModelsAreUnavailable() throws Exception {
        String admin = "Bearer admin-test-token-123456789";
        String space = createSpace(admin, "Wiki 模型降级测试");
        when(uploadRateLimiter.tryAcquire("admin")).thenReturn(new UploadRateLimiter.RateLimitResult(true, 9, 60));
        uploadWikiSource(admin, space, "worker.md", "# Worker\n\nWORKER_RAW 使用异步索引。");
        uploadWikiSource(admin, space, "index.md", "# 异步索引\n\n索引由任务队列调度。");
        when(answerModel.generate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    String prompt = call.getArgument(0);
                    String input = call.getArgument(1);
                    if (prompt.contains("给出的页面目录")) {
                        throw new ModelUnavailableException("MODEL_UNAVAILABLE", "模型提供商暂不可用");
                    }
                    return java.util.Optional.of(input.contains("WORKER_RAW") ? """
                            {"pages":[{"kind":"ENTITIES","title":"Worker","markdown":"负责执行索引。",
                              "links":[{"kind":"CONCEPTS","title":"异步索引"}]}]}
                            """ : """
                            {"pages":[{"kind":"CONCEPTS","title":"异步索引","markdown":"由任务队列调度。","links":[]}]}
                            """);
                });
        var response = mvc.perform(post("/knowledge-base/wiki-compile").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String taskId = mapper.readTree(response.getContentAsString()).path("id").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var task = tasks.get(taskId);
            assertThat(task.getStatus()).as(task.getError()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(mapper.valueToTree(task.getResult()).path("consolidationWarning").asText()).contains("跨文档综合未完成");
        });
        mvc.perform(get("/knowledge-base/wiki-graph").param("spaceId", space).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.edges.length()").value(1))
                .andExpect(jsonPath("$.consolidationWarning").value(containsString("跨文档综合未完成")));
    }

    private String uploadWikiSource(String admin, String space, String name, String raw) throws Exception {
        var file = new MockMultipartFile("file", name, "text/markdown", raw.getBytes(StandardCharsets.UTF_8));
        var response = mvc.perform(multipart("/knowledge-base/upload").file(file).param("spaceId", space)
                        .header("Authorization", admin))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        var record = mapper.readTree(response.getContentAsString());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(tasks.get(record.path("taskId").asText()).getStatus()).isEqualTo(TaskStatus.COMPLETED));
        return record.path("id").asText();
    }

    private String createSpace(String admin, String name) throws Exception {
        var response = mvc.perform(post("/knowledge-base/spaces").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).path("id").asText();
    }

    private static MockHttpServletRequestBuilder mcp(String body) {
        return post("/mcp").header("Authorization", "Bearer mcp-reader-test-token-123456789")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
