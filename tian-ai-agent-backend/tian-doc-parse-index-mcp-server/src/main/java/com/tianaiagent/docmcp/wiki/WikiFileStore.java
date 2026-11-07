package com.tianaiagent.docmcp.wiki;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.wiki.WikiModels.Revision;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Database revisions remain authoritative; these files are a recoverable Markdown projection. */
@Component
public class WikiFileStore {
    private final Path root;

    public WikiFileStore(DocMcpProperties properties) {
        Path configured = Path.of(properties.getWiki().getOutputDir());
        root = (configured.isAbsolute() ? configured : projectRoot(Path.of("").toAbsolutePath()).resolve(configured)).normalize();
    }

    static Path projectRoot(Path start) {
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) && Files.isDirectory(candidate.resolve("tian-doc-parse-index-mcp-server"))) return candidate;
        }
        return start;
    }

    public Path root() { return root; }

    public void write(Revision revision, boolean latest) throws IOException {
        String pageId = safeName(revision.pageId()); String revisionId = safeName(revision.revisionId());
        writeAtomic(root.resolve(".history").resolve(pageId).resolve(revisionId + ".md"), revision.page().markdown());
        if (latest) {
            Path current = root.resolve(relativePath(revision.page().kind(), pageId));
            writeAtomic(current, revision.page().markdown());
            // Remove only this generated page's former locations after its new file is safely in place.
            for (String directory : List.of("", "entities", "concepts", "syntheses")) {
                Path former = root.resolve(directory).resolve(pageId + ".md");
                if (!former.equals(current)) { validatePath(former); Files.deleteIfExists(former); }
            }
        }
    }

    public static String category(String kind) {
        return switch (kind) {
            case "ENTITY" -> "ENTITY";
            case "CONCEPT", "TOPIC" -> "CONCEPT";
            case "SYNTHESIS", "SOURCE" -> "SYNTHESIS";
            default -> throw new IllegalArgumentException("未知Wiki页面类型");
        };
    }
    public static String directory(String kind) {
        return switch (category(kind)) { case "ENTITY" -> "entities"; case "CONCEPT" -> "concepts"; default -> "syntheses"; };
    }
    public static String relativePath(String kind, String pageId) { return directory(kind) + "/" + safeName(pageId) + ".md"; }

    public void writeIndex(String markdown) throws IOException {
        for (String folder : List.of("entities", "concepts", "syntheses")) { Path path = root.resolve(folder); validatePath(path); Files.createDirectories(path); }
        writeAtomic(root.resolve("index.md"), markdown);
    }

    public void appendLog(List<Map<String, Object>> events) throws IOException {
        Path path = root.resolve("log.md"); validatePath(path);
        String existing = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "# Wiki 操作日志\n\n系统按事件ID追加记录，重新同步不会重复追加。\n";
        Set<String> seen = new HashSet<>();
        var matcher = java.util.regex.Pattern.compile("<!-- event:([a-zA-Z0-9_-]+) -->").matcher(existing);
        while (matcher.find()) seen.add(matcher.group(1));
        StringBuilder text = new StringBuilder(existing);
        for (var event : events) {
            String id = safeName(String.valueOf(event.get("EVENT_ID")));
            if (seen.add(id)) text.append("\n<!-- event:").append(id).append(" -->\n## [").append(event.get("CREATED_AT"))
                    .append("] ").append(event.get("EVENT_TYPE")).append("\n\n- 对象：").append(event.get("SUBJECT"))
                    .append("\n- 操作者：").append(event.get("ACTOR")).append('\n');
        }
        writeAtomic(path, text.toString());
    }

    private static String safeName(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalArgumentException("非法Wiki文件标识");
        return value;
    }

    private void writeAtomic(Path path, String text) throws IOException {
        path = path.toAbsolutePath().normalize();
        validatePath(path);
        Files.createDirectories(path.getParent());
        if (Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).equals(text)) return;
        Path temp = Files.createTempFile(path.getParent(), ".wiki-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private void validatePath(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root.toAbsolutePath().normalize())) throw new IOException("Wiki文件越界");
        for (Path p = path; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("Wiki输出路径不允许符号链接");
    }
}
