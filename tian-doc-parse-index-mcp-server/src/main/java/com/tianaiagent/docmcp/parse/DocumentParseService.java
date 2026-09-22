package com.tianaiagent.docmcp.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.index.IndexService;
import com.tianaiagent.docmcp.model.ParseTask;
import com.tianaiagent.docmcp.model.ParseTask.ParseItem;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class DocumentParseService {
    private static final int MAX_BATCH_SIZE = 200;
    private final DocMcpProperties properties;
    private final KnowledgeRepository repository;
    private final SourceLoader sourceLoader;
    private final TikaDocumentParser tika;
    private final MineruClient mineru;
    private final IndexService indexService;
    private final ObjectMapper mapper;
    private final ExecutorService executor;

    public DocumentParseService(DocMcpProperties properties, KnowledgeRepository repository,
                                SourceLoader sourceLoader, TikaDocumentParser tika,
                                MineruClient mineru, IndexService indexService, ObjectMapper mapper) {
        this.properties = properties;
        this.repository = repository;
        this.sourceLoader = sourceLoader;
        this.tika = tika;
        this.mineru = mineru;
        this.indexService = indexService;
        this.mapper = mapper;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getParseWorkers()),
                Thread.ofPlatform().name("document-parse-", 1).daemon(true).factory());
    }

    public ParseTask submit(ParseRequest request) {
        if (request.fileSources() == null || request.fileSources().isEmpty()) throw new IllegalArgumentException("file_sources不能为空");
        if (request.fileSources().size() > MAX_BATCH_SIZE) throw new IllegalArgumentException("单批最多200个文件");
        if (request.fileSources().stream().distinct().count() != request.fileSources().size()) throw new IllegalArgumentException("同一批次不能包含重复来源");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("mode", defaultValue(request.mode(), "auto"));
        options.put("model", defaultValue(request.model(), "vlm"));
        options.put("ocr", request.ocr());
        options.put("formula", request.formula());
        options.put("table", request.table());
        options.put("language", defaultValue(request.language(), "ch"));
        validateOptions(options);

        ParseTask task = ParseTask.queued(UUID.randomUUID().toString());
        task.setOutputDir(Path.of(properties.getParsedDir()).toAbsolutePath().normalize().toString());
        task.setAutoIndex(request.autoIndex());
        task.setOptions(options);
        List<ParseItem> items = new ArrayList<>();
        for (String source : request.fileSources()) {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("file_source不能为空");
            String suppliedHash = request.contentSha256s() == null ? null : request.contentSha256s().get(source);
            if (suppliedHash != null && !validHash(suppliedHash)) {
                throw new IllegalArgumentException(source + " 的content_sha256必须是64位十六进制");
            }
            String fingerprint = validHash(suppliedHash) ? suppliedHash.toLowerCase() : sha256("source:" + source);
            String requestedId = request.docIds() == null ? null : request.docIds().get(source);
            ParseItem item = new ParseItem();
            item.setItemId(UUID.randomUUID().toString());
            item.setSource(source);
            item.setSourceUri(publicSource(source));
            item.setDocId(safeId(requestedId == null ? sourceName(source) + "-" + fingerprint.substring(0, 12) : requestedId));
            item.setContentSha256(fingerprint);
            item.setCacheKey(cacheKey(fingerprint, options));
            Map<String, Object> metadata = request.documentMetadatas() == null
                    ? null : request.documentMetadatas().get(source);
            item.setDocumentMetadata(metadata);
            items.add(item);
        }
        task.setItems(items);
        repository.saveTask(task);
        executor.execute(() -> run(task));
        return task;
    }

    public ParseTask get(String taskId) {
        return repository.findTask(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private void run(ParseTask task) {
        long started = System.nanoTime();
        task.setStatus("running");
        repository.saveTask(task);
        int failed = 0;
        int cacheHits = 0;
        for (ParseItem item : task.getItems()) {
            long itemStarted = System.nanoTime();
            try {
                item.setStatus("running");
                item.setParser(parserName(task.getOptions()));
                repository.saveTask(task);
                Path cache = cachePath(item.getCacheKey());
                String markdown;
                if (Files.exists(cache)) {
                    markdown = Files.readString(cache, StandardCharsets.UTF_8);
                    item.setCacheHit(true);
                    cacheHits++;
                } else {
                    markdown = parse(item, task.getOptions());
                    Files.createDirectories(cache.getParent());
                    Files.writeString(cache, markdown, StandardCharsets.UTF_8);
                }
                Path output = Path.of(task.getOutputDir()).resolve(item.getDocId() + ".md").normalize();
                Files.createDirectories(output.getParent());
                Files.writeString(output, markdown, StandardCharsets.UTF_8);
                if (task.isAutoIndex()) {
                    Map<String, Object> metadata = new LinkedHashMap<>(item.getDocumentMetadata());
                    metadata.put("title", title(markdown, sourceName(item.getSource())));
                    metadata.put("source_uri", item.getSourceUri());
                    metadata.put("content_sha256", item.getContentSha256());
                    IndexService.IndexResult result = indexService.upsert(item.getDocId(), markdown, metadata);
                    item.setLeafNodeCount(result.leafNodeCount());
                    item.setIndexed(true);
                }
                item.setOutputPath(output.toAbsolutePath().toString());
                item.setStatus("completed");
                item.getTimings().put("finish_item_total_ms", elapsed(itemStarted));
            } catch (Exception e) {
                failed++;
                item.setStatus("failed");
                item.setError(rootMessage(e));
                item.getTimings().put("finish_item_total_ms", elapsed(itemStarted));
            }
            repository.saveTask(task);
        }
        task.getTimings().put("batch_document_count", task.getItems().size());
        task.getTimings().put("cache_hit_documents", cacheHits);
        task.getTimings().put("java_task_total_ms", elapsed(started));
        task.setStatus(failed == 0 ? "completed" : failed == task.getItems().size() ? "failed" : "completed_with_errors");
        if (failed > 0) task.setError(failed + "个文档处理失败");
        repository.saveTask(task);
    }

    private String parse(ParseItem item, Map<String, Object> options) {
        String mode = String.valueOf(options.get("mode")).toLowerCase();
        MineruClient.ParseOptions parseOptions = new MineruClient.ParseOptions(
                item.getItemId(), String.valueOf(options.get("model")),
                Boolean.TRUE.equals(options.get("ocr")), Boolean.TRUE.equals(options.get("formula")),
                Boolean.TRUE.equals(options.get("table")), String.valueOf(options.get("language")));
        if ("tika".equals(mode) || ("auto".equals(mode) && !mineru.isAvailable())) {
            item.setParser("tika");
            return tika.parse(sourceLoader.load(item.getSource()));
        }
        if (!mineru.isAvailable()) throw new IllegalStateException("mode=mineru时必须启用MinerU并配置MINERU_TOKEN");
        item.setParser("mineru-v4");
        if (mineru.canParseRemote(item.getSource())) return mineru.parseRemote(item.getSource(), parseOptions);
        return mineru.parseUpload(sourceLoader.load(item.getSource()), parseOptions);
    }

    private Path cachePath(String key) { return Path.of(properties.getRuntimeDir()).resolve("parse-cache").resolve(key + ".md"); }
    private String cacheKey(String fingerprint, Map<String, Object> options) {
        try { return sha256(mapper.writeValueAsString(Map.of("digest", fingerprint, "parser", parserProfile(options), "options", options))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String parserProfile(Map<String, Object> options) {
        String mode = String.valueOf(options.get("mode")).toLowerCase();
        if ("tika".equals(mode) || ("auto".equals(mode) && !mineru.isAvailable())) return "tika-v1";
        return "mineru-v4-upload-v1@" + properties.getMineru().getBaseUrl();
    }
    private String parserName(Map<String, Object> options) {
        return parserProfile(options).startsWith("tika") ? "tika" : "mineru-v4";
    }
    private static void validateOptions(Map<String, Object> options) {
        String mode = String.valueOf(options.get("mode")).toLowerCase();
        if (!List.of("auto", "mineru", "tika").contains(mode)) {
            throw new IllegalArgumentException("mode仅支持auto、mineru或tika");
        }
        String model = String.valueOf(options.get("model"));
        if (!List.of("vlm", "pipeline", "MinerU-HTML").contains(model)) {
            throw new IllegalArgumentException("model仅支持vlm、pipeline或MinerU-HTML");
        }
    }
    private static boolean validHash(String value) { return value != null && value.matches("(?i)[0-9a-f]{64}"); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String sourceName(String value) {
        try { String path = URI.create(value).getPath(); return Path.of(path == null || path.isBlank() ? value : path).getFileName().toString(); }
        catch (Exception e) { return "document"; }
    }
    private static String publicSource(String value) {
        try { URI uri = URI.create(value); return uri.getScheme() == null ? Path.of(value).toAbsolutePath().toString() : new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString(); }
        catch (Exception e) { return value; }
    }
    private static String safeId(String value) {
        String safe = value.replaceAll("[^0-9A-Za-z._\\-\\u4e00-\\u9fff]", "_");
        return safe.length() > 300 ? safe.substring(0, 300) : safe;
    }
    private static String title(String markdown, String fallback) {
        for (String line : markdown.split("\\R", 100)) if (line.startsWith("# ")) return line.substring(2).trim();
        return fallback;
    }
    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    private static String rootMessage(Throwable value) {
        Throwable current = value; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @PreDestroy
    void close() { executor.shutdownNow(); try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public record ParseRequest(List<String> fileSources, Map<String, String> contentSha256s,
                               Map<String, String> docIds, Map<String, Map<String, Object>> documentMetadatas,
                               String mode, String model,
                               boolean ocr, boolean formula, boolean table, String language,
                               boolean autoIndex) {}
    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String taskId) { super("解析任务不存在: " + taskId); }
    }
}
