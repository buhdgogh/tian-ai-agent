package com.tianaiagent.infrastructure.gateway.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void preservesPdfPageBoundaries() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String label : new String[]{"First page body", "Second page body", "Third page body"}) {
                var page = new PDPage();
                pdf.addPage(page);
                try (var output = new PDPageContentStream(pdf, page)) {
                    output.beginText();
                    output.setFont(font, 12);
                    output.newLineAtOffset(50, 700);
                    output.showText(label);
                    output.endText();
                }
            }
            pdf.save(bytes);
        }
        String markdown = new TikaDocumentParser().parse(new SourceLoader.LoadedSource(
                bytes.toByteArray(), "manual.pdf", "file:///manual.pdf"));
        String[] pages = markdown.split("\\f");
        assertEquals(3, pages.length);
        assertTrue(pages[0].contains("First page body"));
        assertTrue(pages[1].contains("Second page body"));
        assertTrue(pages[2].contains("Third page body"));
    }
}
