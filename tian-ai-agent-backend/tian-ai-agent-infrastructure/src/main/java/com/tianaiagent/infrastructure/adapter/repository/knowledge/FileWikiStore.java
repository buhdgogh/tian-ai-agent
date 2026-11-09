package com.tianaiagent.infrastructure.adapter.repository.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.knowledge.adapter.port.WikiStore;
import com.tianaiagent.domain.knowledge.adapter.repository.ParseTaskRepository;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.model.entity.WikiSnapshot;
import com.tianaiagent.types.config.DocMcpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FileWikiStore implements WikiStore {
    private static final Logger log = LoggerFactory.getLogger(FileWikiStore.class);
    private final Path parsed;
    private final Path root;
    private final Path parseCache;
    private final ObjectMapper mapper;
    private final ParseTaskRepository parseTasks;

    public FileWikiStore(DocMcpProperties properties, ObjectMapper mapper, ParseTaskRepository parseTasks) {
        this.parsed = Path.of(properties.getParsedDir()).toAbsolutePath().normalize();
        this.root = Path.of(properties.getRuntimeDir()).toAbsolutePath().normalize().resolve("wiki");
        this.parseCache = root.resolveSibling("parse-cache");
        this.mapper = mapper;
        this.parseTasks = parseTasks;
    }

    @Override
    public synchronized void deleteSpace(String spaceId) {
        KnowledgeSpace.requireId(spaceId);
        try {
            deleteTree(spaceDirectory(spaceId));
            deleteTree(parsed.resolve(spaceId));
            for (String key : parseTasks.findCacheKeysBySpace(spaceId)) {
                if (key.matches("[a-f0-9]{64}")) Files.deleteIfExists(parseCache.resolve(key + ".md"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法清理知识库解析产物或 Wiki", e);
        }
    }

    @Override
    public String readParsedSource(String spaceId, String docId, int maximumChars) {
        if (!docId.matches("[A-Za-z0-9._-]{1,128}") || docId.equals(".") || docId.equals("..")) {
            throw new IllegalArgumentException("Wiki 原始资料 ID 无效");
        }
        Path source = parsed.resolve(KnowledgeSpace.requireId(spaceId)).resolve(docId + ".md");
        try (var reader = Files.newBufferedReader(source)) {
            char[] text = new char[maximumChars + 1];
            int length = 0;
            while (length < text.length) {
                int count = reader.read(text, length, text.length - length);
                if (count < 0) break;
                length += count;
            }
            if (length > maximumChars) throw new IllegalArgumentException("知识库原文超出单次 Wiki 编译上限");
            return new String(text, 0, length);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("缺少文档 " + docId + " 的完整解析文件，请重新导入该文档", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 Wiki 原始资料 " + docId, e);
        }
    }

    @Override
    public synchronized Optional<WikiSnapshot> load(String spaceId) {
        Path space = spaceDirectory(spaceId);
        try {
            if (!Files.exists(space.resolve("current"))) return Optional.empty();
            Path release = release(space, Files.readString(space.resolve("current")).strip());
            return Optional.of(mapper.readValue(release.resolve("manifest.json").toFile(), WikiSnapshot.class));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 Wiki 编译产物", e);
        }
    }

    @Override
    public synchronized void publish(String spaceId, WikiSnapshot snapshot, Map<String, String> sourceTitles) {
        Path space = spaceDirectory(spaceId);
        Path release = release(space, snapshot.id());
        Path pointer = space.resolve("current");
        Path temporaryPointer = space.resolve(snapshot.id() + ".pending");
        boolean published = false;
        boolean created = false;
        String previous = null;
        try {
            Files.createDirectories(space);
            if (Files.exists(pointer)) previous = Files.readString(pointer).strip();
            Files.createDirectory(release);
            created = true;
            for (var kind : WikiSnapshot.Kind.values()) Files.createDirectory(release.resolve(kind.directory()));
            var pages = snapshot.pages().stream().collect(Collectors.toMap(WikiSnapshot.Page::id, Function.identity()));
            for (var page : snapshot.pages()) {
                Files.writeString(release.resolve(page.path()), page.markdown(pages, sourceTitles));
            }
            mapper.writeValue(release.resolve("manifest.json").toFile(), snapshot);
            Files.writeString(temporaryPointer, snapshot.id());
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Wiki 编译已取消");
            Files.move(temporaryPointer, pointer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            published = true;
        } catch (IOException e) {
            throw new IllegalStateException("无法发布 Wiki 编译产物", e);
        } finally {
            remove(temporaryPointer);
            if (created && !published) remove(release);
        }
        if (previous != null && !previous.equals(snapshot.id())) remove(release(space, previous));
    }

    private Path spaceDirectory(String spaceId) {
        return root.resolve(KnowledgeSpace.requireId(spaceId));
    }

    private Path release(Path space, String id) {
        if (!id.matches("[a-f0-9]{32}")) throw new IllegalStateException("Wiki 版本标识无效");
        return space.resolve(id);
    }

    private void remove(Path path) {
        try {
            deleteTree(path);
        } catch (IOException e) {
            log.warn("清理 Wiki 临时产物失败: {}", path.getFileName());
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
