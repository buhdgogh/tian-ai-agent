package com.tianaiagent.domain.knowledge.adapter.port;

import com.tianaiagent.domain.knowledge.adapter.port.DocumentSource.LoadedSource;

public interface MultimodalDocumentParser {
    MultimodalDocumentParser withToken(String token);

    boolean isAvailable();

    boolean canParseRemote(String source);

    String parseRemote(String source, ParseOptions options);

    String parseUpload(LoadedSource source, ParseOptions options);

    record ParseOptions(String dataId, String model, boolean ocr, boolean formula, boolean table, String language) {}
}
