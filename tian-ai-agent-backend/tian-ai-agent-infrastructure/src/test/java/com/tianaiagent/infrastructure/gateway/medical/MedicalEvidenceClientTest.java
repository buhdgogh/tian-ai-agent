package com.tianaiagent.infrastructure.gateway.medical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalEvidenceClientTest {
    @Test
    void parsesPubMedXmlWithoutResolvingExternalEntities() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <PubmedArticleSet>
                  <PubmedArticle>
                    <MedlineCitation>
                      <PMID>12345678</PMID>
                      <Article>
                        <ArticleTitle>Evidence-based test article</ArticleTitle>
                        <Abstract><AbstractText Label="BACKGROUND">First section.</AbstractText><AbstractText>Second section.</AbstractText></Abstract>
                        <Journal><Title>Medical Journal</Title><JournalIssue><PubDate><Year>2026</Year><Month>Sep</Month></PubDate></JournalIssue></Journal>
                        <PublicationTypeList><PublicationType>Randomized Controlled Trial</PublicationType></PublicationTypeList>
                      </Article>
                    </MedlineCitation>
                    <PubmedData><ArticleIdList><ArticleId IdType="doi">10.1000/test</ArticleId><ArticleId IdType="pmc">PMC123</ArticleId></ArticleIdList></PubmedData>
                  </PubmedArticle>
                </PubmedArticleSet>
                """;

        List<Map<String, Object>> articles = MedicalEvidenceClient.parsePubMedXml(xml);

        assertEquals(1, articles.size());
        assertEquals("12345678", articles.getFirst().get("pmid"));
        assertEquals("10.1000/test", articles.getFirst().get("doi"));
        assertTrue(String.valueOf(articles.getFirst().get("abstract")).contains("BACKGROUND"));
    }
}
