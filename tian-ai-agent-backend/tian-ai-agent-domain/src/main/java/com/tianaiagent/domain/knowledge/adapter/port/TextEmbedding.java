package com.tianaiagent.domain.knowledge.adapter.port;

import java.util.List;

public interface TextEmbedding {
    List<float[]> embedDocuments(List<String> texts);

    float[] embedQuery(String text);
}
