package com.tianaiagent.docmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("doc-mcp")
public class DocMcpProperties {
    private String endpoint = "/mcp";
    private String storageDir = "./data";
    private String parsedDir = "./parsed";
    private String runtimeDir = "./runtime";
    private int parseWorkers = 2;
    private boolean allowLocalFiles = true;
    private long maxSourceBytes = 200L * 1024 * 1024;
    private List<Integer> chunkSizes = new ArrayList<>(List.of(4096, 1024, 256));
    private int chunkOverlap = 24;
    private int retrievalTopK = 30;
    private int rerankTopN = 8;
    private double hybridDenseWeight = 0.65;
    private double hybridKeywordWeight = 0.35;
    private double mmrRelevanceWeight = 0.78;
    private int maxCandidatesPerDocument = 4;
    private String vectorStore = "local";
    private Milvus milvus = new Milvus();
    private Mineru mineru = new Mineru();
    private Embedding embedding = new Embedding();
    private Chat chat = new Chat();
    private Evidence evidence = new Evidence();

    public static class Evidence {
        private double minimumRelevantScore = 0.15;
        private double minimumSufficientScore = 0.35;
        private int minimumSources = 1;
        public double getMinimumRelevantScore() { return minimumRelevantScore; }
        public void setMinimumRelevantScore(double value) { this.minimumRelevantScore = value; }
        public double getMinimumSufficientScore() { return minimumSufficientScore; }
        public void setMinimumSufficientScore(double value) { this.minimumSufficientScore = value; }
        public int getMinimumSources() { return minimumSources; }
        public void setMinimumSources(int value) { this.minimumSources = value; }
    }

    public static class Milvus {
        private String uri = "http://127.0.0.1:19530";
        private String token = "root:Milvus";
        private String databaseName = "default";
        private String collectionName = "knowledge_chunks";
        private String memoryCollectionName = "agent_memories";
        private int batchSize = 500;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getDatabaseName() { return databaseName; }
        public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
        public String getCollectionName() { return collectionName; }
        public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
        public String getMemoryCollectionName() { return memoryCollectionName; }
        public void setMemoryCollectionName(String value) { this.memoryCollectionName = value; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class Mineru {
        private boolean enabled = true;
        private String baseUrl = "https://mineru.net";
        private String token = "";
        private Duration pollInterval = Duration.ofSeconds(2);
        private Duration timeout = Duration.ofMinutes(30);
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Embedding {
        private String provider = "local";
        private String model = "text-embedding-v3";
        private int dimensions = 1024;
        private int batchSize = 10;
        private String apiKey = "";
        private String endpoint;
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }

    public static class Chat {
        private String model = "qwen-plus";
        private String apiKey = "";
        private String endpoint;
        private int maxOutputTokens = 2048;
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public String getParsedDir() { return parsedDir; }
    public void setParsedDir(String parsedDir) { this.parsedDir = parsedDir; }
    public String getRuntimeDir() { return runtimeDir; }
    public void setRuntimeDir(String runtimeDir) { this.runtimeDir = runtimeDir; }
    public int getParseWorkers() { return parseWorkers; }
    public void setParseWorkers(int parseWorkers) { this.parseWorkers = parseWorkers; }
    public boolean isAllowLocalFiles() { return allowLocalFiles; }
    public void setAllowLocalFiles(boolean allowLocalFiles) { this.allowLocalFiles = allowLocalFiles; }
    public long getMaxSourceBytes() { return maxSourceBytes; }
    public void setMaxSourceBytes(long maxSourceBytes) { this.maxSourceBytes = maxSourceBytes; }
    public List<Integer> getChunkSizes() { return chunkSizes; }
    public void setChunkSizes(List<Integer> chunkSizes) { this.chunkSizes = chunkSizes; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public int getRetrievalTopK() { return retrievalTopK; }
    public void setRetrievalTopK(int retrievalTopK) { this.retrievalTopK = retrievalTopK; }
    public int getRerankTopN() { return rerankTopN; }
    public void setRerankTopN(int rerankTopN) { this.rerankTopN = rerankTopN; }
    public double getHybridDenseWeight() { return hybridDenseWeight; }
    public void setHybridDenseWeight(double hybridDenseWeight) { this.hybridDenseWeight = hybridDenseWeight; }
    public double getHybridKeywordWeight() { return hybridKeywordWeight; }
    public void setHybridKeywordWeight(double hybridKeywordWeight) { this.hybridKeywordWeight = hybridKeywordWeight; }
    public double getMmrRelevanceWeight() { return mmrRelevanceWeight; }
    public void setMmrRelevanceWeight(double mmrRelevanceWeight) { this.mmrRelevanceWeight = mmrRelevanceWeight; }
    public int getMaxCandidatesPerDocument() { return maxCandidatesPerDocument; }
    public void setMaxCandidatesPerDocument(int maxCandidatesPerDocument) { this.maxCandidatesPerDocument = maxCandidatesPerDocument; }
    public String getVectorStore() { return vectorStore; }
    public void setVectorStore(String vectorStore) { this.vectorStore = vectorStore; }
    public Milvus getMilvus() { return milvus; }
    public void setMilvus(Milvus milvus) { this.milvus = milvus; }
    public Mineru getMineru() { return mineru; }
    public void setMineru(Mineru mineru) { this.mineru = mineru; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Chat getChat() { return chat; }
    public void setChat(Chat chat) { this.chat = chat; }
    public Evidence getEvidence() { return evidence; }
    public void setEvidence(Evidence evidence) { this.evidence = evidence; }
}
