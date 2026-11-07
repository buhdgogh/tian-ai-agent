package com.tianaiagent.docmcp.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.wiki.WikiModels.*;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Uses the document database so source invalidation and durable work creation share one transaction. */
@Repository
public class WikiRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final DocMcpProperties properties;
    @Autowired private ApplicationEventPublisher events;

    public WikiRepository(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, DocMcpProperties properties) {
        this.jdbc = jdbc; this.tx = tx; this.mapper = mapper; this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_sources(doc_id VARCHAR(300) PRIMARY KEY, source_version VARCHAR(64) NOT NULL, active BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_source_snapshots(doc_id VARCHAR(300),source_version VARCHAR(64),payload CLOB NOT NULL,created_at VARCHAR(40),PRIMARY KEY(doc_id,source_version))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_jobs(job_id VARCHAR(64) PRIMARY KEY,doc_id VARCHAR(300),source_version VARCHAR(64),status VARCHAR(24),attempt INT,progress INT,error_code VARCHAR(100),next_run BIGINT,input_tokens BIGINT,output_tokens BIGINT,created_at VARCHAR(40),updated_at VARCHAR(40))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS wiki_jobs_queue ON wiki_jobs(status,next_run)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_worker(lock_id INT PRIMARY KEY,job_id VARCHAR(64),lease_until BIGINT)");
        jdbc.update("INSERT INTO wiki_worker(lock_id,job_id,lease_until) SELECT 1,'',0 WHERE NOT EXISTS(SELECT 1 FROM wiki_worker WHERE lock_id=1)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_facts(fact_id VARCHAR(64) PRIMARY KEY,doc_id VARCHAR(300),source_version VARCHAR(64),topic VARCHAR(64),payload CLOB)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS wiki_facts_topic ON wiki_facts(topic)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_pages(page_id VARCHAR(100) PRIMARY KEY,title VARCHAR(500),kind VARCHAR(20),latest_revision VARCHAR(64),published_revision VARCHAR(64))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_revisions(revision_id VARCHAR(64) PRIMARY KEY,page_id VARCHAR(100),status VARCHAR(24),payload CLOB,created_at VARCHAR(40))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_refs(revision_id VARCHAR(64),doc_id VARCHAR(300),source_version VARCHAR(64),PRIMARY KEY(revision_id,doc_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS wiki_refs_doc ON wiki_refs(doc_id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_events(event_id VARCHAR(64) PRIMARY KEY,event_type VARCHAR(40),subject VARCHAR(300),actor VARCHAR(300),created_at VARCHAR(40))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_links(revision_id VARCHAR(64),target VARCHAR(300),PRIMARY KEY(revision_id,target))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS wiki_links_target ON wiki_links(target)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_tags(revision_id VARCHAR(64),tag VARCHAR(80),PRIMARY KEY(revision_id,tag))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS wiki_tags_tag ON wiki_tags(tag)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_markdown_metadata(revision_id VARCHAR(64) PRIMARY KEY,normalized_title VARCHAR(500),body CLOB)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS wiki_markdown_title ON wiki_markdown_metadata(normalized_title)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_file_exports(revision_id VARCHAR(64) PRIMARY KEY)");
        // Idempotent migration for revisions created before Markdown metadata was introduced.
        for (String revision : jdbc.queryForList("SELECT revision_id FROM wiki_revisions", String.class)) indexMarkdown(revision, revision(revision).page());
    }

    /** Caller holds the documents transaction. No LLM/network calls here. */
    public void sourceChanged(String docId, String version) {
        jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE");
        if (version.equals(currentVersion(docId))) return;
        List<String> affected = relatedDocuments(docId);
        jdbc.update("MERGE INTO wiki_sources(doc_id,source_version,active) KEY(doc_id) VALUES(?,?,TRUE)", docId, version);
        invalidate(docId);
        if (properties.getWiki().isEnabled() && properties.getWiki().isAutoCompile()) {
            enqueue(docId, version);
            for (String other : affected) { String v = currentVersion(other); if (v != null) enqueue(other, v); }
        }
    }

    public void sourceDeleted(String docId) {
        jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE");
        List<String> affected = relatedDocuments(docId);
        jdbc.update("UPDATE wiki_sources SET active=FALSE WHERE doc_id=?", docId);
        invalidate(docId);
        jdbc.update("DELETE FROM wiki_facts WHERE doc_id=?", docId);
        if (properties.getWiki().isEnabled() && properties.getWiki().isAutoCompile()) {
            for (String other : affected) { String v = currentVersion(other); if (v != null) enqueue(other, v); }
        }
    }

    private List<String> relatedDocuments(String docId) {
        return jdbc.queryForList("SELECT DISTINCT doc_id FROM wiki_facts WHERE doc_id<>? AND topic IN (SELECT topic FROM wiki_facts WHERE doc_id=?)", String.class, docId, docId);
    }

    private void invalidate(String docId) {
        jdbc.update("UPDATE wiki_revisions SET status='STALE' WHERE revision_id IN (SELECT revision_id FROM wiki_refs WHERE doc_id=?)", docId);
        jdbc.update("UPDATE wiki_jobs SET status='SUPERSEDED',updated_at=? WHERE doc_id=? AND status IN ('QUEUED','RETRY','RUNNING','BLOCKED')", now(), docId);
        jdbc.update("UPDATE wiki_worker SET job_id='',lease_until=0 WHERE lock_id=1 AND job_id IN (SELECT job_id FROM wiki_jobs WHERE doc_id=? AND status='SUPERSEDED')", docId);
        event("SOURCE_INVALIDATED", docId, "system");
        filesChanged();
    }

    public String currentVersion(String docId) {
        return jdbc.query("SELECT source_version FROM wiki_sources WHERE doc_id=? AND active=TRUE", (rs, i) -> rs.getString(1), docId).stream().findFirst().orElse(null);
    }

    public void lockSources() { jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE"); }

    public void archive(String docId, String version, Map<String, Object> snapshot) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM wiki_source_snapshots WHERE doc_id=? AND source_version=?", Integer.class, docId, version) == 0)
            jdbc.update("INSERT INTO wiki_source_snapshots VALUES(?,?,?,?)", docId, version, json(snapshot), now());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot(String docId, String version) {
        return jdbc.query("SELECT payload FROM wiki_source_snapshots WHERE doc_id=? AND source_version=?", (rs, i) -> read(rs.getString(1), Map.class), docId, version)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("来源快照不存在"));
    }

    public String enqueue(String docId, String version) {
        return tx.execute(s -> {
            jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE");
            if (!version.equals(currentVersion(docId))) throw new IllegalArgumentException("资料版本已变化，请刷新后重试");
            List<String> active = jdbc.queryForList("SELECT job_id FROM wiki_jobs WHERE doc_id=? AND source_version=? AND status IN ('QUEUED','RETRY','RUNNING')", String.class, docId, version);
            if (!active.isEmpty()) return active.getFirst();
            String id = id();
            jdbc.update("INSERT INTO wiki_jobs VALUES(?,?,?,'QUEUED',0,0,'',0,0,0,?,?)", id, docId, version, now(), now());
            event("COMPILE_QUEUED", id, "system");
            return id;
        });
    }

    public Optional<Job> claim() {
        return tx.execute(s -> {
            long time = System.currentTimeMillis();
            var lock = jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE");
            if (((Number) lock.get("LEASE_UNTIL")).longValue() > time) return Optional.empty();
            String abandoned = String.valueOf(lock.get("JOB_ID"));
            jdbc.update("UPDATE wiki_jobs SET status=CASE WHEN attempt>=? THEN 'FAILED' ELSE 'RETRY' END,error_code='WORKER_LEASE_EXPIRED',next_run=0 WHERE job_id=? AND status='RUNNING'", maxAttempts(), abandoned);
            List<String> ids = jdbc.queryForList("SELECT job_id FROM wiki_jobs WHERE status IN ('QUEUED','RETRY') AND next_run<=? ORDER BY created_at,job_id LIMIT 1", String.class, time);
            if (ids.isEmpty()) { jdbc.update("UPDATE wiki_worker SET job_id='',lease_until=0 WHERE lock_id=1"); return Optional.empty(); }
            String id = ids.getFirst();
            jdbc.update("UPDATE wiki_jobs SET status='RUNNING',attempt=attempt+1,progress=0,error_code='',updated_at=? WHERE job_id=?", now(), id);
            jdbc.update("UPDATE wiki_worker SET job_id=?,lease_until=? WHERE lock_id=1", id, time + leaseMillis());
            return Optional.of(job(id));
        });
    }

    public void heartbeat(Job job, int progress, long inputTokens, long outputTokens) {
        tx.executeWithoutResult(s -> {
            fence(job);
            String jobId = job.jobId();
            jdbc.update("UPDATE wiki_worker SET lease_until=? WHERE lock_id=1 AND job_id=?", System.currentTimeMillis() + leaseMillis(), jobId);
            jdbc.update("UPDATE wiki_jobs SET progress=GREATEST(progress,?),input_tokens=input_tokens+?,output_tokens=output_tokens+?,updated_at=? WHERE job_id=?", Math.min(95, progress), Math.max(0, inputTokens), Math.max(0, outputTokens), now(), jobId);
        });
    }

    private void fence(Job expected) {
        var lock = jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE");
        String jobId = expected.jobId(); Job current = job(jobId);
        if (!jobId.equals(lock.get("JOB_ID")) || ((Number) lock.get("LEASE_UNTIL")).longValue() < System.currentTimeMillis()
                || !"RUNNING".equals(current.status()) || current.attempt() != expected.attempt()) throw new Superseded();
    }

    public List<Fact> facts(String topic, String excludeDoc) {
        return jdbc.query("SELECT f.payload FROM wiki_facts f JOIN wiki_sources s ON f.doc_id=s.doc_id AND f.source_version=s.source_version WHERE s.active=TRUE AND f.topic=? AND f.doc_id<>? ORDER BY f.doc_id,f.fact_id",
                (rs, i) -> read(rs.getString(1), Fact.class), topic, excludeDoc);
    }

    /** All generated pages and extracted facts are committed together; old/new evidence cannot mix. */
    public void complete(Job job, List<Fact> facts, List<Page> pages) {
        tx.executeWithoutResult(s -> {
            fence(job);
            var version = jdbc.queryForList("SELECT source_version FROM wiki_sources WHERE doc_id=? AND active=TRUE FOR UPDATE", String.class, job.docId());
            if (version.isEmpty() || !job.sourceVersion().equals(version.getFirst())) throw new Superseded();
            for (Page page : pages) {
                for (var ref : page.sourceVersions().entrySet()) {
                    String current = currentVersion(ref.getKey());
                    if (!ref.getValue().equals(current)) throw new Superseded();
                }
            }
            jdbc.update("DELETE FROM wiki_facts WHERE doc_id=?", job.docId());
            for (Fact fact : facts) jdbc.update("INSERT INTO wiki_facts VALUES(?,?,?,?,?)", id(), fact.docId(), fact.version(), fact.topic(), json(fact));
            for (Page page : pages) {
                String revision = id();
                boolean publish = properties.getWiki().isAutoPublish();
                jdbc.update("INSERT INTO wiki_revisions VALUES(?,?,?,?,?)", revision, page.pageId(), publish ? "PUBLISHED" : "DRAFT", json(page), now());
                jdbc.update("INSERT INTO wiki_file_exports(revision_id) VALUES(?)", revision);
                indexMarkdown(revision, page);
                for (var ref : page.sourceVersions().entrySet()) jdbc.update("INSERT INTO wiki_refs VALUES(?,?,?)", revision, ref.getKey(), ref.getValue());
                if (jdbc.update("UPDATE wiki_pages SET title=?,kind=?,latest_revision=? WHERE page_id=?", page.title(), page.kind(), revision, page.pageId()) == 0)
                    jdbc.update("INSERT INTO wiki_pages VALUES(?,?,?,?,NULL)", page.pageId(), page.title(), page.kind(), revision);
                if (publish) jdbc.update("UPDATE wiki_pages SET published_revision=? WHERE page_id=?", revision, page.pageId());
                event(publish ? "AUTO_PUBLISHED" : "DRAFT_CREATED", revision, "compiler");
            }
            jdbc.update("UPDATE wiki_jobs SET status='SUCCEEDED',progress=100,error_code='',updated_at=? WHERE job_id=?", now(), job.jobId());
            jdbc.update("UPDATE wiki_worker SET job_id='',lease_until=0 WHERE lock_id=1 AND job_id=?", job.jobId());
            filesChanged();
        });
    }

    public void fail(Job job, String code, boolean retryable) {
        tx.executeWithoutResult(s -> {
            jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE");
            Job current = job(job.jobId());
            if (!"RUNNING".equals(current.status()) || current.attempt() != job.attempt()) return;
            String state = "CHAT_NOT_CONFIGURED".equals(code) ? "BLOCKED" : retryable && job.attempt() < maxAttempts() ? "RETRY" : "FAILED";
            jdbc.update("UPDATE wiki_jobs SET status=?,error_code=?,next_run=?,updated_at=? WHERE job_id=?", state, code,
                    System.currentTimeMillis() + (1L << Math.min(job.attempt(), 8)) * 5000, now(), job.jobId());
            jdbc.update("UPDATE wiki_worker SET job_id='',lease_until=0 WHERE lock_id=1 AND job_id=?", job.jobId());
            event("COMPILE_" + state, job.jobId(), "compiler");
        });
    }

    public Job job(String id) {
        return jdbc.query("SELECT * FROM wiki_jobs WHERE job_id=?", (rs, i) -> new Job(rs.getString("job_id"), rs.getString("doc_id"), rs.getString("source_version"), rs.getString("status"), rs.getInt("attempt"), rs.getInt("progress"), rs.getString("error_code"), rs.getLong("input_tokens"), rs.getLong("output_tokens")), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Wiki任务不存在"));
    }

    public List<Job> jobs() {
        return jdbc.queryForList("SELECT job_id FROM wiki_jobs ORDER BY created_at DESC LIMIT 100", String.class).stream().map(this::job).toList();
    }

    public List<Map<String, Object>> pages() {
        return jdbc.query("SELECT p.*,r.status,r.created_at FROM wiki_pages p JOIN wiki_revisions r ON p.latest_revision=r.revision_id ORDER BY p.kind,p.title LIMIT 1000", (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page_id", rs.getString("page_id")); row.put("title", rs.getString("title")); row.put("kind", rs.getString("kind"));
            row.put("revision_id", rs.getString("latest_revision")); row.put("published_revision", rs.getString("published_revision"));
            row.put("status", rs.getString("status")); row.put("created_at", rs.getString("created_at"));
            row.put("category", WikiFileStore.category(rs.getString("kind")));
            row.put("file_path", WikiFileStore.relativePath(rs.getString("kind"), rs.getString("page_id"))); return row;
        });
    }

    public Revision revision(String revisionId) {
        return jdbc.query("SELECT * FROM wiki_revisions WHERE revision_id=?", (rs, i) -> new Revision(rs.getString("revision_id"), rs.getString("page_id"), rs.getString("status"), read(rs.getString("payload"), Page.class), rs.getString("created_at")), revisionId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Wiki版本不存在"));
    }

    private void indexMarkdown(String revision, Page page) {
        var parsed = WikiMarkdown.parse(page.markdown());
        jdbc.update("MERGE INTO wiki_markdown_metadata(revision_id,normalized_title,body) KEY(revision_id) VALUES(?,?,?)", revision, parsed.title().toLowerCase(Locale.ROOT), parsed.body());
        for (String target : parsed.links()) jdbc.update("MERGE INTO wiki_links(revision_id,target) KEY(revision_id,target) VALUES(?,?)", revision, target);
        for (String tag : parsed.tags()) jdbc.update("MERGE INTO wiki_tags(revision_id,tag) KEY(revision_id,tag) VALUES(?,?)", revision, tag);
    }

    public Revision readPage(String target, boolean publishedOnly) {
        String pointer = publishedOnly ? "published_revision" : "latest_revision";
        List<String> ids = jdbc.queryForList("SELECT " + pointer + " FROM wiki_pages WHERE page_id=? AND " + pointer + " IS NOT NULL", String.class, target);
        if (ids.isEmpty()) ids = jdbc.queryForList("SELECT p." + pointer + " FROM wiki_pages p JOIN wiki_markdown_metadata m ON p." + pointer + "=m.revision_id WHERE m.normalized_title=?", String.class, target.toLowerCase(Locale.ROOT));
        List<Revision> matches = ids.stream().map(this::revision)
                .filter(r -> !publishedOnly || "PUBLISHED".equals(r.status()) && fresh(r.page())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "Wiki页面不存在或没有有效发布版本" : "页面标题存在歧义，请使用page_id");
        return matches.getFirst();
    }

    public List<Map<String, Object>> backlinks(String pageId, boolean publishedOnly) {
        Revision target = readPage(pageId, publishedOnly);
        String pointer = publishedOnly ? "published_revision" : "latest_revision";
        List<String> refs = jdbc.queryForList("SELECT DISTINCT p." + pointer + " FROM wiki_pages p JOIN wiki_links l ON p." + pointer + "=l.revision_id WHERE l.target=? OR LOWER(l.target)=LOWER(?)", String.class, target.pageId(), target.page().title());
        return refs.stream().map(this::revision).filter(r -> !publishedOnly || "PUBLISHED".equals(r.status()) && fresh(r.page()))
                .filter(r -> WikiMarkdown.parse(r.page().markdown()).links().stream().anyMatch(link -> resolvesTo(link, target.pageId(), publishedOnly)))
                .map(r -> Map.<String, Object>of("page_id", r.pageId(), "revision_id", r.revisionId(), "title", r.page().title())).toList();
    }

    private boolean resolvesTo(String link, String pageId, boolean publishedOnly) {
        try { return readPage(link, publishedOnly).pageId().equals(pageId); } catch (IllegalArgumentException e) { return false; }
    }

    public List<Map<String, Object>> search(String query, String tag, int limit, boolean publishedOnly) {
        if (query == null || query.length() > 500 || tag != null && tag.length() > 80) throw new IllegalArgumentException("搜索条件过长");
        String needle = "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String pointer = publishedOnly ? "published_revision" : "latest_revision";
        String sql = "SELECT r.revision_id FROM wiki_pages p JOIN wiki_revisions r ON p." + pointer + "=r.revision_id JOIN wiki_markdown_metadata m ON r.revision_id=m.revision_id WHERE LOWER(m.body) LIKE ? ESCAPE '!'";
        List<Object> args = new ArrayList<>(List.of(needle));
        if (tag != null && !tag.isBlank()) { sql += " AND EXISTS(SELECT 1 FROM wiki_tags t WHERE t.revision_id=r.revision_id AND t.tag=?)"; args.add(tag.toLowerCase(Locale.ROOT)); }
        sql += " ORDER BY p.title,p.page_id";
        return jdbc.queryForList(sql, String.class, args.toArray()).stream().map(this::revision)
                .filter(r -> !publishedOnly || "PUBLISHED".equals(r.status()) && fresh(r.page())).limit(Math.max(1, Math.min(limit, 100)))
                .map(r -> Map.<String, Object>of("page_id", r.pageId(), "revision_id", r.revisionId(), "title", r.page().title(), "tags", WikiMarkdown.parse(r.page().markdown()).tags(), "status", r.status())).toList();
    }

    public List<Revision> history(String pageId) {
        return jdbc.queryForList("SELECT revision_id FROM wiki_revisions WHERE page_id=? ORDER BY created_at DESC LIMIT 100", String.class, pageId).stream().map(this::revision).toList();
    }

    public void publish(String pageId, String revisionId, String actor) {
        tx.executeWithoutResult(s -> {
            // Serialize publication with source changes and compiler commits.
            jdbc.queryForMap("SELECT * FROM wiki_worker WHERE lock_id=1 FOR UPDATE");
            Revision r = revision(revisionId);
            if (!pageId.equals(r.pageId()) || !Set.of("DRAFT", "PUBLISHED").contains(r.status()) || !fresh(r.page()))
                throw new IllegalArgumentException("版本已失效或不属于该页面，请重新编译并审核");
            jdbc.update("UPDATE wiki_revisions SET status='PUBLISHED' WHERE revision_id=?", revisionId);
            jdbc.update("UPDATE wiki_pages SET published_revision=? WHERE page_id=?", revisionId, pageId);
            event("PUBLISHED", revisionId, actor);
            filesChanged();
        });
    }

    public List<Revision> published() {
        return jdbc.queryForList("SELECT published_revision FROM wiki_pages WHERE published_revision IS NOT NULL", String.class).stream()
                .map(this::revision).filter(r -> "PUBLISHED".equals(r.status()) && fresh(r.page())).toList();
    }

    public List<Revision> publishedForDocuments(Set<String> docs) {
        if (docs.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(docs.size(), "?"));
        return jdbc.queryForList("SELECT DISTINCT p.published_revision FROM wiki_pages p JOIN wiki_refs r ON p.published_revision=r.revision_id WHERE r.doc_id IN (" + placeholders + ")", String.class, docs.toArray())
                .stream().map(this::revision).filter(r -> "PUBLISHED".equals(r.status()) && fresh(r.page())).toList();
    }

    public boolean fresh(Page page) { return !page.sourceVersions().isEmpty() && page.sourceVersions().entrySet().stream().allMatch(e -> e.getValue().equals(currentVersion(e.getKey()))); }
    public List<Map<String, Object>> events() { return jdbc.queryForList("SELECT * FROM wiki_events ORDER BY created_at DESC LIMIT 100"); }
    public Map<String, Object> status() {
        return Map.of("enabled", properties.getWiki().isEnabled(), "auto_publish", properties.getWiki().isAutoPublish(),
                "markdown_output_dir", properties.getWiki().getOutputDir(),
                "file_exports_pending", jdbc.queryForObject("SELECT COUNT(*) FROM wiki_file_exports", Integer.class),
                "pages", jdbc.queryForObject("SELECT COUNT(*) FROM wiki_pages", Integer.class),
                "version", jdbc.queryForObject("SELECT COUNT(*) FROM wiki_events", Long.class),
                "job_states", jdbc.queryForList("SELECT status,COUNT(*) AS total FROM wiki_jobs GROUP BY status"));
    }
    private void event(String type, String subject, String actor) { jdbc.update("INSERT INTO wiki_events VALUES(?,?,?,?,?)", id(), type, subject, actor, now()); }
    private void filesChanged() { if (events != null) events.publishEvent(new WikiFileSync.Changed()); }
    private long leaseMillis() { return Math.max(properties.getWiki().getRequestTimeoutSeconds() + 30, properties.getWiki().getLeaseSeconds()) * 1000L; }
    private int maxAttempts() { return Math.max(1, properties.getWiki().getMaxAttempts()); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Wiki序列化失败", e); } }
    private <T> T read(String json, Class<T> type) { try { return mapper.readValue(json, type); } catch (Exception e) { throw new IllegalStateException("Wiki数据损坏", e); } }
    public static String key(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String id() { return UUID.randomUUID().toString(); }
    private static String now() { return Instant.now().toString(); }
    public static class Superseded extends RuntimeException {}
}
