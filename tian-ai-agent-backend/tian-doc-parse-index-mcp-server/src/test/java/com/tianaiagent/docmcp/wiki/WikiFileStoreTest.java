package com.tianaiagent.docmcp.wiki;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.wiki.WikiModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WikiFileStoreTest {
    @TempDir Path directory;
    WikiFileStore store() { var p = new DocMcpProperties(); p.getWiki().setOutputDir(directory.toString()); return new WikiFileStore(p); }
    Revision revision(String pageId, String id, String markdown) {
        return new Revision(id, pageId, "DRAFT", new Page(pageId, "页面", "SOURCE", List.of(), List.of(), List.of(), Map.of(), "test", "v2", markdown, List.of()), "now");
    }
    @Test void oldRevisionIsArchivedWithoutOverwritingLatestAndNamesCannotEscape() throws Exception {
        var store = store();
        Files.writeString(directory.resolve("source-a.md"), "legacy flat file");
        store.write(revision("source-a", "revision-new", "# 新版\n正文\n"), true);
        store.write(revision("source-a", "revision-old", "# 旧版\n"), false);
        assertEquals("# 新版\n正文\n", Files.readString(directory.resolve("syntheses/source-a.md")));
        assertFalse(Files.exists(directory.resolve("source-a.md")));
        assertEquals("# 旧版\n", Files.readString(directory.resolve(".history/source-a/revision-old.md")));
        assertThrows(IllegalArgumentException.class, () -> store.write(revision("../outside", "r", "bad"), true));
        try (var paths = Files.walk(directory)) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
    }
    @Test void moduleAndRootLaunchesResolveTheSameProjectDirectory() throws Exception {
        Path root = directory.resolve("backend"); Files.createDirectories(root.resolve("tian-doc-parse-index-mcp-server")); Files.writeString(root.resolve("pom.xml"), "test");
        assertEquals(root, WikiFileStore.projectRoot(root));
        assertEquals(root, WikiFileStore.projectRoot(root.resolve("tian-doc-parse-index-mcp-server")));
    }
    @Test void layoutCreatesAllCategoriesAndLogAppendIsIdempotent() throws Exception {
        var store = store(); store.writeIndex("# Wiki\n");
        for (String folder : List.of("entities", "concepts", "syntheses")) assertTrue(Files.isDirectory(directory.resolve(folder)));
        var event = Map.<String, Object>of("EVENT_ID", "event-1", "CREATED_AT", "2026-11-04", "EVENT_TYPE", "DRAFT_CREATED", "SUBJECT", "page-1", "ACTOR", "compiler");
        store.appendLog(List.of(event)); String first = Files.readString(directory.resolve("log.md"));
        store.appendLog(List.of(event)); assertEquals(first, Files.readString(directory.resolve("log.md")));
        assertEquals("entities/page.md", WikiFileStore.relativePath("ENTITY", "page"));
        assertEquals("concepts/page.md", WikiFileStore.relativePath("CONCEPT", "page"));
        assertEquals("syntheses/page.md", WikiFileStore.relativePath("SYNTHESIS", "page"));
    }
}
