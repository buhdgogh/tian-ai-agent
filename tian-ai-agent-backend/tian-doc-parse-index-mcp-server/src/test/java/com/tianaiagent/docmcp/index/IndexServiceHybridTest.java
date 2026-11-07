package com.tianaiagent.docmcp.index;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import com.tianaiagent.docmcp.storage.KnowledgeRepository.ChunkRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IndexServiceHybridTest {
    private final VectorStore store = mock(VectorStore.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private IndexService index;
    @BeforeEach void setup() {
        when(store.supportsKeywordSearch()).thenReturn(true);
        when(embeddings.embedQuery(anyString())).thenReturn(new float[64]);
        index = new IndexService(mock(KnowledgeRepository.class), mock(HierarchicalChunker.class), embeddings, new DocMcpProperties(), store);
    }

    static ChunkRecord chunk(String id, String text) {
        return new ChunkRecord(id, "doc-" + id, "", 2, true, 0, "医学资料", "", "", text, Map.of(), null);
    }

    @Test void fusionRetainsAnIndependentBm25HitThatDenseSearchMissed() {
        var common = chunk("common", "共同证据");
        var keywordOnly = chunk("lexical", "HbA1c 指标资料");
        when(store.search(any(), anyInt())).thenReturn(List.of(new VectorStore.VectorCandidate(common, 0.8)));
        when(store.searchKeywords(anyString(), anyInt())).thenReturn(List.of(new VectorStore.KeywordCandidate(keywordOnly, 8), new VectorStore.KeywordCandidate(common, 2)));
        var result = index.retrieve("HbA1c", 3);
        assertEquals("hybrid-milvus-bm25-rrf-mmr", result.mode());
        assertEquals(2, result.sources().size());
        assertTrue(result.sources().stream().anyMatch(source -> source.get("chunkId").equals("lexical")));
        assertEquals("common", result.sources().getFirst().get("chunkId"));
        assertEquals(8.0, result.sources().stream().filter(source -> source.get("chunkId").equals("lexical")).findFirst().orElseThrow().get("bm25Score"));
    }

    @Test void keywordModeDoesNotCallEmbeddingOrDenseSearch() {
        when(store.searchKeywords(anyString(), anyInt())).thenReturn(List.of(new VectorStore.KeywordCandidate(chunk("keyword", "检索原文"), 2)));
        var result = index.retrieve("原文", 2, "keyword");
        verify(embeddings, never()).embedQuery(anyString());
        verify(store, never()).search(any(), anyInt());
        assertEquals("milvus-bm25-mmr", result.mode());
        assertTrue(((Number) result.sources().getFirst().get("score")).doubleValue() > 0.35);
    }

    @Test void nativeBm25ErrorsAreNotSilentlyReplacedWithCoverageScoring() {
        when(store.search(any(), anyInt())).thenReturn(List.of());
        when(store.searchKeywords(anyString(), anyInt())).thenThrow(new IllegalStateException("BM25 unavailable"));
        assertThrows(IllegalStateException.class, () -> index.retrieve("证据", 2));
    }

    @Test void duplicatedAndZeroScoreCandidatesDoNotCreateExtraEvidence() {
        var hit = chunk("same", "一份证据");
        when(store.search(any(), anyInt())).thenReturn(List.of(new VectorStore.VectorCandidate(hit, 0), new VectorStore.VectorCandidate(hit, 0)));
        when(store.searchKeywords(anyString(), anyInt())).thenReturn(List.of(new VectorStore.KeywordCandidate(hit, 0)));
        assertTrue(index.retrieve("不存在的词", 3).sources().isEmpty());
        when(store.searchKeywords(anyString(), anyInt())).thenReturn(List.of(new VectorStore.KeywordCandidate(hit, 2), new VectorStore.KeywordCandidate(hit, 2)));
        assertEquals(1, index.retrieve("证据", 3).sources().size());
    }

    @Test void localModeCannotPretendToProvideNativeBm25() {
        when(store.supportsKeywordSearch()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> index.retrieve("资料", 1, "keyword"));
        assertThrows(IllegalArgumentException.class, () -> index.retrieve("资料", 1, "unknown"));
    }
}
