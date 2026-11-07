package com.tianaiagent.toolgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.constant.FileConstant;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 大工具结果外置存储。模型只拿到有预算的预览，读取工具必须携带同一会话的服务端上下文。 */
@Service
public class ToolResultStore {
    public static final String SCOPE_KEY = "toolResultScope";
    private final Path directory;
    private final ObjectMapper mapper;
    private final JTokkitTokenCountEstimator tokenizer = new JTokkitTokenCountEstimator();
    private final int maxTokens;
    private final int batchTokens;
    private final long ttlSeconds;
    private final long maxDiskBytes;

    public ToolResultStore(ObjectMapper mapper,
                           @Value("${agent.tool-gateway.results.directory:}") String directory,
                           @Value("${agent.tool-gateway.results.max-tokens:8192}") int maxTokens,
                           @Value("${agent.tool-gateway.results.batch-max-tokens:24576}") int batchTokens,
                           @Value("${agent.tool-gateway.results.ttl-hours:24}") long ttlHours,
                           @Value("${agent.tool-gateway.results.max-disk-bytes:268435456}") long maxDiskBytes) {
        this.mapper = mapper;
        this.directory = (directory.isBlank() ? Path.of(FileConstant.FILE_SAVE_DIR, "tool-results")
                : Path.of(directory)).toAbsolutePath().normalize();
        if (maxTokens < 256 || batchTokens < maxTokens || ttlHours < 1 || maxDiskBytes < 1) {
            throw new IllegalArgumentException("Invalid tool result budget or storage configuration");
        }
        this.maxTokens = maxTokens;
        this.batchTokens = batchTokens;
        this.ttlSeconds = Math.multiplyExact(ttlHours, 3600);
        this.maxDiskBytes = maxDiskBytes;
    }

    public int batchTokens() { return batchTokens; }
    public int countTokens(String text) { return tokenizer.estimate(text == null ? "" : text); }

    public String limit(String scope, String toolName, String text, int budget) {
        String value = text == null ? "" : text;
        int allowed = Math.min(maxTokens, budget);
        if (allowed < 256) throw new IllegalArgumentException("Tool result budget must be at least 256");
        int tokens = countTokens(value);
        if (tokens <= allowed) return value;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("externalized", true);
        envelope.put("originalEstimatedTokens", tokens);
        envelope.put("totalChars", value.length());
        envelope.put("tokenEncoding", "CL100K_ESTIMATE");
        try {
            String id = save(scope, toolName, value);
            envelope.put("stored", true);
            envelope.put("resultId", id);
            envelope.put("nextAction", "readToolResult(resultId, offset=0, maxTokens); preview is incomplete evidence");
        } catch (IOException e) {
            envelope.put("stored", false);
            envelope.put("nextAction", "Storage unavailable or quota exceeded; narrow the original query. Preview is incomplete.");
        }
        return withPreview(envelope, "preview", value, allowed);
    }

    /** offset/nextOffset 均为 Java 字符偏移；UTF-8 JSON 文件保留原始结果及来源元信息。 */
    public String read(String scope, String resultId, int offset, Integer requestedTokens) {
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("Missing tool result scope");
        if (resultId == null || !resultId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid resultId");
        Path file = directory.resolve(resultId + ".json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Result not found");
            StoredResult stored = mapper.readValue(file.toFile(), StoredResult.class);
            if (!scope.equals(stored.scope()) || stored.createdAt() + ttlSeconds <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("Result not found or expired");
            }
            if (offset < 0 || offset > stored.text().length()
                    || (offset > 0 && offset < stored.text().length() && Character.isLowSurrogate(stored.text().charAt(offset)))) {
                throw new IllegalArgumentException("Invalid offset");
            }
            int budget = requestedTokens == null ? maxTokens : Math.max(256, Math.min(maxTokens, requestedTokens));
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("resultId", resultId);
            page.put("toolName", stored.toolName());
            page.put("offset", offset);
            page.put("totalChars", stored.text().length());
            page.put("nextOffset", stored.text().length());
            page.put("hasMore", false);
            return page(page, stored.text().substring(offset), offset, budget);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read stored tool result", e);
        }
    }

    private String page(Map<String, Object> page, String text, int offset, int budget) {
        int end = fittingPrefix(page, "content", text, budget);
        page.put("nextOffset", offset + end);
        page.put("hasMore", end < text.length());
        page.put("content", text.substring(0, end));
        // nextOffset/hasMore 改动也计入最终 JSON 的预算。
        while (countTokens(json(page)) > budget && end > 0) {
            end = safeEnd(text, end - 1);
            page.put("nextOffset", offset + end);
            page.put("hasMore", end < text.length());
            page.put("content", text.substring(0, end));
        }
        return json(page);
    }

    private String withPreview(Map<String, Object> envelope, String key, String text, int budget) {
        int end = fittingPrefix(envelope, key, text, budget);
        envelope.put(key, text.substring(0, end));
        return json(envelope);
    }

    private int fittingPrefix(Map<String, Object> envelope, String key, String text, int budget) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            envelope.put(key, text.substring(0, safeEnd(text, mid)));
            if (countTokens(json(envelope)) <= budget) low = mid;
            else high = mid - 1;
        }
        int end = safeEnd(text, low);
        envelope.put(key, text.substring(0, end));
        if (countTokens(json(envelope)) > budget) throw new IllegalStateException("Result envelope exceeds token budget");
        return end;
    }

    private static int safeEnd(String text, int end) {
        return end > 0 && end < text.length() && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    private synchronized String save(String scope, String toolName, String text) throws IOException {
        if (scope == null || scope.isBlank()) throw new IOException("Missing scope");
        Files.createDirectories(directory);
        long used = 0;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (!file.getFileName().toString().matches("[a-f0-9]{32}\\.json")
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.getLastModifiedTime(file).toInstant().plusSeconds(ttlSeconds).isBefore(Instant.now())) {
                    Files.deleteIfExists(file);
                } else used += Files.size(file);
            }
        }
        byte[] bytes = mapper.writeValueAsBytes(new StoredResult(scope, toolName, Instant.now().getEpochSecond(), text));
        if (bytes.length > maxDiskBytes - used) throw new IOException("Tool result disk quota exceeded");
        String id = UUID.randomUUID().toString().replace("-", "");
        Path target = directory.resolve(id + ".json");
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) {
            Files.deleteIfExists(target);
            throw error;
        }
        return id;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalStateException("Cannot serialize tool result", e); }
    }

    public record StoredResult(String scope, String toolName, long createdAt, String text) {}
}
