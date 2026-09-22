package com.tianaiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.constant.FileConstant;
import com.tianaiagent.mcp.LlamaIndexRagClient;
import com.tianaiagent.mcp.MineruParseClient;
import com.tianaiagent.rag.SemanticRagCache;
import com.tianaiagent.storage.OssStorageClient;
import com.tianaiagent.task.AsyncTaskCenter;
import com.tianaiagent.task.TaskRecord;
import com.tianaiagent.task.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class KnowledgeBaseService {

    private static final String KB_DIR = FileConstant.FILE_SAVE_DIR + "/knowledge-base";
    private static final String OSS_OBJECT_PREFIX = "knowledge-base/";
    private static final Path META_FILE = Paths.get(KB_DIR, "metadata.json");
    private static final Path INDEX_META_FILE = Paths.get(KB_DIR, "index-meta.json");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".txt", ".md", ".csv", ".xlsx", ".pptx"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> nameMap;                  // id → originalName
    private final Map<String, Map<String, Object>> indexMetaMap; // id → {status, summary, error}

    private final MineruParseClient mineruParseClient;
    private final LlamaIndexRagClient llamaIndexRagClient;
    private final OssStorageClient ossStorageClient;
    private final AsyncTaskCenter taskCenter;
    private final SemanticRagCache semanticRagCache;

    @Value("${knowledge-base.delete.max-attempts:3}")
    private int deleteMaxAttempts = 3;

    @Value("${knowledge-base.delete.initial-backoff-ms:300}")
    private long deleteInitialBackoffMs = 300;

    public KnowledgeBaseService(MineruParseClient mineruParseClient,
                                LlamaIndexRagClient llamaIndexRagClient,
                                OssStorageClient ossStorageClient,
                                AsyncTaskCenter taskCenter,
                                SemanticRagCache semanticRagCache) {
        this.mineruParseClient = mineruParseClient;
        this.llamaIndexRagClient = llamaIndexRagClient;
        this.ossStorageClient = ossStorageClient;
        this.taskCenter = taskCenter;
        this.semanticRagCache = semanticRagCache;
        File dir = new File(KB_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.nameMap = loadMetadata();  // 加载知识库元数据
        this.indexMetaMap = loadIndexMeta();  // 加载知识库索引元数据
    }

    private Map<String, String> loadMetadata() {
        File file = META_FILE.toFile();
        if (file.exists()) {
            try {
                return objectMapper.readValue(file, new TypeReference<ConcurrentHashMap<String, String>>() {});
            } catch (IOException e) {
                log.error("加载知识库元数据失败", e);
            }
        }
        return new ConcurrentHashMap<>();
    }

    private synchronized void saveMetadata() {
        try {
            objectMapper.writeValue(META_FILE.toFile(), nameMap);
        } catch (IOException e) {
            log.error("保存知识库元数据失败", e);
        }
    }

    private Map<String, Map<String, Object>> loadIndexMeta() {
        File file = INDEX_META_FILE.toFile();
        if (file.exists()) {
            try {
                return objectMapper.readValue(file,
                        new TypeReference<ConcurrentHashMap<String, Map<String, Object>>>() {});
            } catch (IOException e) {
                log.error("加载知识库索引元数据失败", e);
            }
        }
        return new ConcurrentHashMap<>();
    }

    private synchronized void saveIndexMeta() {
        try {
            objectMapper.writeValue(INDEX_META_FILE.toFile(), indexMetaMap);
        } catch (IOException e) {
            log.error("保存知识库索引元数据失败", e);
        }
    }

    /** 保存文件后立即返回，解析和双路索引在任务中心异步执行。 */
    public Map<String, Object> saveFile(MultipartFile file, MedicalDocumentMetadata medicalMetadata) {
        long saveStartedNanos = System.nanoTime();
        MedicalDocumentMetadata metadata = validateMetadata(medicalMetadata);
        if (file.isEmpty()) {
            throw new RuntimeException("文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null) {
            throw new RuntimeException("文件名不能为空");
        }

        String ext = getFileExtension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new RuntimeException("不支持的文件类型: " + ext);
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        String savedName = id + ext;
        Path filePath = Paths.get(KB_DIR, savedName);

        try {
            Files.copy(file.getInputStream(), filePath);
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new RuntimeException("文件保存失败: " + e.getMessage());
        }
        long localSaveMs = elapsedMs(saveStartedNanos);

        String contentSha256;
        String fileSource;
        long shaStarted = System.nanoTime();
        try {
            contentSha256 = sha256(filePath);
            long sha256Ms = elapsedMs(shaStarted);
            long sourceStarted = System.nanoTime();
            fileSource = ossStorageClient.prepareSource(OSS_OBJECT_PREFIX + savedName, filePath);
            long sourcePrepareMs = elapsedMs(sourceStarted);

            nameMap.put(id, originalName);
            saveMetadata();

            String uploadTime = LocalDateTime.now().atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("name", originalName);
            result.put("size", file.getSize());
            result.put("uploadTime", uploadTime);

            recordIndexMeta(id, "QUEUED", null, null, null);
            putMedicalMetadata(id, metadata);
            long queuedNanos = System.nanoTime();
            TaskRecord task = taskCenter.submit(TaskType.DOCUMENT_INDEX, "knowledge-base",
                    Map.of("documentId", id, "fileName", originalName, "medicalMetadata", metadata),
                    context -> processDocument(
                            context, id, originalName, queuedNanos, localSaveMs, metadata,
                            fileSource, contentSha256, sha256Ms, sourcePrepareMs));
            recordIndexMeta(id, "QUEUED", null, null, task.getId());
            result.put("status", "QUEUED");
            result.put("taskId", task.getId());
            result.put("storageMode", ossStorageClient.storageMode());
            result.put("sourceUploaded", ossStorageClient.usesOss());
            return result;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw new IllegalStateException("知识库原文上传或准备失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> processDocument(AsyncTaskCenter.TaskContext context, String id,
                                                String originalName, long queuedNanos,
                                                long localSaveMs, MedicalDocumentMetadata medicalMetadata,
                                                String fileSource, String contentSha256,
                                                long sha256Ms, long sourcePrepareMs) throws Exception {
        long totalStarted = System.nanoTime();
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("localSaveMs", localSaveMs);
        timings.put("javaQueueWaitMs", elapsedMs(queuedNanos));
        timings.put("sha256Ms", sha256Ms);
        timings.put(ossStorageClient.usesOss() ? "ossUploadMs" : "localSourcePrepareMs",
                sourcePrepareMs);
        try {
            updateIndexStatus(id, "PARSING_INDEXING", null);
            context.update(30, "文档服务解析并索引",
                    "原文已就绪，等待Java文档MCP调用MinerU并完成分层切片和向量索引");
            long documentMcpStarted = System.nanoTime();
            MineruParseClient.ParseIndexResult indexResult =
                    mineruParseClient.parseAndIndex(id, fileSource, contentSha256,
                            documentMetadata(medicalMetadata, originalName));
            timings.put("documentMcpParseIndexMs", elapsedMs(documentMcpStarted));
            timings.put("documentMcp", indexResult.timings());
            timings.put("totalMs", elapsedMs(totalStarted));
            String summary = indexResult.summary();

            context.update(95, "完成索引", "文档已可检索");
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("summary", summary);
            details.put("chunkCount", indexResult.leafNodeCount());
            details.put("documentMcpTaskId", indexResult.taskId());
            details.put("storageMode", ossStorageClient.storageMode());
            if (ossStorageClient.usesOss()) details.put("url", fileSource);
            details.put("medicalMetadata", medicalMetadata);
            details.put("timings", timings);
            recordIndexMeta(id, "INDEXED", summary, null, context.taskId());
            putIndexDetails(id, indexResult.leafNodeCount(), indexResult.taskId(), timings);
            log.info("知识库文档处理完成 id={}, name={}, timings={}", id, originalName, timings);
            return details;
        } catch (Exception e) {
            timings.put("totalMs", elapsedMs(totalStarted));
            log.error("文档异步解析/索引失败: id={}, name={}, timings={}",
                    id, originalName, timings, e);
            if (!isDeleting(id)) {
                recordIndexMeta(id, "FAILED", null, e.getMessage(), context.taskId());
            }
            throw e;
        }
    }

    private synchronized void putIndexDetails(String id, int chunkCount, String documentMcpTaskId,
                                              Map<String, Object> timings) {
        Map<String, Object> meta = indexMetaMap.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
        meta.put("chunkCount", chunkCount);
        meta.put("documentMcpTaskId", documentMcpTaskId);
        meta.put("timings", timings);
        saveIndexMeta();
    }

    private synchronized void putMedicalMetadata(String id, MedicalDocumentMetadata metadata) {
        Map<String, Object> meta = indexMetaMap.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
        meta.put("medicalMetadata", metadata);
        saveIndexMeta();
    }

    private Map<String, Object> documentMetadata(MedicalDocumentMetadata medicalMetadata,
                                                 String originalName) {
        Map<String, Object> metadata = objectMapper.convertValue(
                medicalMetadata, new TypeReference<>() {});
        metadata.put("original_filename", originalName);
        return metadata;
    }

    private void recordIndexMeta(String id, String status, String summary, String error) {
        recordIndexMeta(id, status, summary, error, null);
    }

    private synchronized void recordIndexMeta(String id, String status, String summary, String error, String taskId) {
        Map<String, Object> meta = indexMetaMap.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
        meta.put("status", status);
        if (summary != null) {
            meta.put("summary", summary);
        }
        if (error != null) {
            meta.put("error", error);
        }
        if (taskId != null) meta.put("taskId", taskId);
        indexMetaMap.put(id, meta);
        saveIndexMeta();
    }

    private synchronized void updateIndexStatus(String id, String status, String error) {
        Map<String, Object> meta = indexMetaMap.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
        meta.put("status", status);
        if (error != null) meta.put("error", error);
        else meta.remove("error");
        saveIndexMeta();
    }

    private synchronized boolean isDeleting(String id) {
        Map<String, Object> meta = indexMetaMap.get(id);
        if (meta == null) return false;
        String status = String.valueOf(meta.get("status"));
        return "DELETING".equals(status) || "DELETE_FAILED".equals(status);
    }

    public List<Map<String, Object>> listFiles() {
        File dir = new File(KB_DIR);
        File[] files = dir.listFiles((d, name) -> !name.equals("metadata.json") && !name.equals("index-meta.json"));
        if (files == null) {
            return List.of();
        }

        return Arrays.stream(files)
                .filter(File::isFile)
                .map(f -> {
                    String fileName = f.getName();
                    String ext = getFileExtension(fileName);
                    String id = fileName.substring(0, fileName.length() - ext.length());

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id);
                    item.put("name", nameMap.getOrDefault(id, fileName));
                    item.put("size", f.length());
                    item.put("uploadTime", ZonedDateTime.ofInstant(
                            new Date(f.lastModified()).toInstant(),
                            ZoneId.systemDefault()
                    ).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    // 附加索引状态
                    Map<String, Object> indexMeta = indexMetaMap.get(id);
                    if (indexMeta != null) {
                        item.put("status", indexMeta.get("status"));
                        item.put("summary", indexMeta.get("summary"));
                        item.put("error", indexMeta.get("error"));
                        item.put("taskId", indexMeta.get("taskId"));
                        item.put("documentMcpTaskId", indexMeta.get("documentMcpTaskId"));
                        item.put("chunkCount", indexMeta.get("chunkCount"));
                        item.put("timings", indexMeta.get("timings"));
                        item.put("medicalMetadata", indexMeta.get("medicalMetadata"));
                    }
                    return item;
                })
                .collect(Collectors.toList());
    }

    public synchronized void deleteFile(String id) {
        File dir = new File(KB_DIR);
        File[] files = dir.listFiles((d, name) -> name.startsWith(id) && !name.equals("metadata.json") && !name.equals("index-meta.json"));
        boolean knownDocument = nameMap.containsKey(id) || indexMetaMap.containsKey(id)
                || (files != null && files.length > 0);
        if (!knownDocument) {
            throw new RuntimeException("文件不存在: " + id);
        }

        updateIndexStatus(id, "DELETING", null);
        cancelIndexTask(id);
        try {
            // 必须先由文档MCP完成幂等索引删除并确认无残留，再提交Admin侧元数据删除。
            LlamaIndexRagClient.DeleteResult deleteResult = deleteIndexWithRetry(id);

            List<String> savedNames = new ArrayList<>();
            if (files != null) {
                for (File file : files) savedNames.add(file.getName());
            }
            if (savedNames.isEmpty()) {
                String originalName = nameMap.get(id);
                if (originalName != null) savedNames.add(id + getFileExtension(originalName));
            }

            // OSS先删，若失败保留本地文件和元数据，后续请求可以安全重试。
            for (String savedName : savedNames) {
                ossStorageClient.delete(OSS_OBJECT_PREFIX + savedName);
            }
            if (files != null) {
                for (File file : files) Files.deleteIfExists(file.toPath());
            }

            nameMap.remove(id);
            saveMetadata();
            indexMetaMap.remove(id);
            saveIndexMeta();
            log.info("知识库文档删除完成: id={}, redisFound={}, redisDeleted={}, verified={}",
                    id, deleteResult.redisNodesFound(), deleteResult.redisNodesDeleted(),
                    deleteResult.verified());
        } catch (Exception e) {
            updateIndexStatus(id, "DELETE_FAILED", e.getMessage());
            throw new IllegalStateException("删除知识库文档失败，删除状态已保留，可安全重试: id=" + id, e);
        }
    }

    private LlamaIndexRagClient.DeleteResult deleteIndexWithRetry(String id) {
        RuntimeException lastFailure = null;
        int attempts = Math.max(1, deleteMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                LlamaIndexRagClient.DeleteResult result = llamaIndexRagClient.delete(id);
                log.info("文档MCP索引删除校验通过: id={}, attempt={}, remaining={}",
                        id, attempt, result.redisNodesRemaining());
                return result;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt == attempts) break;
                long backoff = Math.max(0, deleteInitialBackoffMs) * (1L << Math.min(attempt - 1, 10));
                log.warn("文档MCP索引删除失败，准备重试: id={}, attempt={}/{}, backoffMs={}",
                        id, attempt, attempts, backoff, e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("删除重试被中断: id=" + id, interrupted);
                }
            }
        }
        throw new IllegalStateException("文档MCP索引删除在" + attempts + "次尝试后仍失败: id=" + id,
                lastFailure);
    }

    private void cancelIndexTask(String id) {
        Map<String, Object> meta = indexMetaMap.get(id);
        if (meta == null || meta.get("taskId") == null) return;
        String taskId = String.valueOf(meta.get("taskId"));
        try {
            TaskRecord task = taskCenter.get(taskId);
            if (task.getStatus() == com.tianaiagent.task.TaskStatus.PENDING
                    || task.getStatus() == com.tianaiagent.task.TaskStatus.RUNNING) {
                taskCenter.cancel(taskId);
                log.info("删除前已取消文档索引任务: id={}, taskId={}", id, taskId);
            }
        } catch (IllegalArgumentException e) {
            log.warn("删除前未找到文档索引任务，继续执行幂等删除: id={}, taskId={}", id, taskId);
        }
    }

    /**
     * 检索增强问答（由Java文档MCP完成多层级Chunk检索与证据约束回答）
     */
    public String query(String question) {
        if (question == null || question.isBlank()) {
            throw new RuntimeException("查询内容不能为空");
        }
        return llamaIndexRagClient.query(question.trim());
    }

    /** 返回LLM生成的回答和参与生成的可追溯原文证据。 */
    public JsonNode queryWithSources(String question, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new RuntimeException("查询内容不能为空");
        }
        int limit = topK == null ? 8 : Math.max(1, Math.min(topK, 20));
        String normalizedQuestion = question.trim();
        String version = knowledgeVersion();
        JsonNode result = semanticRagCache.get(normalizedQuestion, limit, version).orElseGet(() -> {
            JsonNode fresh = llamaIndexRagClient.queryWithSources(normalizedQuestion, limit);
            semanticRagCache.put(normalizedQuestion, limit, version, fresh);
            return fresh;
        });
        for (JsonNode source : result.path("sources")) {
            if (source instanceof com.fasterxml.jackson.databind.node.ObjectNode objectSource) {
                String documentId = source.path("documentId").asText("");
                objectSource.put("fileName",
                        nameMap.getOrDefault(documentId, documentId));
            }
        }
        return result;
    }

    private String knowledgeVersion() {
        try {
            Map<String, Object> state = new TreeMap<>();
            nameMap.forEach((id, name) -> state.put(id, Map.of(
                    "name", name,
                    "status", String.valueOf(indexMetaMap.getOrDefault(id, Map.of()).getOrDefault("status", "")),
                    "chunks", String.valueOf(indexMetaMap.getOrDefault(id, Map.of()).getOrDefault("chunkCount", "")))));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(state)));
        } catch (Exception e) {
            throw new IllegalStateException("计算知识库版本失败", e);
        }
    }

    /** 只从Java文档MCP召回原始片段，不经过回答模型。 */
    public JsonNode retrieve(String question, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new RuntimeException("查询内容不能为空");
        }
        int limit = topK == null ? 8 : Math.max(1, Math.min(topK, 20));
        return llamaIndexRagClient.retrieve(question.trim(), limit);
    }

    /** 校验文档的元信息是否完整 */
    private MedicalDocumentMetadata validateMetadata(MedicalDocumentMetadata value) {
        if (value == null) throw new IllegalArgumentException("医疗文档元数据不能为空");
        require(value.issuer(), "issuer"); require(value.version(), "version");
        require(value.publishedAt(), "publishedAt"); require(value.audience(), "audience");
        require(value.documentType(), "documentType");
        try { java.time.LocalDate.parse(value.publishedAt()); }
        catch (Exception e) { throw new IllegalArgumentException("publishedAt必须为YYYY-MM-DD"); }
        if (!"ACTIVE".equalsIgnoreCase(value.effectiveStatus())) {
            throw new IllegalArgumentException("只有effectiveStatus=ACTIVE的文档可以进入当前检索库");
        }
        if (!"APPROVED".equalsIgnoreCase(value.reviewStatus())) {
            throw new IllegalArgumentException("只有reviewStatus=APPROVED的文档可以进入当前检索库");
        }
        return new MedicalDocumentMetadata(value.issuer().trim(), value.version().trim(),
                value.publishedAt().trim(), value.audience().trim(), "ACTIVE", "APPROVED",
                value.documentType().trim());
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    }

    public record MedicalDocumentMetadata(String issuer, String version, String publishedAt,
                                          String audience, String effectiveStatus,
                                          String reviewStatus, String documentType) {}

    /** 读取Path的字节流，不断的更新digest，最后生成sha256 */
    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());  // 将sha256转化为16进制字符串
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return "";
        }
        return fileName.substring(dotIndex);
    }

    /** 计算从开始时间到现在的纳秒数然后转化为毫秒 */
    private static long elapsedMs(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos);
    }
}
