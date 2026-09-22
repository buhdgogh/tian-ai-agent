package com.tianaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalDocumentLoaderTest {
    @Test
    void loadsSectionedDocumentsWithGovernanceMetadata() {
        MedicalDocumentLoader loader = new MedicalDocumentLoader(new PathMatchingResourcePatternResolver());
        var documents = loader.loadMarkdowns();
        assertThat(documents).isNotEmpty();
        assertThat(documents).allSatisfy(document -> assertThat(document.getMetadata()).containsKeys(
                "filename", "documentType", "issuer", "version", "publishedAt",
                "audience", "effectiveStatus", "reviewStatus"));
    }
}
