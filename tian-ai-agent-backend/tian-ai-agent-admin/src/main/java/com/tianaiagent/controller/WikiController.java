package com.tianaiagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianaiagent.mcp.LlamaIndexRagClient;
import com.tianaiagent.user.CurrentUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Shared curated knowledge base. All management operations require a logged-in user. */
@RestController
@RequestMapping("/knowledge-base/wiki")
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class WikiController {
    private final LlamaIndexRagClient client;
    private final CurrentUserService users;
    public WikiController(LlamaIndexRagClient client, CurrentUserService users) { this.client = client; this.users = users; }
    @GetMapping("/status") public JsonNode status() { users.userId(); return client.wiki("wiki_status", Map.of()); }
    @GetMapping("/pages") public JsonNode pages() { users.userId(); return client.wiki("wiki_pages", Map.of()); }
    @GetMapping("/pages/{pageId}") public JsonNode page(@PathVariable String pageId) { users.userId(); return client.wiki("wiki_page", Map.of("page_id", pageId)); }
    @GetMapping("/pages/{pageId}/content")
    public JsonNode content(@PathVariable String pageId, @RequestParam(defaultValue = "false") boolean publishedOnly) {
        users.userId(); return client.wiki("wiki_read_note", Map.of("page_id", pageId, "published_only", publishedOnly));
    }
    @GetMapping("/pages/{pageId}/markdown")
    public org.springframework.http.ResponseEntity<String> markdown(@PathVariable String pageId, @RequestParam(defaultValue = "false") boolean publishedOnly) {
        var page = content(pageId, publishedOnly);
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(page.path("markdown").asText());
    }
    @GetMapping("/search")
    public JsonNode search(@RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "") String tag,
                           @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "false") boolean publishedOnly) {
        users.userId(); return client.wiki("wiki_search", Map.of("query", query, "tag", tag, "limit", limit, "published_only", publishedOnly));
    }
    @GetMapping("/revisions/{revisionId}") public JsonNode revision(@PathVariable String revisionId) { users.userId(); return client.wiki("wiki_page", Map.of("revision_id", revisionId)); }
    @GetMapping("/sources/{docId}/{version}") public JsonNode source(@PathVariable String docId, @PathVariable String version) { users.userId(); return client.wiki("wiki_source", Map.of("doc_id", docId, "source_version", version)); }
    @GetMapping("/jobs") public JsonNode jobs() { users.userId(); return client.wiki("wiki_jobs", Map.of()); }
    @GetMapping("/jobs/{jobId}") public JsonNode job(@PathVariable String jobId) { users.userId(); return client.wiki("wiki_jobs", Map.of("job_id", jobId)); }
    @PostMapping("/compile") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode compile(@RequestBody CompileRequest request) { users.userId(); if (request.docId() == null || request.docId().isBlank()) throw new IllegalArgumentException("docId不能为空"); return client.wiki("wiki_compile", Map.of("doc_id", request.docId())); }
    @PostMapping("/pages/{pageId}/publish")
    public JsonNode publish(@PathVariable String pageId, @RequestBody PublishRequest request) {
        String reviewer = users.userId();
        if (request.revisionId() == null || request.revisionId().isBlank()) throw new IllegalArgumentException("revisionId不能为空");
        return client.wiki("wiki_publish", Map.of("page_id", pageId, "revision_id", request.revisionId(), "reviewer", reviewer));
    }
    @PostMapping("/lint") public JsonNode lint() { users.userId(); return client.wiki("wiki_lint", Map.of()); }
    @GetMapping("/events") public JsonNode events() { users.userId(); return client.wiki("wiki_events", Map.of()); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
    public record CompileRequest(String docId) {}
    public record PublishRequest(String revisionId) {}
}
