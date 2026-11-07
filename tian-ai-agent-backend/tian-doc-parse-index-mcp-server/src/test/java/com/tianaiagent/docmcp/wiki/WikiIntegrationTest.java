package com.tianaiagent.docmcp.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.index.IndexService;
import com.tianaiagent.docmcp.query.ChatService;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import com.tianaiagent.docmcp.wiki.WikiModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.junit.jupiter.api.AfterAll;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wiki-test;DB_CLOSE_DELAY=-1",
        "doc-mcp.vector-store=local", "doc-mcp.embedding.provider=local", "doc-mcp.embedding.dimensions=128",
        "doc-mcp.wiki.poll-interval-ms=3600000", "doc-mcp.wiki.auto-publish=false"
})
class WikiIntegrationTest {
    private static final java.nio.file.Path WIKI_FILES = temporaryWiki();
    @DynamicPropertySource static void files(DynamicPropertyRegistry registry) { registry.add("doc-mcp.wiki.output-dir", () -> WIKI_FILES.toString()); }
    private static java.nio.file.Path temporaryWiki() {
        try { return java.nio.file.Files.createTempDirectory("tian-wiki-files-test-"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    @AfterAll static void removeTemporaryWiki() throws Exception {
        if (!WIKI_FILES.getFileName().toString().startsWith("tian-wiki-files-test-")) throw new IllegalStateException("Unexpected test directory");
        try (var paths = java.nio.file.Files.walk(WIKI_FILES)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
        }
    }
    @Autowired WikiRepository wiki;
    @Autowired WikiService service;
    @Autowired WikiCompiler compiler;
    @Autowired KnowledgeRepository knowledge;
    @Autowired IndexService index;
    @Autowired ChatService chat;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DocMcpProperties properties;
    @MockitoBean WikiModelClient model;
    @MockitoSpyBean WikiFileStore files;
    @Autowired WikiFileSync fileSync;

    @BeforeEach void resetState() {
        for (String table : List.of("wiki_file_exports", "wiki_markdown_metadata", "wiki_links", "wiki_tags", "wiki_events", "wiki_refs", "wiki_revisions", "wiki_pages", "wiki_facts", "wiki_jobs", "wiki_sources", "wiki_source_snapshots", "chunks", "documents")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE wiki_worker SET job_id='',lease_until=0");
        properties.getWiki().setMaxCallsPerJob(80);
        reset(model);
        reset(files);
        when(model.generate(anyString(), any())).thenAnswer(call -> {
            Map<?, ?> data = call.getArgument(1);
            if (data.containsKey("chunks")) {
                List<Map<String, Object>> chunks = (List<Map<String, Object>>) data.get("chunks");
                List<Map<String, Object>> facts = chunks.stream().map(c -> Map.<String, Object>of("topic", "随访", "category", "CONCEPT", "statement", "需定期随访并记录症状。", "node_id", c.get("node_id"), "quote", c.get("text"))).toList();
                return new WikiModelClient.Reply(mapper.valueToTree(Map.of("covered_chunk_ids", chunks.stream().map(c -> c.get("node_id")).toList(), "facts", facts)), 100, 40);
            }
            return markdownReply(data);
        });
    }

    private WikiModelClient.Reply markdownReply(Map<?, ?> data) {
        String links = ((List<String>) data.get("allowed_links")).stream().map(id -> "[[" + id + "]]").collect(java.util.stream.Collectors.joining("\n"));
        String markdown = "---\ncategory: " + data.get("kind") + "\ntags: [随访, 知识]\n---\n# " + data.get("title")
                + "\n\n## 知识综述\n\n资料强调定期随访。[1]\n\n## 冲突与适用边界\n\n不能推出未说明的适用人群。[1]\n\n## 关联知识\n\n" + links;
        return new WikiModelClient.Reply(mapper.valueToTree(Map.of("markdown", markdown)), 80, 30);
    }

    @Test void compilesEntitiesThenConceptsThenSynthesesAndPersistsCategorizedFilesAndLog() throws Exception {
        doAnswer(call -> {
            Map<?, ?> data = call.getArgument(1);
            if (!data.containsKey("chunks")) return markdownReply(data);
            var chunks = (List<Map<String, Object>>) data.get("chunks");
            var c = chunks.getFirst();
            var facts = List.of(Map.of("topic", "药物A", "category", "ENTITY", "statement", "资料涉及药物A。", "node_id", c.get("node_id"), "quote", c.get("text")),
                    Map.of("topic", "随访", "category", "CONCEPT", "statement", "应记录随访信息。", "node_id", c.get("node_id"), "quote", c.get("text")));
            return new WikiModelClient.Reply(mapper.valueToTree(Map.of("covered_chunk_ids", chunks.stream().map(v -> v.get("node_id")).toList(), "facts", facts)), 100, 40);
        }).when(model).generate(anyString(), any());
        upload("hierarchy", "药物A用于本资料所列场景，并要求记录随访信息。");
        assertEquals("SUCCEEDED", compileNext().status());
        var inputs = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(model, times(4)).generate(anyString(), inputs.capture());
        var pageInputs = inputs.getAllValues().stream().filter(data -> data.containsKey("kind")).toList();
        assertEquals(List.of("ENTITY", "CONCEPT", "SYNTHESIS"), pageInputs.stream().map(data -> data.get("kind")).toList());
        assertEquals(1, ((List<?>) pageInputs.get(1).get("compiled_pages")).size());
        assertEquals(2, ((List<?>) pageInputs.get(2).get("compiled_pages")).size());
        for (var page : service.pages()) assertTrue(java.nio.file.Files.isRegularFile(WIKI_FILES.resolve(page.get("file_path").toString())));
        String log = java.nio.file.Files.readString(WIKI_FILES.resolve("log.md"));
        assertTrue(log.contains("DRAFT_CREATED")); fileSync.sync();
        assertEquals(log, java.nio.file.Files.readString(WIKI_FILES.resolve("log.md")));
        String index = java.nio.file.Files.readString(WIKI_FILES.resolve("index.md"));
        assertTrue(index.contains("entities/")); assertTrue(index.contains("concepts/")); assertTrue(index.contains("syntheses/"));
    }

    @Test void wrongFinalCategoryCannotCommitPartialHierarchyPages() {
        doAnswer(call -> {
            Map<?, ?> data = call.getArgument(1);
            if (data.containsKey("chunks")) {
                var chunks = (List<Map<String, Object>>) data.get("chunks"); var c = chunks.getFirst();
                return new WikiModelClient.Reply(mapper.valueToTree(Map.of("covered_chunk_ids", chunks.stream().map(v -> v.get("node_id")).toList(), "facts",
                        List.of(Map.of("topic", "随访", "category", "CONCEPT", "statement", "记录症状。", "node_id", c.get("node_id"), "quote", c.get("text"))))), 10, 10);
            }
            var reply = markdownReply(data);
            if ("SYNTHESIS".equals(data.get("kind"))) ((com.fasterxml.jackson.databind.node.ObjectNode) reply.json()).put("markdown", reply.json().path("markdown").asText().replace("category: SYNTHESIS", "category: ENTITY"));
            return reply;
        }).when(model).generate(anyString(), any());
        upload("bad-category", "随访需要记录症状。");
        assertEquals("RETRY", compileNext().status());
        assertTrue(service.pages().isEmpty()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_file_exports", Integer.class));
    }

    void upload(String docId, String text) { index.upsert(docId, text, Map.of("title", "随访资料")); }
    Job compileNext() { Job j = wiki.claim().orElseThrow(); compiler.run(j); return wiki.job(j.jobId()); }
    void publishAll() { for (var page : service.pages()) service.publish(String.valueOf(page.get("page_id")), String.valueOf(page.get("revision_id")), "reviewer-user-id"); }

    @Test void compileIsDurableDraftsNeedReviewAndAnswersExposeWikiProvenance() throws Exception {
        upload("a", "随访需要定期记录症状与变化。");
        assertEquals(1, wiki.jobs().size());
        assertEquals("SUCCEEDED", compileNext().status());
        assertEquals(2, service.pages().size());
        Revision compiled = wiki.readPage(WikiCompiler.sourceId("a"), false);
        assertEquals(compiled.page().markdown(), java.nio.file.Files.readString(WIKI_FILES.resolve("syntheses/" + compiled.pageId() + ".md")));
        assertEquals(compiled.page().markdown(), java.nio.file.Files.readString(WIKI_FILES.resolve(".history").resolve(compiled.pageId()).resolve(compiled.revisionId() + ".md")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_file_exports", Integer.class));
        assertEquals(260, wiki.jobs().getFirst().inputTokens());
        assertTrue(wiki.published().isEmpty());
        assertEquals("rag", chat.answer("随访需要记录什么", 5).get("answer_mode"));
        publishAll();
        assertTrue(java.nio.file.Files.readString(WIKI_FILES.resolve("index.md")).contains("PUBLISHED"));
        assertEquals(2, wiki.published().size());
        var answer = chat.answer("随访需要记录什么", 5);
        assertEquals("wiki-enhanced", answer.get("answer_mode"));
        assertFalse(((List<?>) answer.get("wiki_pages")).isEmpty());
        assertEquals(true, service.lint().get("passed"));
        assertTrue(wiki.events().stream().anyMatch(e -> "PUBLISHED".equals(e.get("EVENT_TYPE")) && "reviewer-user-id".equals(e.get("ACTOR"))));
    }

    @Test void fileFailureRemainsQueuedAndRetryDoesNotRecompileTheModel() throws Exception {
        doThrow(new java.io.IOException("test write failure")).when(files).write(any(), anyBoolean());
        upload("a", "随访需要记录症状。");
        assertEquals("SUCCEEDED", compileNext().status());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_file_exports", Integer.class));
        reset(files); clearInvocations(model);
        fileSync.sync();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_file_exports", Integer.class));
        verifyNoInteractions(model);
        assertTrue(java.nio.file.Files.exists(WIKI_FILES.resolve("syntheses/" + WikiCompiler.sourceId("a") + ".md")));
    }

    @Test void startupRestoresExistingRevisionsWithoutModelCalls() throws Exception {
        upload("a", "随访需要记录症状。"); compileNext();
        java.nio.file.Path page = WIKI_FILES.resolve("syntheses/" + WikiCompiler.sourceId("a") + ".md");
        java.nio.file.Files.delete(page); clearInvocations(model);
        fileSync.restore();
        assertTrue(java.nio.file.Files.exists(page)); verifyNoInteractions(model);
    }

    @Test void sourceChangeInvalidatesPublishedAndHistoricalPagesWithoutDeletingHistory() {
        upload("a", "随访需要定期记录症状。"); compileNext(); publishAll();
        String revision = wiki.published().getFirst().revisionId();
        String version = wiki.currentVersion("a");
        upload("a", "新版随访需要每天记录症状。");
        assertEquals("STALE", wiki.revision(revision).status());
        assertTrue(wiki.published().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.publish(wiki.revision(revision).pageId(), revision, "reviewer"));
        assertEquals("SUCCEEDED", compileNext().status());
        assertTrue(wiki.history(WikiCompiler.sourceId("a")).size() >= 2);
        index.deleteDocument("a");
        assertEquals("随访需要定期记录症状。", service.source("a", version).get("markdown"));
        assertTrue(wiki.published().isEmpty());
        assertTrue(wiki.pages().stream().allMatch(p -> "STALE".equals(p.get("status"))));
    }

    @Test void markdownReadSearchAndBacklinksRespectPublicationAndSourceFreshness() {
        upload("a", "随访需要记录症状。"); compileNext();
        String sourceId = WikiCompiler.sourceId("a");
        assertThrows(IllegalArgumentException.class, () -> service.read(sourceId, true));
        assertTrue(service.search("随访", "随访", 10, true).isEmpty());
        var draft = service.read(sourceId, false);
        assertTrue(draft.get("markdown").toString().contains("# 随访资料"));
        assertFalse(((List<?>) draft.get("backlinks")).isEmpty());
        publishAll();
        assertEquals(2, service.search("随访", "随访", 10, true).size());
        assertFalse(((List<?>) service.read(sourceId, true).get("backlinks")).isEmpty());
        assertEquals(sourceId, service.read("随访资料", true).get("page_id"));
        upload("a", "更新后随访需要记录症状。");
        assertTrue(service.search("随访", "随访", 10, true).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.read(sourceId, true));
    }

    @Test void draftLinksAndTitlesDoNotReplacePublishedGraphUntilPublication() {
        upload("a", "随访需要记录症状。"); compileNext(); publishAll();
        String sourceId = WikiCompiler.sourceId("a");
        Page original = wiki.readPage(sourceId, true).page(); String topicId = original.links().getFirst();
        String markdown = "---\ntags: [修改]\n---\n# 新标题\n\n## 综述\n\n定期随访。[1]\n\n## 冲突与适用边界\n\n仅依据原文。[1]\n";
        var parsed = WikiMarkdown.parse(markdown);
        wiki.enqueue("a", wiki.currentVersion("a")); Job job = wiki.claim().orElseThrow();
        wiki.complete(job, original.claims(), List.of(new Page(sourceId, parsed.title(), "SOURCE", original.claims(), parsed.sections(), parsed.links(), original.sourceVersions(), "test", "wiki-markdown-v2", markdown, parsed.tags())));
        assertEquals(sourceId, wiki.readPage("随访资料", true).pageId());
        assertTrue(wiki.backlinks(topicId, true).stream().anyMatch(p -> sourceId.equals(p.get("page_id"))));
        assertTrue(wiki.backlinks(topicId, false).stream().noneMatch(p -> sourceId.equals(p.get("page_id"))));
        service.publish(sourceId, wiki.readPage(sourceId, false).revisionId(), "reviewer");
        assertTrue(wiki.backlinks(topicId, true).stream().noneMatch(p -> sourceId.equals(p.get("page_id"))));
        assertEquals(sourceId, wiki.readPage("新标题", true).pageId());
        assertThrows(IllegalArgumentException.class, () -> wiki.readPage("随访资料", true));
    }

    @Test void crossDocumentTopicsAccumulateSourcesAndDeletionRebuildsRemainingTopic() {
        upload("a", "随访需要记录症状。"); compileNext();
        upload("b", "随访需要记录不适的变化。"); compileNext();
        Revision topic = wiki.history(service.pages().stream().filter(p -> "CONCEPT".equals(p.get("kind"))).map(p -> p.get("page_id").toString()).findFirst().orElseThrow()).getFirst();
        assertEquals(Set.of("a", "b"), topic.page().sourceVersions().keySet());
        publishAll();
        index.deleteDocument("a");
        assertTrue(wiki.published().stream().noneMatch(r -> r.page().sourceVersions().containsKey("a")));
        assertEquals("SUCCEEDED", compileNext().status());
        Revision rebuilt = wiki.history(topic.pageId()).getFirst();
        assertEquals(Set.of("b"), rebuilt.page().sourceVersions().keySet());
    }

    @Test void forgedQuotesCannotPublishPartialPagesAndRetryStopsAfterThreeAttempts() {
        upload("a", "随访需要记录症状。");
        doThrow(new WikiModelClient.Failure("UNGROUNDED_FACT", true)).when(model).generate(anyString(), any());
        for (int i = 1; i <= 3; i++) {
            Job result = compileNext(); assertEquals(i, result.attempt());
            assertEquals(i == 3 ? "FAILED" : "RETRY", result.status());
            jdbc.update("UPDATE wiki_jobs SET next_run=0");
        }
        assertTrue(wiki.claim().isEmpty()); assertTrue(service.pages().isEmpty());
    }

    @Test void unconfiguredModelBlocksInsteadOfWastingRetriesAndManualCompileCanRetry() {
        upload("a", "随访需要记录症状。");
        doThrow(new WikiModelClient.Failure("CHAT_NOT_CONFIGURED", false)).when(model).generate(anyString(), any());
        assertEquals("BLOCKED", compileNext().status());
        assertTrue(wiki.claim().isEmpty());
        assertNotNull(service.compile("a").get("job_id"));
        assertEquals(2, wiki.jobs().size());
    }

    @Test void staleWorkerAttemptCannotHeartbeatOrPublishAfterLeaseRecovery() {
        upload("a", "随访需要记录症状。");
        Job old = wiki.claim().orElseThrow();
        jdbc.update("UPDATE wiki_worker SET lease_until=0");
        Job resumed = wiki.claim().orElseThrow();
        assertEquals(old.jobId(), resumed.jobId()); assertEquals(2, resumed.attempt());
        assertThrows(WikiRepository.Superseded.class, () -> wiki.heartbeat(old, 50, 0, 0));
        wiki.fail(old, "ZOMBIE", true);
        assertEquals("RUNNING", wiki.job(resumed.jobId()).status());
        compiler.run(resumed); assertEquals("SUCCEEDED", wiki.job(resumed.jobId()).status());
    }

    @Test void sourceMutationWhileModelRunsFencesTheEntireCompilation() {
        upload("a", "随访需要记录症状。"); Job old = wiki.claim().orElseThrow();
        doAnswer(call -> { upload("a", "更新后的随访需要记录异常症状。"); throw new WikiModelClient.Failure("MODEL_REQUEST_FAILED", true); }).when(model).generate(anyString(), any());
        compiler.run(old);
        assertEquals("SUPERSEDED", wiki.job(old.jobId()).status()); assertTrue(service.pages().isEmpty());
        assertTrue(wiki.claim().isPresent(), "A superseded source must release the compiler lease immediately");
    }

    @Test void budgetFailureDoesNotPublishIncompleteKnowledge() {
        properties.getWiki().setMaxCallsPerJob(1);
        upload("a", "随访需要记录症状。");
        assertEquals("FAILED", compileNext().status());
        verifyNoInteractions(model); assertTrue(service.pages().isEmpty());
    }

    @Test void documentFailureRollsBackInvalidationQueueAndSnapshotTogether() {
        upload("a", "随访需要记录症状。"); compileNext(); publishAll();
        String oldVersion = wiki.currentVersion("a"); int jobsBefore = wiki.jobs().size();
        var chunks = knowledge.loadDocumentChunks("a"); var c = chunks.getFirst();
        var document = new KnowledgeRepository.DocumentRecord("a", "changed-title", "", "", Map.of());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> knowledge.replaceDocument(document, List.of(c, c), "失败的更新"));
        assertEquals(oldVersion, wiki.currentVersion("a")); assertEquals(jobsBefore, wiki.jobs().size());
        assertEquals(2, wiki.published().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_source_snapshots", Integer.class));
    }

    @Test void extractionValidatesCoverageQuoteAndSynthesisReferences() throws Exception {
        upload("a", "随访需要记录症状。"); Job job = wiki.jobs().getFirst();
        var chunks = knowledge.loadDocumentChunks("a").stream().filter(KnowledgeRepository.ChunkRecord::leaf).toList();
        String id = chunks.getFirst().nodeId();
        assertThrows(WikiModelClient.Failure.class, () -> WikiCompiler.validateExtraction(mapper.readTree("{\"covered_chunk_ids\":[],\"facts\":[]}"), chunks, job));
        var forged = mapper.valueToTree(Map.of("covered_chunk_ids", List.of(id), "facts", List.of(Map.of("topic", "随访", "statement", "伪造事实", "node_id", id, "quote", "原文不存在的摘录"))));
        assertThrows(WikiModelClient.Failure.class, () -> WikiCompiler.validateExtraction(forged, chunks, job));
        assertThrows(WikiModelClient.Failure.class, () -> WikiCompiler.validateSections(mapper.readTree("{\"sections\":[{\"heading\":\"冲突与边界\",\"text\":\"内容\",\"claim_ids\":[9]}]}"), 1));
    }
}
