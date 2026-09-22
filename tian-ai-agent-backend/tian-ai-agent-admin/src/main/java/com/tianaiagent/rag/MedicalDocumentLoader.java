package com.tianaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 按 Markdown 标题/章节加载医疗资料，并附加证据治理所需元数据。 */
@Component
@Slf4j
public class MedicalDocumentLoader {
    private final ResourcePatternResolver resolver;

    public MedicalDocumentLoader(ResourcePatternResolver resolver) { this.resolver = resolver; }

    public List<Document> loadMarkdowns() {
        List<Document> documents = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath:document/*.md")) {
                String filename = resource.getFilename();
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(true)
                        .withAdditionalMetadata("filename", filename)
                        .withAdditionalMetadata("documentType", documentType(filename))
                        .withAdditionalMetadata("issuer", "项目内置演示资料")
                        .withAdditionalMetadata("version", "1.0")
                        .withAdditionalMetadata("publishedAt", LocalDate.now().toString())
                        .withAdditionalMetadata("audience", "GENERAL_EDUCATION")
                        .withAdditionalMetadata("effectiveStatus", "DEMO_ONLY")
                        .withAdditionalMetadata("reviewStatus", "NOT_FOR_CLINICAL_USE")
                        .build();
                documents.addAll(new MarkdownDocumentReader(resource, config).get());
            }
        } catch (IOException e) {
            log.error("医疗知识文档加载失败", e);
        }
        return documents;
    }

    private String documentType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.contains("药") || name.contains("用药")) return "MEDICATION_SAFETY";
        if (name.contains("急症") || name.contains("红旗")) return "TRIAGE_SAFETY";
        if (name.contains("指南")) return "GUIDELINE";
        return "MEDICAL_EDUCATION";
    }
}
