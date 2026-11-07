package com.tianaiagent.docmcp.wiki;

import java.util.List;
import java.util.Map;

public final class WikiModels {
    private WikiModels() {}
    public record Fact(String topic, String title, String statement, String docId,
                       String version, String nodeId, String quote, String category) {
        public Fact { category = category == null || category.isBlank() ? "CONCEPT" : category; }
        public Fact(String topic, String title, String statement, String docId, String version, String nodeId, String quote) {
            this(topic, title, statement, docId, version, nodeId, quote, "CONCEPT");
        }
    }
    public record Section(String heading, String text, List<Integer> claimIds) {}
    public record Page(String pageId, String title, String kind, List<Fact> claims,
                       List<Section> sections, List<String> links, Map<String, String> sourceVersions,
                       String model, String schemaVersion, String markdown, List<String> tags) {
        public Page {
            tags = tags == null ? List.of() : List.copyOf(tags);
            if (markdown == null || markdown.isBlank()) {
                StringBuilder text = new StringBuilder("# " + title + "\n\n");
                for (Section section : sections) {
                    text.append("## ").append(section.heading()).append("\n\n").append(section.text()).append("\n\n");
                    section.claimIds().forEach(id -> text.append('[').append(id).append("] "));
                    text.append("\n\n");
                }
                for (String link : links) text.append("[[").append(link).append("]]\n");
                markdown = text.toString();
            }
        }
        public Page(String pageId, String title, String kind, List<Fact> claims, List<Section> sections,
                    List<String> links, Map<String, String> versions, String model, String schemaVersion) {
            this(pageId, title, kind, claims, sections, links, versions, model, schemaVersion, null, List.of());
        }
    }
    public record Job(String jobId, String docId, String sourceVersion, String status,
                      int attempt, int progress, String errorCode, long inputTokens, long outputTokens) {}
    public record Revision(String revisionId, String pageId, String status, Page page, String createdAt) {}
}
