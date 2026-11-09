package com.tianaiagent.domain.knowledge.adapter.port;

import com.tianaiagent.domain.knowledge.adapter.port.DocumentSource.LoadedSource;

public interface TextDocumentParser {
    String parse(LoadedSource source);
}
