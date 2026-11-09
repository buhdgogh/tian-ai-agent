package com.tianaiagent.domain.knowledge.adapter.port;

public interface DocumentSource {
    LoadedSource load(String source);

    record LoadedSource(byte[] bytes, String fileName, String publicUri) {}
}
