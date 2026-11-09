package com.tianaiagent.domain.knowledge.service;

import com.tianaiagent.types.config.DocMcpProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchicalChunkerTest {
    @Test
    void createsParentsAndLeaves() {
        DocMcpProperties properties = new DocMcpProperties();
        properties.setChunkSizes(List.of(20, 8));
        properties.setChunkOverlap(2);
        HierarchicalChunker chunker = new HierarchicalChunker(properties.getChunkSizes(), properties.getChunkOverlap());

        var chunks = chunker.split("doc-1", "第一段地质资料。第二段地质资料。第三段地质资料。第四段地质资料。");

        assertTrue(chunks.stream().anyMatch(chunk -> !chunk.leaf()));
        assertTrue(chunks.stream().anyMatch(HierarchicalChunker.ChunkDraft::leaf));
        assertFalse(chunks.stream().filter(HierarchicalChunker.ChunkDraft::leaf)
                .anyMatch(chunk -> chunk.parentId() == null));
    }
}
