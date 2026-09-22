package com.tianaiagent.docmcp.parse;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class TikaDocumentParser {
    public String parse(SourceLoader.LoadedSource source) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, source.fileName());
            parser.parse(new ByteArrayInputStream(source.bytes()), handler, metadata, new ParseContext());
            String text = handler.toString().replace("\r\n", "\n").trim();
            if (text.isBlank()) throw new IllegalStateException("Tika未提取到文本");
            String title = metadata.get(TikaCoreProperties.TITLE);
            if (title == null || title.isBlank()) title = stripExtension(source.fileName());
            return "# " + title.trim() + "\n\n" + text;
        } catch (Exception e) {
            throw new IllegalStateException("Tika文档解析失败: " + e.getMessage(), e);
        }
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
}
