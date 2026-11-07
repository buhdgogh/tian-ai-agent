package com.tianaiagent.docmcp.index;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.UpsertReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MilvusKeywordIndexTest {
    @Test void schemaUsesMilvusBm25FunctionAndSparseInvertedIndex() {
        var index = new MilvusKeywordIndex(mock(MilvusClientV2.class), new DocMcpProperties(), mock(KnowledgeRepository.class));
        var request = index.createRequest();
        var schema = request.getCollectionSchema();
        assertEquals(true, schema.getField(MilvusKeywordIndex.TEXT).getEnableAnalyzer());
        assertEquals("jieba", schema.getField(MilvusKeywordIndex.TEXT).getAnalyzerParams().get("tokenizer"));
        assertEquals(DataType.SparseFloatVector, schema.getField(MilvusKeywordIndex.SPARSE).getDataType());
        assertEquals(FunctionType.BM25, schema.getFunctionList().getFirst().getFunctionType());
        assertEquals(IndexParam.IndexType.SPARSE_INVERTED_INDEX, request.getIndexParams().getFirst().getIndexType());
        assertEquals(IndexParam.MetricType.BM25, request.getIndexParams().getFirst().getMetricType());
    }

    @Test void backfillsExistingTextInPagesWithoutComputingEmbeddings() {
        var client = mock(MilvusClientV2.class);
        var repository = mock(KnowledgeRepository.class);
        var properties = new DocMcpProperties(); properties.getMilvus().setBatchSize(1);
        var index = new MilvusKeywordIndex(client, properties, repository);
        when(client.describeCollection(any())).thenReturn(DescribeCollectionResp.builder().collectionSchema(index.createRequest().getCollectionSchema()).build());
        when(repository.loadLeafChunksAfter("", 1)).thenReturn(List.of(IndexServiceHybridTest.chunk("aaa", "阿司匹林记录")));
        when(repository.loadLeafChunksAfter("aaa", 1)).thenReturn(List.of(IndexServiceHybridTest.chunk("bbb", "HbA1c记录")));
        when(repository.loadLeafChunksAfter("bbb", 1)).thenReturn(List.of());
        index.initialize();
        var writes = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client, times(2)).upsert(writes.capture());
        assertTrue(writes.getAllValues().getFirst().getData().getFirst().get("search_text").getAsString().contains("阿司匹林"));
        assertFalse(writes.getValue().getData().getFirst().has("bm25_sparse"));
        assertFalse(writes.getValue().getData().getFirst().has("embedding"));
    }

    @Test void rejectsOverwritingAnExistingDenseCollectionAsKeywordIndex() {
        var properties = new DocMcpProperties();
        properties.getMilvus().setKeywordCollectionName(properties.getMilvus().getCollectionName());
        var client = mock(MilvusClientV2.class);
        var index = new MilvusKeywordIndex(client, properties, mock(KnowledgeRepository.class));
        assertThrows(IllegalArgumentException.class, index::initialize);
        verifyNoInteractions(client);
    }
}
