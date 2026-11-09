package com.tianaiagent.infrastructure.gateway.document;

import com.tianaiagent.domain.knowledge.adapter.port.DocumentSource.LoadedSource;

import com.tianaiagent.domain.knowledge.adapter.port.TextDocumentParser;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;

public class TikaDocumentParser implements TextDocumentParser {
    public String parse(LoadedSource source) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            var handler = new PagedBodyContentHandler();
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

    /** Tika 的 PDF XHTML 为每页发出 div.page，保留边界供清洗使用。 */
    private static final class PagedBodyContentHandler extends BodyContentHandler {
        private static final char[] PAGE_BREAK = {'\n', '\f', '\n'};
        private int pageCount;

        private PagedBodyContentHandler() { super(-1); }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            super.startElement(uri, localName, qName, attributes);
            if ("div".equals(localName) && "page".equals(attributes.getValue("class")) && pageCount++ > 0) {
                characters(PAGE_BREAK, 0, PAGE_BREAK.length);
            }
        }
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
}
