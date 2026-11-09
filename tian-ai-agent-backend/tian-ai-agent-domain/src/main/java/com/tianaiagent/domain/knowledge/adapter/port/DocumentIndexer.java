package com.tianaiagent.domain.knowledge.adapter.port;

import java.util.Map;

public interface DocumentIndexer {
    ParseIndexResult parseAndIndex(String spaceId, String docId, String fileSource, String contentSha256,
                                   Map<String, Object> documentMetadata);

    record ParseIndexResult(String taskId, int documentCount, int leafNodeCount, Map<String, Object> timings,
                            boolean degraded) {
        public String summary() {
            return "Java 文档 MCP 批量解析索引完成，文档数：" + documentCount + "，叶子节点数：" + leafNodeCount;
        }
    }
}
