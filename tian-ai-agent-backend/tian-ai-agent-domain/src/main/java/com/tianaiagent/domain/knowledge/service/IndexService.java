package com.tianaiagent.domain.knowledge.service;

import com.tianaiagent.domain.knowledge.service.HierarchicalChunker;
import com.tianaiagent.domain.knowledge.adapter.port.VectorStore;
import com.tianaiagent.domain.knowledge.model.valobj.MedicalDocumentMetadata;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex.Evidence;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex.Retrieval;

import com.tianaiagent.types.config.DocMcpProperties;
import com.tianaiagent.domain.knowledge.adapter.repository.KnowledgeRepository;
import com.tianaiagent.domain.knowledge.model.entity.ChunkRecord;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.adapter.repository.KnowledgeRepository.DocumentRecord;

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

public class IndexService {
    private static final Pattern WORD = Pattern.compile("[a-zA-Z0-9_.+-]+|[\\p{IsHan}]");
    private final KnowledgeRepository repository;
    private final HierarchicalChunker chunker;
    private final SpaceEmbeddings embeddings;
    private final DocMcpProperties properties;
    private final VectorStore vectorStore;
    private final List<Object> documentLocks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(ignored -> new Object()).toList();
    private final List<Object> spaceLocks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(ignored -> new Object()).toList();

    public IndexService(KnowledgeRepository repository, HierarchicalChunker chunker,
                        SpaceEmbeddings embeddings, DocMcpProperties properties,
                        VectorStore vectorStore) {
        this.repository = repository;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.properties = properties;
        this.vectorStore = vectorStore;
    }

    public KnowledgeSpace createSpace(String name, boolean medical, String ownerId, String embeddingProviderId) {
        KnowledgeSpace space = KnowledgeSpace.create(name, medical, embeddings.bind(ownerId, embeddingProviderId));
        repository.addSpace(space);
        return space;
    }

    public List<KnowledgeSpace> listSpaces() { return repository.listSpaces(); }

    public KnowledgeSpace space(String spaceId) {
        KnowledgeSpace space = repository.findSpace(KnowledgeSpace.requireId(spaceId))
                .orElseThrow(() -> new IllegalArgumentException("知识库空间不存在"));
        if (space.deleting()) throw new IllegalStateException("知识库正在删除");
        return space;
    }

    public void deleteSpace(String spaceId, Runnable cleanup) {
        KnowledgeSpace.requireId(spaceId);
        if (KnowledgeSpace.MEDICAL_ID.equals(spaceId)) throw new IllegalArgumentException("内置医疗知识库不能删除");
        synchronized (spaceLock(spaceId)) {
            KnowledgeSpace space = repository.findSpace(spaceId).orElse(null);
            if (space == null) return;
            repository.markSpaceDeleting(spaceId);
            vectorStore.deleteSpace(space);
            cleanup.run();
            repository.deleteSpace(spaceId);
        }
    }

    public void writeInSpace(String spaceId, Runnable write) {
        synchronized (spaceLock(spaceId)) {
            space(spaceId);
            write.run();
        }
    }

    public IndexResult upsert(String spaceId, String docId, String markdown, Map<String, Object> metadata) {
        KnowledgeSpace space = space(spaceId);
        String safeId = safeDocId(docId);
        if (space.medical()) MedicalDocumentMetadata.approvedFrom(metadata);
        requireWritable(spaceId, safeId);
        String title = String.valueOf(metadata.getOrDefault("title", firstTitle(markdown, safeId)));
        String sourceUri = string(metadata.get("source_uri"));
        String sha = string(metadata.get("content_sha256"));
        List<HierarchicalChunker.ChunkDraft> drafts = chunker.split(safeId, markdown);
        List<HierarchicalChunker.ChunkDraft> leaves = drafts.stream().filter(HierarchicalChunker.ChunkDraft::leaf).toList();
        List<float[]> vectors = embeddings.embedDocuments(space, leaves.stream().map(HierarchicalChunker.ChunkDraft::text).toList());
        Map<String, float[]> byId = new HashMap<>();
        for (int i = 0; i < leaves.size(); i++) byId.put(leaves.get(i).nodeId(), vectors.get(i));
        List<ChunkRecord> records = drafts.stream().map(draft -> new ChunkRecord(
                draft.nodeId(), safeId, draft.parentId(), draft.level(), draft.leaf(), draft.ordinal(),
                title, sourceUri, sha, draft.text(), metadata, byId.get(draft.nodeId()))).toList();
        writeInSpace(spaceId, () -> {
            synchronized (documentLock(safeId)) {
                requireWritable(spaceId, safeId);
                List<ChunkRecord> previous = repository.loadDocumentChunks(spaceId, safeId);
                vectorStore.replaceDocument(space, previous, records);
                List<ChunkRecord> persisted = vectorStore.external()
                        ? records.stream().map(IndexService::withoutVector).toList() : records;
                repository.replaceDocument(spaceId, new DocumentRecord(safeId, title, sourceUri, sha, metadata), persisted);
            }
        });
        return new IndexResult(safeId, records.size(), leaves.size());
    }

    public Retrieval retrieve(String spaceId, String question, int requestedTopK) {
        return retrieve(spaceId, question, requestedTopK, RetrievalMode.HYBRID_MMR);
    }

    Retrieval retrieve(String spaceId, String question, int requestedTopK, RetrievalMode mode) {
        KnowledgeSpace space = space(spaceId);
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question不能为空");
        int topK = Math.max(1, Math.min(requestedTopK, 20));
        float[] queryVector = embeddings.embedQuery(space, question);
        Set<String> queryTerms = tokens(question);
        int vectorLimit = Math.max(topK * 10, properties.getRetrievalTopK());
        List<ScoredChunk> candidates = new ArrayList<>();
        Map<String, Boolean> deleted = new HashMap<>();
        for (VectorStore.VectorCandidate vectorCandidate : vectorStore.search(space, queryVector, vectorLimit)) {
            ChunkRecord chunk = vectorCandidate.chunk();
            if (deleted.computeIfAbsent(chunk.docId(), id -> repository.isDeleted(spaceId, id))
                    || !space.allowsDocument(chunk.metadata())) continue;
            if (vectorStore.external() && repository.findChunk(spaceId, chunk.nodeId())
                    .filter(persisted -> space.allowsDocument(persisted.metadata())).isEmpty()) continue;
            double dense = vectorCandidate.denseScore();
            Set<String> terms = tokens(chunk.title() + " " + chunk.text());
            double keyword = queryTerms.isEmpty() ? 0 : (double) intersection(queryTerms, terms) / queryTerms.size();
            double score = mode == RetrievalMode.DENSE ? dense
                    : properties.getHybridDenseWeight() * dense + properties.getHybridKeywordWeight() * keyword;
            if (score > 0) candidates.add(new ScoredChunk(chunk, score, dense, keyword));
        }
        candidates.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        int poolSize = Math.min(candidates.size(), Math.max(topK * 5, properties.getRetrievalTopK()));
        List<ScoredChunk> capped = capPerDocument(candidates.subList(0, poolSize));
        List<ScoredChunk> selected = mode == RetrievalMode.HYBRID_MMR
                ? mmr(capped, topK) : candidates.stream().limit(topK).toList();
        List<Evidence> sources = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            ScoredChunk value = selected.get(i);
            ChunkRecord chunk = value.chunk();
            sources.add(new Evidence(i + 1, chunk.nodeId(), chunk.parentId(), chunk.level(), chunk.docId(),
                    chunk.title(), chunk.sourceUri(), round(value.score()), round(value.dense()),
                    round(value.keyword()), chunk.text(), chunk.metadata()));
        }
        return new Retrieval(question, List.copyOf(sources), "hybrid-" + vectorStore.name() + "-keyword-mmr",
                topK, topK, "java-mmr", List.of(question));
    }

    enum RetrievalMode { DENSE, HYBRID, HYBRID_MMR }

    public List<Map<String, Object>> getChunkContext(String spaceId, String chunkId) {
        KnowledgeSpace space = space(spaceId);
        if (chunkId == null || !chunkId.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("chunk_id必须是64位十六进制Chunk ID");
        }
        List<ChunkRecord> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ChunkRecord current = repository.findChunk(spaceId, chunkId)
                .filter(chunk -> !repository.isDeleted(spaceId, chunk.docId()) && space.allowsDocument(chunk.metadata()))
                .orElseThrow(() -> new IllegalArgumentException("Chunk不存在: " + chunkId));
        while (current != null && visited.add(current.nodeId()) && chain.size() < 10) {
            chain.add(current);
            String parentId = current.parentId();
            current = parentId == null || parentId.isBlank()
                    ? null : repository.findChunk(spaceId, parentId).orElse(null);
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

    public boolean deleteDocument(String spaceId, String docId) {
        String safeId = safeDocId(docId);
        synchronized (spaceLock(spaceId)) {
            KnowledgeSpace space = space(spaceId);
            synchronized (documentLock(safeId)) {
                if (belongsToAnotherSpace(spaceId, safeId)) return false;
                repository.markDeleted(spaceId, safeId);
                vectorStore.deleteDocument(space, safeId);
                return repository.deleteDocument(spaceId, safeId);
            }
        }
    }

    private Object spaceLock(String id) { return spaceLocks.get(Math.floorMod(KnowledgeSpace.requireId(id).hashCode(), spaceLocks.size())); }
    private Object documentLock(String id) { return documentLocks.get(Math.floorMod(id.hashCode(), documentLocks.size())); }

    private boolean belongsToAnotherSpace(String spaceId, String docId) {
        return repository.findDocumentSpace(docId).filter(id -> !id.equals(spaceId)).isPresent();
    }

    private void requireWritable(String spaceId, String id) {
        if (belongsToAnotherSpace(spaceId, id) || repository.isDeleted(spaceId, id)) {
            throw new IllegalStateException("该文档 ID 无法在当前空间发布");
        }
    }

    public Map<String, Object> status(String spaceId) {
        KnowledgeSpace space = space(spaceId);
        Map<String, Object> result = new LinkedHashMap<>(repository.status(spaceId));
        Map<String, Object> vectorStatus = vectorStore.status(space);
        result.put("space_id", spaceId);
        result.put("vector_store", vectorStatus);
        if (vectorStatus.containsKey("vectors")) result.put("vectors", vectorStatus.get("vectors"));
        result.put("retrieval", Map.of("mode", "hybrid-vector-keyword-mmr"));
        return result;
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
    private record ScoredChunk(ChunkRecord chunk, double score, double dense, double keyword) {}
}
