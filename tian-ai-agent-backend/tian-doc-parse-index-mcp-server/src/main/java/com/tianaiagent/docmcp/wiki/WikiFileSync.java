package com.tianaiagent.docmcp.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

@Service
public class WikiFileSync {
    private static final Logger log = LoggerFactory.getLogger(WikiFileSync.class);
    private final WikiRepository wiki;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WikiFileStore files;
    public WikiFileSync(WikiRepository wiki, JdbcTemplate jdbc, TransactionTemplate tx, WikiFileStore files) {
        this.wiki = wiki; this.jdbc = jdbc; this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.files = files;
    }
    public record Changed() {}

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        tx.executeWithoutResult(s -> {
            wiki.lockSources();
            // Reconcile all revisions after restart, including files deleted while the service was stopped.
            jdbc.update("INSERT INTO wiki_file_exports(revision_id) SELECT revision_id FROM wiki_revisions WHERE revision_id NOT IN (SELECT revision_id FROM wiki_file_exports)");
        });
        sync();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(Changed event) { sync(); }

    @Scheduled(fixedDelayString = "${doc-mcp.wiki.file-sync-interval-ms:10000}")
    public synchronized void sync() {
        try {
            tx.executeWithoutResult(s -> {
                wiki.lockSources();
                List<String> pending = jdbc.queryForList("SELECT revision_id FROM wiki_file_exports ORDER BY revision_id LIMIT 100", String.class);
                for (String id : pending) {
                    var revision = wiki.revision(id);
                    String latest = jdbc.queryForObject("SELECT latest_revision FROM wiki_pages WHERE page_id=?", String.class, revision.pageId());
                    try { files.write(revision, id.equals(latest)); }
                    catch (java.io.IOException e) { throw new FileSyncFailure(e); }
                }
                StringBuilder index = new StringBuilder("# Wiki 页面目录\n\n按实体、概念、综合分析组织。文件为最新编译版本，可能是草稿或已失效；正式问答以数据库审核状态为准。\n\n[操作日志](log.md)\n");
                List<Map<String, Object>> pages = wiki.pages();
                for (String kind : List.of("ENTITY", "CONCEPT", "SYNTHESIS")) {
                    String label = switch (kind) { case "ENTITY" -> "Entities · 实体"; case "CONCEPT" -> "Concepts · 概念"; default -> "Syntheses · 综合分析"; };
                    index.append("\n## ").append(label).append("\n\n| 页面 | 状态 | 版本 |\n|---|---|---|\n");
                    for (Map<String, Object> page : pages) {
                    if (!WikiFileStore.category(String.valueOf(page.get("kind"))).equals(kind)) continue;
                    String title = String.valueOf(page.get("title")).replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("|", "\\|").replace("\n", " ").replace("\r", " ");
                    index.append("| [").append(title).append("](").append(WikiFileStore.relativePath(String.valueOf(page.get("kind")), String.valueOf(page.get("page_id")))).append(") | ").append(page.get("status")).append(" | ").append(page.get("revision_id")).append(" |\n");
                    }
                }
                try {
                    files.writeIndex(index.toString());
                    files.appendLog(jdbc.queryForList("SELECT * FROM wiki_events ORDER BY created_at,event_id"));
                }
                catch (java.io.IOException e) { throw new FileSyncFailure(e); }
                for (String id : pending) jdbc.update("DELETE FROM wiki_file_exports WHERE revision_id=?", id);
            });
        } catch (Exception e) {
            // Transaction rollback keeps the durable queue intact; no half-written file becomes visible.
            log.warn("Wiki Markdown file sync pending; retry scheduled. exception={}", e.getClass().getSimpleName());
        }
    }
    private static class FileSyncFailure extends RuntimeException { FileSyncFailure(Throwable cause) { super(cause); } }
}
