package com.tianaiagent.rag.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.constant.FileConstant;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 本地持久化的稠密向量 + 关键词混合检索，并使用 MMR 做轻量重排序。 */
@Service
public class HybridRetrievalService {
    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_OVERLAP = 120;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_]+|[\\u4e00-\\u9fff]");
    private final Path indexDir = Path.of(FileConstant.FILE_SAVE_DIR, "knowledge-base", "retrieval-index");
    private final Map<String, IndexedDocument> documents = new ConcurrentHashMap<>();
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public HybridRetrievalService(EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() throws IOException {
        Files.createDirectories(indexDir);
        try (var paths = Files.list(indexDir)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    IndexedDocument document = objectMapper.readValue(path.toFile(), IndexedDocument.class);
                    documents.put(document.documentId(), document);
                } catch (IOException ignored) {
                    // 损坏索引可通过重新索引文档恢复
                }
            });
        }
    }

    public int index(String documentId, String fileName, String markdown) {
        List<TextChunk> textChunks = split(markdown);
        if (textChunks.isEmpty()) throw new IllegalArgumentException("文档解析结果为空");
        List<float[]> embeddings = embeddingModel.embed(textChunks.stream().map(TextChunk::text).toList());
        List<IndexedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            TextChunk chunk = textChunks.get(i);
            chunks.add(new IndexedChunk(documentId + "-" + (i + 1), chunk.section(), chunk.text(), embeddings.get(i)));
        }
        IndexedDocument document = new IndexedDocument(documentId, fileName, chunks);
        documents.put(documentId, document);
        persist(document);
        return chunks.size();
    }

    public void delete(String documentId) {
        documents.remove(documentId);
        try { Files.deleteIfExists(indexDir.resolve(documentId + ".json")); }
        catch (IOException e) { throw new IllegalStateException("删除混合检索索引失败", e); }
    }

    public List<Evidence> search(String query, int topK) {
        return search(query, topK, RetrievalMode.HYBRID_MMR);
    }

    /** 为消融评测暴露 Dense、混合召回和 MMR 重排三个阶段。 */
    public List<Evidence> search(String query, int topK, RetrievalMode mode) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("查询内容不能为空");
        RetrievalMode safeMode = mode == null ? RetrievalMode.HYBRID_MMR : mode;
        int limit = Math.max(1, Math.min(topK, 20));
        float[] queryVector = embeddingModel.embed(query.trim());
        Set<String> queryTokens = tokenize(query);
        List<ScoredChunk> candidates = new ArrayList<>();
        for (IndexedDocument document : documents.values()) {
            for (IndexedChunk chunk : document.chunks()) {
                double dense = Math.max(0, cosine(queryVector, chunk.embedding()));
                Set<String> chunkTokens = tokenize(chunk.text());
                long matches = queryTokens.stream().filter(chunkTokens::contains).count();
                double sparse = queryTokens.isEmpty() ? 0 : (double) matches / queryTokens.size();
                double hybrid = dense * 0.7 + sparse * 0.3;
                candidates.add(new ScoredChunk(document, chunk, dense, sparse, hybrid));
            }
        }
        Comparator<ScoredChunk> ranking = safeMode == RetrievalMode.DENSE
                ? Comparator.comparingDouble(ScoredChunk::denseScore).reversed()
                : Comparator.comparingDouble(ScoredChunk::hybridScore).reversed();
        candidates.sort(ranking);
        List<ScoredChunk> pool = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), limit * 4)));
        List<ScoredChunk> selected = safeMode == RetrievalMode.HYBRID_MMR
                ? mmr(pool, limit) : pool.subList(0, Math.min(pool.size(), limit));
        List<Evidence> result = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            ScoredChunk item = selected.get(i);
            result.add(new Evidence(i + 1, item.document.documentId(), item.document.fileName(),
                    item.chunk.chunkId(), item.chunk.section(), item.chunk.text(),
                    round(safeMode == RetrievalMode.DENSE ? item.denseScore : item.hybridScore),
                    round(item.denseScore), round(item.sparseScore)));
        }
        return result;
    }

    public int documentCount() { return documents.size(); }

    private List<ScoredChunk> mmr(List<ScoredChunk> pool, int limit) {
        List<ScoredChunk> selected = new ArrayList<>();
        while (!pool.isEmpty() && selected.size() < limit) {
            ScoredChunk best = null;
            double bestScore = -Double.MAX_VALUE;
            for (ScoredChunk candidate : pool) {
                double redundancy = selected.stream()
                        .mapToDouble(chosen -> cosine(candidate.chunk.embedding(), chosen.chunk.embedding()))
                        .max().orElse(0);
                double score = 0.8 * candidate.hybridScore - 0.2 * Math.max(0, redundancy);
                if (score > bestScore) { bestScore = score; best = candidate; }
            }
            selected.add(best);
            pool.remove(best);
        }
        return selected;
    }

    private List<TextChunk> split(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n");
        List<TextChunk> result = new ArrayList<>();
        String section = "正文";
        StringBuilder buffer = new StringBuilder();
        for (String block : normalized.split("\\n\\s*\\n")) {
            String text = block.trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("#")) section = text.replaceFirst("^#+\\s*", "").trim();
            if (buffer.length() > 0 && buffer.length() + text.length() > CHUNK_SIZE) {
                appendWindows(result, section, buffer.toString());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) buffer.append("\n\n");
            buffer.append(text);
        }
        if (buffer.length() > 0) appendWindows(result, section, buffer.toString());
        return result;
    }

    private void appendWindows(List<TextChunk> result, String section, String text) {
        if (text.length() <= CHUNK_SIZE) { result.add(new TextChunk(section, text)); return; }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + CHUNK_SIZE);
            result.add(new TextChunk(section, text.substring(start, end)));
            if (end == text.length()) break;
            start = end - CHUNK_OVERLAP;
        }
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        String previousChinese = null;
        while (matcher.find()) {
            String token = matcher.group();
            tokens.add(token);
            if (token.matches("[\\u4e00-\\u9fff]")) {
                if (previousChinese != null) tokens.add(previousChinese + token);
                previousChinese = token;
            } else previousChinese = null;
        }
        return tokens;
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) return 0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private double round(double value) { return Math.round(value * 10000d) / 10000d; }

    private void persist(IndexedDocument document) {
        Path target = indexDir.resolve(document.documentId() + ".json");
        Path temp = indexDir.resolve(document.documentId() + ".json.tmp");
        try {
            objectMapper.writeValue(temp.toFile(), document);
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new IllegalStateException("持久化混合检索索引失败", e);
        }
    }

    private record TextChunk(String section, String text) {}
    public enum RetrievalMode { DENSE, HYBRID, HYBRID_MMR }
    private record ScoredChunk(IndexedDocument document, IndexedChunk chunk,
                               double denseScore, double sparseScore, double hybridScore) {}
    public record IndexedDocument(String documentId, String fileName, List<IndexedChunk> chunks) {}
    public record IndexedChunk(String chunkId, String section, String text, float[] embedding) {}
    public record Evidence(int citation, String documentId, String fileName, String chunkId,
                           String section, String text, double score, double denseScore, double sparseScore) {}
}
