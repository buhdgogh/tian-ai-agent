package com.tianaiagent.docmcp.index;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import com.tianaiagent.docmcp.storage.KnowledgeRepository.ChunkRecord;
import com.tianaiagent.docmcp.storage.KnowledgeRepository.DocumentRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IndexService {
    private static final Pattern WORD = Pattern.compile("[a-zA-Z0-9_.+-]+|[\\p{IsHan}]");
    private final KnowledgeRepository repository;
    private final HierarchicalChunker chunker;
    private final EmbeddingService embeddings;
    private final DocMcpProperties properties;
    private final VectorStore vectorStore;

    public IndexService(KnowledgeRepository repository, HierarchicalChunker chunker,
                        EmbeddingService embeddings, DocMcpProperties properties,
                        VectorStore vectorStore) {
        this.repository = repository;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.properties = properties;
        this.vectorStore = vectorStore;
    }

    public IndexResult upsert(String docId, String markdown, Map<String, Object> metadata) {
        String safeId = safeDocId(docId);
        String title = String.valueOf(metadata.getOrDefault("title", firstTitle(markdown, safeId)));
        String sourceUri = string(metadata.get("source_uri"));
        String sha = string(metadata.get("content_sha256"));
        List<HierarchicalChunker.ChunkDraft> drafts = chunker.split(safeId, markdown);
        List<HierarchicalChunker.ChunkDraft> leaves = drafts.stream().filter(HierarchicalChunker.ChunkDraft::leaf).toList();
        List<float[]> vectors = embeddings.embedDocuments(leaves.stream().map(HierarchicalChunker.ChunkDraft::text).toList());
        Map<String, float[]> byId = new HashMap<>();
        for (int i = 0; i < leaves.size(); i++) byId.put(leaves.get(i).nodeId(), vectors.get(i));
        List<ChunkRecord> records = drafts.stream().map(draft -> new ChunkRecord(
                draft.nodeId(), safeId, draft.parentId(), draft.level(), draft.leaf(), draft.ordinal(),
                title, sourceUri, sha, draft.text(), metadata, byId.get(draft.nodeId()))).toList();
        List<ChunkRecord> previous = repository.loadDocumentChunks(safeId);
        vectorStore.replaceDocument(safeId, previous, records);
        List<ChunkRecord> persisted = vectorStore.external()
                ? records.stream().map(IndexService::withoutVector).toList() : records;
        repository.replaceDocument(new DocumentRecord(safeId, title, sourceUri, sha, metadata), persisted);
        return new IndexResult(safeId, records.size(), leaves.size());
    }

    public RetrievalResult retrieve(String question, int requestedTopK) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question不能为空");
        int topK = Math.max(1, Math.min(requestedTopK, 20));
        float[] queryVector = embeddings.embedQuery(question);
        Set<String> queryTerms = tokens(question);
        int vectorLimit = Math.max(topK * 10, properties.getRetrievalTopK());
        List<ScoredChunk> candidates = new ArrayList<>();
        for (VectorStore.VectorCandidate vectorCandidate : vectorStore.search(queryVector, vectorLimit)) {
            ChunkRecord chunk = vectorCandidate.chunk();
            double dense = vectorCandidate.denseScore();
            Set<String> terms = tokens(chunk.title() + " " + chunk.text());
            double keyword = queryTerms.isEmpty() ? 0 : (double) intersection(queryTerms, terms) / queryTerms.size();
            double score = properties.getHybridDenseWeight() * dense + properties.getHybridKeywordWeight() * keyword;
            if (score > 0) candidates.add(new ScoredChunk(chunk, score, dense, keyword));
        }
        candidates.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        int poolSize = Math.min(candidates.size(), Math.max(topK * 5, properties.getRetrievalTopK()));
        List<ScoredChunk> capped = capPerDocument(candidates.subList(0, poolSize));
        List<ScoredChunk> selected = mmr(capped, topK);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            ScoredChunk value = selected.get(i);
            ChunkRecord chunk = value.chunk();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", i + 1);
            source.put("chunkId", chunk.nodeId());
            source.put("parentId", chunk.parentId());
            source.put("level", chunk.level());
            source.put("documentId", chunk.docId());
            source.put("title", chunk.title());
            source.put("sourceUri", chunk.sourceUri());
            source.put("score", round(value.score()));
            source.put("denseScore", round(value.dense()));
            source.put("keywordScore", round(value.keyword()));
            source.put("text", chunk.text());
            source.put("metadata", chunk.metadata());
            sources.add(source);
        }
        return new RetrievalResult(question, "hybrid-" + vectorStore.name() + "-keyword-mmr", sources);
    }

    public List<Map<String, Object>> getChunkContext(String chunkId) {
        if (chunkId == null || !chunkId.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("chunk_id必须是64位十六进制Chunk ID");
        }
        List<ChunkRecord> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ChunkRecord current = repository.findChunk(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk不存在: " + chunkId));
        while (current != null && visited.add(current.nodeId()) && chain.size() < 10) {
            chain.add(current);
            String parentId = current.parentId();
            current = parentId == null || parentId.isBlank()
                    ? null : repository.findChunk(parentId).orElse(null);
        }
        java.util.Collections.reverse(chain);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChunkRecord chunk : chain) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chunkId", chunk.nodeId());
            value.put("parentId", chunk.parentId());
            value.put("documentId", chunk.docId());
            value.put("level", chunk.level());
            value.put("leaf", chunk.leaf());
            value.put("title", chunk.title());
            value.put("sourceUri", chunk.sourceUri());
            value.put("text", chunk.text());
            value.put("metadata", chunk.metadata());
            result.add(value);
        }
        return result;
    }

    public boolean deleteDocument(String docId) {
        String safeId = safeDocId(docId);
        if (repository.loadDocumentChunks(safeId).isEmpty()) return false;
        vectorStore.deleteDocument(safeId);
        return repository.deleteDocument(safeId);
    }

    public Map<String, Object> status() {
        return vectorStore.status();
    }

    private static ChunkRecord withoutVector(ChunkRecord chunk) {
        return new ChunkRecord(chunk.nodeId(), chunk.docId(), chunk.parentId(), chunk.level(),
                chunk.leaf(), chunk.ordinal(), chunk.title(), chunk.sourceUri(),
                chunk.contentSha256(), chunk.text(), chunk.metadata(), null);
    }

    private List<ScoredChunk> capPerDocument(List<ScoredChunk> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        List<ScoredChunk> result = new ArrayList<>();
        for (ScoredChunk candidate : candidates) {
            int count = counts.getOrDefault(candidate.chunk().docId(), 0);
            if (count >= properties.getMaxCandidatesPerDocument()) continue;
            counts.put(candidate.chunk().docId(), count + 1);
            result.add(candidate);
        }
        return result;
    }

    private List<ScoredChunk> mmr(List<ScoredChunk> candidates, int limit) {
        List<ScoredChunk> remaining = new ArrayList<>(candidates);
        List<ScoredChunk> selected = new ArrayList<>();
        double relevance = properties.getMmrRelevanceWeight();
        while (!remaining.isEmpty() && selected.size() < limit) {
            ScoredChunk best = null;
            double bestMmr = -Double.MAX_VALUE;
            for (ScoredChunk candidate : remaining) {
                double redundancy = selected.stream()
                        .mapToDouble(item -> jaccard(tokens(candidate.chunk().text()), tokens(item.chunk().text())))
                        .max().orElse(0);
                double value = relevance * candidate.score() - (1 - relevance) * redundancy;
                if (value > bestMmr) { bestMmr = value; best = candidate; }
            }
            selected.add(best);
            remaining.remove(best);
        }
        return selected;
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (matcher.find() && result.size() < 5000) result.add(matcher.group());
        return result;
    }
    private static int intersection(Set<String> left, Set<String> right) {
        Set<String> copy = new HashSet<>(left); copy.retainAll(right); return copy.size();
    }
    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left); intersection.retainAll(right);
        Set<String> union = new HashSet<>(left); union.addAll(right);
        return (double) intersection.size() / union.size();
    }
    private static String safeDocId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("doc_id不能为空");
        String safe = value.trim().replaceAll("[^0-9A-Za-z._\\-\\u4e00-\\u9fff]", "_");
        if (safe.length() > 300) safe = safe.substring(0, 300);
        return safe;
    }
    private static String firstTitle(String markdown, String fallback) {
        for (String line : markdown.split("\\R", 100)) if (line.startsWith("# ")) return line.substring(2).trim();
        return fallback;
    }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static double round(double value) { return Math.round(value * 1_000_000d) / 1_000_000d; }

    public record IndexResult(String docId, int nodeCount, int leafNodeCount) {}
    public record RetrievalResult(String question, String mode, List<Map<String, Object>> sources) {}
    private record ScoredChunk(ChunkRecord chunk, double score, double dense, double keyword) {}
}
