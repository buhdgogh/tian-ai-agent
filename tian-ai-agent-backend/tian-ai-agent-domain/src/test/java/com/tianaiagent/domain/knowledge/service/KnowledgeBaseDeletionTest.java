package com.tianaiagent.domain.knowledge.service;

import com.tianaiagent.domain.task.service.AsyncTaskCenter;
import com.tianaiagent.domain.knowledge.adapter.port.DocumentIndexer;
import com.tianaiagent.domain.knowledge.adapter.port.DocumentSourceStorage;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeAnswerCache;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;
import com.tianaiagent.domain.knowledge.model.valobj.MedicalDocumentMetadata;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import com.tianaiagent.domain.task.model.valobj.TaskType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KnowledgeBaseDeletionTest {
    @Test
    void retriesOriginalCleanupAfterIndexWasAlreadyDeleted() {
        var index = mock(KnowledgeIndex.class);
        when(index.space("medical")).thenReturn(com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace.MEDICAL);
        var storage = mock(DocumentSourceStorage.class);
        var tasks = mock(AsyncTaskCenter.class);
        when(storage.prepareSource(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(1).toString());
        when(tasks.submit(eq(TaskType.DOCUMENT_INDEX), eq("admin"), anyMap(), any()))
                .thenReturn(new TaskRecord("test-task", TaskType.DOCUMENT_INDEX, "admin", Map.of()));
        when(tasks.get("test-task")).thenThrow(new IllegalArgumentException("not running"));
        var service = new KnowledgeBaseService(java.util.Optional.of(mock(DocumentIndexer.class)),
                java.util.Optional.of(index), java.util.Optional.empty(), storage, tasks, mock(KnowledgeAnswerCache.class));
        var metadata = new MedicalDocumentMetadata("issuer", "v1", "2026-01-01", "all", "ACTIVE", "APPROVED", "guide");
        String id = (String) service.saveFile("medical", "guide.txt", 1, new ByteArrayInputStream(new byte[]{1}),
                metadata, "admin").get("id");
        when(index.delete("medical", id)).thenReturn(new KnowledgeIndex.DeleteResult(id, false), new KnowledgeIndex.DeleteResult(id, true));
        doThrow(new IllegalStateException("storage unavailable")).doNothing().when(storage).delete("knowledge-base/" + id + ".txt");

        try {
            assertThatThrownBy(() -> service.deleteFile("medical", id)).isInstanceOf(IllegalStateException.class);
            assertThat(service.listFiles("medical")).anyMatch(file -> id.equals(file.get("id")));
            service.deleteFile("medical", id);
            assertThat(service.listFiles("medical")).noneMatch(file -> id.equals(file.get("id")));
            service.deleteFile("medical", id);
            verify(index, times(2)).delete("medical", id);
        } finally {
            service.deleteFile("medical", id);
        }
    }
}
