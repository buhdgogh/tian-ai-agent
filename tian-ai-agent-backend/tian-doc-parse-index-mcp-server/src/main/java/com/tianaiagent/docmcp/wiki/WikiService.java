package com.tianaiagent.docmcp.wiki;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import com.tianaiagent.docmcp.wiki.WikiModels.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WikiService {
    private final WikiRepository wiki;
    private final KnowledgeRepository knowledge;
    private final DocMcpProperties properties;
    public WikiService(WikiRepository wiki, KnowledgeRepository knowledge, DocMcpProperties properties) {
        this.wiki = wiki; this.knowledge = knowledge; this.properties = properties;
    }

    public Map<String, Object> status() { return wiki.status(); }
    public List<Map<String, Object>> pages() { return wiki.pages(); }
    public List<Job> jobs() { return wiki.jobs(); }
    public Job job(String id) { return wiki.job(id); }
    public List<Revision> history(String pageId) { return wiki.history(pageId); }
    public Revision revision(String id) { return wiki.revision(id); }
    public Map<String, Object> source(String docId, String version) { return wiki.snapshot(docId, version); }
    public List<Map<String, Object>> events() { return wiki.events(); }
    public Map<String, Object> read(String target, boolean publishedOnly) {
        Revision r = wiki.readPage(target, publishedOnly);
        var document = WikiMarkdown.parse(r.page().markdown());
        List<Map<String, Object>> links = new ArrayList<>();
        for (String link : document.links()) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("target", link);
            try { Revision resolved = wiki.readPage(link, publishedOnly); item.put("page_id", resolved.pageId()); item.put("title", resolved.page().title()); item.put("resolved", true); }
            catch (IllegalArgumentException e) { item.put("resolved", false); }
            links.add(item);
        }
        return Map.of("page_id", r.pageId(), "revision_id", r.revisionId(), "title", document.title(), "markdown", r.page().markdown(),
                "tags", document.tags(), "links", links, "backlinks", wiki.backlinks(r.pageId(), publishedOnly), "status", r.status());
    }
    public List<Map<String, Object>> search(String query, String tag, int limit, boolean publishedOnly) { return wiki.search(query, tag, limit, publishedOnly); }

    public Map<String, Object> compile(String docId) {
        if (!properties.getWiki().isEnabled()) throw new IllegalArgumentException("Wiki功能未开启");
        knowledge.registerWikiSource(docId);
        String version = wiki.currentVersion(docId);
        if (version == null) throw new IllegalArgumentException("文档不存在或没有可编译内容");
        return Map.of("job_id", wiki.enqueue(docId, version));
    }

    public void publish(String pageId, String revisionId, String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 300) throw new IllegalArgumentException("审核人不能为空");
        Revision revision = wiki.revision(revisionId);
        if (!lintPage(revision).isEmpty()) throw new IllegalArgumentException("Wiki引用检查未通过，不能发布");
        wiki.publish(pageId, revisionId, actor);
    }

    public Map<String, Object> lint() {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (var page : wiki.pages()) {
            Revision r = wiki.revision(String.valueOf(page.get("revision_id")));
            for (String issue : lintPage(r)) issues.add(Map.of("page_id", r.pageId(), "revision_id", r.revisionId(), "issue", issue));
        }
        return Map.of("issues", issues, "passed", issues.isEmpty(), "scope", "structural-and-source-integrity; semantic review is required before publication");
    }

    private List<String> lintPage(Revision r) {
        List<String> issues = new ArrayList<>(); Page p = r.page();
        if (!wiki.fresh(p)) issues.add("STALE_SOURCE");
        if (p.claims().isEmpty() || p.sections().isEmpty()) issues.add("EMPTY_PAGE");
        for (Fact f : p.claims()) {
            var chunk = knowledge.findChunk(f.nodeId()).orElse(null);
            if (chunk == null || !f.docId().equals(chunk.docId()) || !chunk.text().contains(f.quote())) { issues.add("BROKEN_SOURCE_QUOTE"); break; }
        }
        for (String link : WikiMarkdown.parse(p.markdown()).links()) {
            try { wiki.readPage(link, false); } catch (IllegalArgumentException e) { issues.add("BROKEN_PAGE_LINK"); break; }
        }
        return issues;
    }

    /** Wiki is an interpretation layer. Every section must retain citations to canonical source chunks. */
    public Context context(String question, List<Map<String, Object>> sources) {
        if (!properties.getWiki().isEnabled()) return new Context("", List.of());
        Set<String> docs = new HashSet<>(); sources.forEach(s -> docs.add(String.valueOf(s.get("documentId"))));
        Map<String, Integer> cited = new HashMap<>(); sources.forEach(s -> cited.put(String.valueOf(s.get("chunkId")), ((Number) s.get("index")).intValue()));
        Set<String> retrievedChunkIds = Set.copyOf(cited.keySet());
        List<Revision> candidates = wiki.publishedForDocuments(docs).stream()
                .filter(r -> r.page().claims().stream().anyMatch(f -> retrievedChunkIds.contains(f.nodeId())) || titleMatches(question, r.page()))
                .sorted(Comparator.<Revision>comparingInt(r -> rank(question, r.page(), retrievedChunkIds)).reversed().thenComparing(Revision::pageId)).limit(3).toList();
        StringBuilder context = new StringBuilder(); List<Map<String, Object>> used = new ArrayList<>();
        for (Revision r : candidates) {
            if (!lintPage(r).isEmpty()) continue;
            Page page = r.page(); StringBuilder sectionContext = new StringBuilder();
            for (Section section : page.sections()) {
                if (context.length() + sectionContext.length() + section.text().length() > 14000) break;
                List<Integer> indexes = new ArrayList<>(); boolean valid = true;
                for (int claimId : section.claimIds()) {
                    Fact fact = page.claims().get(claimId - 1);
                    if (!cited.containsKey(fact.nodeId())) {
                        if (sources.size() >= 30) { valid = false; break; }
                        var chunk = knowledge.findChunk(fact.nodeId()).orElse(null);
                        if (chunk == null || !fact.docId().equals(chunk.docId()) || !chunk.text().contains(fact.quote())) { valid = false; break; }
                        int index = sources.size() + 1;
                        Map<String, Object> source = new LinkedHashMap<>();
                        source.put("index", index); source.put("chunkId", chunk.nodeId()); source.put("documentId", chunk.docId());
                        source.put("title", chunk.title()); source.put("text", chunk.text()); source.put("sourceUri", chunk.sourceUri());
                        source.put("metadata", chunk.metadata()); source.put("score", 0.0); source.put("via", "wiki-citation");
                        sources.add(source); cited.put(chunk.nodeId(), index);
                    }
                    indexes.add(cited.get(fact.nodeId()));
                }
                if (valid) sectionContext.append(section.heading()).append("：").append(section.text()).append(" 来源")
                        .append(indexes.stream().distinct().map(i -> "[" + i + "]").reduce("", String::concat)).append('\n');
            }
            if (!sectionContext.isEmpty()) {
                context.append("Wiki页面：").append(page.title()).append("\n").append(sectionContext).append('\n');
                used.add(Map.of("page_id", page.pageId(), "revision_id", r.revisionId(), "title", page.title()));
            }
        }
        return new Context(context.toString(), used);
    }
    private static boolean titleMatches(String question, Page p) { return question.toLowerCase(Locale.ROOT).contains(p.title().toLowerCase(Locale.ROOT)); }
    private static int rank(String question, Page p, Set<String> chunks) {
        long matches = p.claims().stream().map(Fact::nodeId).distinct().filter(chunks::contains).count();
        return (Set.of("TOPIC", "CONCEPT", "ENTITY").contains(p.kind()) ? 10 : 0) + (titleMatches(question, p) ? 100 : 0) + (int) Math.min(5, matches) * 20;
    }
    public record Context(String text, List<Map<String, Object>> pages) {}
}
