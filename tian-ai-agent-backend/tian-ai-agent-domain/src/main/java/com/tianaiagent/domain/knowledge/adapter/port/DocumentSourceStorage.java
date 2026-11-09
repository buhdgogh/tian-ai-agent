package com.tianaiagent.domain.knowledge.adapter.port;

import java.nio.file.Path;

public interface DocumentSourceStorage {
    String prepareSource(String objectName, Path localPath);

    boolean usesOss();

    String storageMode();

    void delete(String objectName);
}
