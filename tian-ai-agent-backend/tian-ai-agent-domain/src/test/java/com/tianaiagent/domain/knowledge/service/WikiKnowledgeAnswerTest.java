package com.tianaiagent.domain.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.knowledge.adapter.port.AnswerModel;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;
import com.tianaiagent.domain.knowledge.adapter.port.WikiStore;
import com.tianaiagent.domain.knowledge.adapter.repository.KnowledgeRepository;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.model.entity.WikiSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WikiKnowledgeAnswerTest {
    private static final String SPACE = "0123456789abcdef0123456789abcdef";
    private static final String QUESTION = "北辰项目交付日期";
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final IndexService index = mock(IndexService.class);
    private final WikiStore store = mock(WikiStore.class);
    private final AnswerModel model = mock(AnswerModel.class);
    private final WikiGraphService wiki = new WikiGraphService(repository, index, store, model, new ObjectMapper(),
            null, Runnable::run, 1);
    private final KnowledgeAnswerService answers = new KnowledgeAnswerService(index, model,
            new EvidenceAssessmentService(0.15, 0.35, 1), wiki);

    @BeforeEach
    void prepare() {
        when(index.space(SPACE)).thenReturn(new KnowledgeSpace(SPACE, "北辰", false));
        var page = new WikiSnapshot.Page("delivery", WikiSnapshot.Kind.CONCEPTS, "北辰项目交付日期",
                "北辰项目于 6 月 3 日交付。", List.of("doc-1"), List.of());
        when(store.load(SPACE)).thenReturn(Optional.of(new WikiSnapshot("version-1", "", Map.of("doc-1", "r1"),
                List.of(page), null)));
        sources("r1");
        when(index.retrieve(SPACE, QUESTION, 8)).thenReturn(new KnowledgeIndex.Retrieval(QUESTION, List.of(),
                "hybrid-sqlite-keyword-mmr", 8, 8, "java-mmr", List.of(QUESTION)));
    }

    @Test
    void citesWikiAndKeepsOriginalDocumentReference() {
        when(model.generate(anyString(), anyString())).thenReturn(Optional.of("{\"answer\":\"6 月 3 日 [1]\"}"));

        var result = answers.queryWithSources(SPACE, QUESTION, 8);

        assertThat(result.retrievalMode()).isEqualTo("wiki");
        assertThat(result.sources().getFirst().documentId()).isEqualTo("doc-1");
        assertThat(result.sources().getFirst().metadata()).containsEntry("wiki_page_id", "delivery");
        verify(index, never()).retrieve(anyString(), anyString(), anyInt());
    }

    @Test
    void fallsBackToRagWhenWikiCannotAnswerOrCitesAnUnknownPage() {
        when(model.generate(anyString(), anyString())).thenReturn(Optional.of("{\"answer\":\"\"}"),
                Optional.of("{\"answer\":\"6 月 3 日 [99]\"}"));

        assertThat(answers.queryWithSources(SPACE, QUESTION, 8).evidenceLevel()).isEqualTo("NO_RELEVANT_EVIDENCE");
        assertThat(answers.queryWithSources(SPACE, QUESTION, 8).retrievalMode()).contains("sqlite");
        verify(index, times(2)).retrieve(SPACE, QUESTION, 8);
    }

    @Test
    void fallsBackWhenOriginalDocumentHasChanged() {
        sources("r2");

        assertThat(answers.queryWithSources(SPACE, QUESTION, 8).retrievalMode()).contains("sqlite");
        verifyNoInteractions(model);
    }

    private void sources(String revision) {
        when(repository.wikiSources(eq(SPACE), anyInt())).thenReturn(List.of(
                new KnowledgeRepository.WikiSource("doc-1", "交付计划", revision, Map.of())));
    }
}
