package com.tianaiagent.docmcp.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParseTask {
    private String taskId;
    private String status;
    private String createdAt;
    private String updatedAt;
    private String outputDir;
    private boolean autoIndex;
    private Map<String, Object> options = new LinkedHashMap<>();
    private List<ParseItem> items = new ArrayList<>();
    private Map<String, Object> timings = new LinkedHashMap<>();
    private String error;

    public static ParseTask queued(String taskId) {
        ParseTask task = new ParseTask();
        task.taskId = taskId;
        task.status = "queued";
        task.createdAt = Instant.now().toString();
        task.updatedAt = task.createdAt;
        return task;
    }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public boolean isAutoIndex() { return autoIndex; }
    public void setAutoIndex(boolean autoIndex) { this.autoIndex = autoIndex; }
    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }
    public List<ParseItem> getItems() { return items; }
    public void setItems(List<ParseItem> items) { this.items = items; }
    public Map<String, Object> getTimings() { return timings; }
    public void setTimings(Map<String, Object> timings) { this.timings = timings; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public static class ParseItem {
        private String itemId;
        private String source;
        private String sourceUri;
        private String docId;
        private String contentSha256;
        private String cacheKey;
        private Map<String, Object> documentMetadata = new LinkedHashMap<>();
        private String status = "queued";
        private boolean cacheHit;
        private boolean indexed;
        private String parser;
        private String outputPath;
        private Integer leafNodeCount;
        private Map<String, Object> timings = new LinkedHashMap<>();
        private String error;
        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getSourceUri() { return sourceUri; }
        public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        public String getContentSha256() { return contentSha256; }
        public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
        public String getCacheKey() { return cacheKey; }
        public void setCacheKey(String cacheKey) { this.cacheKey = cacheKey; }
        public Map<String, Object> getDocumentMetadata() { return documentMetadata; }
        public void setDocumentMetadata(Map<String, Object> documentMetadata) {
            this.documentMetadata = documentMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(documentMetadata);
        }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public boolean isCacheHit() { return cacheHit; }
        public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }
        public boolean isIndexed() { return indexed; }
        public void setIndexed(boolean indexed) { this.indexed = indexed; }
        public String getParser() { return parser; }
        public void setParser(String parser) { this.parser = parser; }
        public String getOutputPath() { return outputPath; }
        public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
        public Integer getLeafNodeCount() { return leafNodeCount; }
        public void setLeafNodeCount(Integer leafNodeCount) { this.leafNodeCount = leafNodeCount; }
        public Map<String, Object> getTimings() { return timings; }
        public void setTimings(Map<String, Object> timings) { this.timings = timings; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
