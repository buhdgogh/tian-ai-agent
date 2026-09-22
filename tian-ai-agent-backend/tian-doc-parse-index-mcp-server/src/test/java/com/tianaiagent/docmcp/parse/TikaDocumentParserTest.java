package com.tianaiagent.docmcp.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TikaDocumentParserTest {
    @Test
    void parsesPlainTextIntoMarkdown() {
        TikaDocumentParser parser = new TikaDocumentParser();
        String markdown = parser.parse(new SourceLoader.LoadedSource(
                "地质文档正文".getBytes(StandardCharsets.UTF_8), "report.txt", "file:///report.txt"));
        assertTrue(markdown.startsWith("# report"));
        assertTrue(markdown.contains("地质文档正文"));
    }
}
