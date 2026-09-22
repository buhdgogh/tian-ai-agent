package com.tianaiagent.docmcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.index.IndexService;
import com.tianaiagent.docmcp.index.SemanticMemoryService;
import com.tianaiagent.docmcp.model.ApiResponse;
import com.tianaiagent.docmcp.parse.DocumentParseService;
import com.tianaiagent.docmcp.parse.DocumentParseService.ParseRequest;
import com.tianaiagent.docmcp.query.ChatService;
import com.tianaiagent.docmcp.storage.KnowledgeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpToolService {
    private final DocumentParseService parsing;
    private final IndexService indexing;
    private final ChatService chat;
    private final KnowledgeRepository repository;
    private final ObjectMapper mapper;
    private final SemanticMemoryService semanticMemory;

    public McpToolService(DocumentParseService parsing, IndexService indexing, ChatService chat,
                          KnowledgeRepository repository, ObjectMapper mapper,
                          SemanticMemoryService semanticMemory) {
        this.parsing = parsing;
        this.indexing = indexing;
        this.chat = chat;
        this.repository = repository;
        this.mapper = mapper;
        this.semanticMemory = semanticMemory;
    }

    public Object call(String name, JsonNode arguments) {
        try {
            return switch (name) {
                case "parse_document" -> parseDocument(arguments);
                case "parse_documents_batch" -> parseBatch(arguments);
                case "get_parse_task" -> getTask(text(arguments, "task_id", true));
                case "index_document" -> indexDocument(arguments);
                case "retrieve_knowledge" -> retrieve(arguments);
                case "get_chunk_context" -> getChunkContext(arguments);
                case "query_knowledge" -> ApiResponse.ok(chat.answer(
                        text(arguments, "question", true), integer(arguments, "top_k", 8)));
                case "delete_document" -> deleteDocument(arguments);
                case "list_documents" -> listDocuments();
                case "knowledge_base_status" -> knowledgeBaseStatus();
                case "upsert_long_term_memory" -> upsertMemory(arguments);
                case "search_long_term_memory" -> searchMemory(arguments);
                case "delete_long_term_memory" -> deleteMemory(arguments);
                default -> ApiResponse.error("tool_not_found", "未知工具: " + name);
            };
        } catch (DocumentParseService.TaskNotFoundException e) {
            return ApiResponse.error("task_not_found", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("invalid_argument", e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("internal_error", rootMessage(e));
        }
    }

    private Object parseDocument(JsonNode args) {
        String source = text(args, "file_source", true);
        Map<String, String> hashes = args.hasNonNull("content_sha256")
                ? Map.of(source, args.path("content_sha256").asText()) : Map.of();
        Map<String, String> ids = args.hasNonNull("doc_id")
                ? Map.of(source, args.path("doc_id").asText()) : Map.of();
        Map<String, Map<String, Object>> metadata = args.path("metadata").isObject()
                ? Map.of(source, objectMap(args.path("metadata"))) : Map.of();
        ParseRequest request = request(List.of(source), hashes, ids, metadata, args);
        return ApiResponse.ok(parsing.submit(request));
    }

    private Object parseBatch(JsonNode args) {
        List<String> sources = new ArrayList<>();
        args.path("file_sources").forEach(value -> sources.add(value.asText()));
        Map<String, String> hashes = stringMap(args.path("content_sha256s"));
        Map<String, String> ids = stringMap(args.path("doc_ids"));
        Map<String, Map<String, Object>> metadata = nestedObjectMap(args.path("document_metadatas"));
        return ApiResponse.ok(parsing.submit(request(sources, hashes, ids, metadata, args)));
    }

    private ParseRequest request(List<String> sources, Map<String, String> hashes,
                                 Map<String, String> ids, Map<String, Map<String, Object>> metadata,
                                 JsonNode args) {
        return new ParseRequest(sources, hashes, ids, metadata,
                optionalText(args, "mode", "auto"), optionalText(args, "model", "vlm"),
                bool(args, "ocr", false), bool(args, "formula", true), bool(args, "table", true),
                optionalText(args, "language", "ch"), bool(args, "auto_index", true));
    }

    private Object getTask(String taskId) { return ApiResponse.ok(parsing.get(taskId)); }

    @SuppressWarnings("unchecked")
    private Object indexDocument(JsonNode args) {
        String docId = text(args, "doc_id", true);
        String markdown = text(args, "text", false);
        if (markdown.isBlank()) markdown = text(args, "markdown", true);
        Map<String, Object> metadata = args.path("metadata").isObject()
                ? mapper.convertValue(args.path("metadata"), Map.class) : new LinkedHashMap<>();
        for (String field : List.of("title", "source_uri", "content_sha256", "page_number")) {
            if (args.hasNonNull(field)) metadata.put(field, mapper.convertValue(args.get(field), Object.class));
        }
        IndexService.IndexResult result = indexing.upsert(docId, markdown, metadata);
        return ApiResponse.ok(Map.of("doc_id", result.docId(), "leaf_node_count", result.leafNodeCount(),
                "node_count", result.nodeCount()));
    }

    private Object retrieve(JsonNode args) {
        int topK = integer(args, "top_k", 8);
        IndexService.RetrievalResult result = indexing.retrieve(text(args, "question", true), topK);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", result.question());
        data.put("count", result.sources().size());
        data.put("retrieval_top_k", topK);
        data.put("rerank_top_n", topK);
        data.put("rerank_model", "java-mmr");
        data.put("retrieval_mode", result.mode());
        data.put("queries", List.of(result.question()));
        data.put("sources", result.sources());
        return ApiResponse.ok(data);
    }

    private Object getChunkContext(JsonNode args) {
        String chunkId = text(args, "chunk_id", true);
        List<Map<String, Object>> context = indexing.getChunkContext(chunkId);
        return ApiResponse.ok(Map.of("chunk_id", chunkId, "levels", context.size(), "context", context));
    }

    private Object deleteDocument(JsonNode args) {
        String docId = text(args, "doc_id", true);
        if (!indexing.deleteDocument(docId)) return ApiResponse.error("document_not_found", "文档不存在: " + docId);
        return ApiResponse.ok(Map.of("doc_id", docId, "deleted", true));
    }

    private Object listDocuments() {
        List<Map<String, Object>> documents = repository.listDocuments();
        return ApiResponse.ok(Map.of("count", documents.size(), "documents", documents));
    }

    private Object knowledgeBaseStatus() {
        Map<String, Object> result = new LinkedHashMap<>(repository.status());
        Map<String, Object> vectorStatus = indexing.status();
        result.put("vector_store", vectorStatus);
        if (vectorStatus.containsKey("vectors")) result.put("vectors", vectorStatus.get("vectors"));
        result.put("retrieval", Map.of("mode", "hybrid-vector-keyword-mmr"));
        return ApiResponse.ok(result);
    }

    private Object upsertMemory(JsonNode args) {
        String ownerId = text(args, "owner_id", true);
        String memoryId = text(args, "memory_id", true);
        semanticMemory.upsert(ownerId, memoryId, text(args, "text", true),
                args.path("metadata").isObject() ? objectMap(args.path("metadata")) : Map.of());
        return ApiResponse.ok(Map.of("owner_id", ownerId, "memory_id", memoryId, "stored", true));
    }

    private Object searchMemory(JsonNode args) {
        String ownerId = text(args, "owner_id", true);
        var memories = semanticMemory.search(ownerId, text(args, "query", true),
                integer(args, "top_k", 3));
        return ApiResponse.ok(Map.of("owner_id", ownerId, "count", memories.size(), "memories", memories));
    }

    private Object deleteMemory(JsonNode args) {
        String ownerId = text(args, "owner_id", true);
        semanticMemory.delete(ownerId);
        return ApiResponse.ok(Map.of("owner_id", ownerId, "deleted", true));
    }

    private static String text(JsonNode args, String field, boolean required) {
        String value = args == null ? "" : args.path(field).asText("");
        if (required && value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return value;
    }
    private static String optionalText(JsonNode args, String field, String fallback) {
        String value = text(args, field, false); return value.isBlank() ? fallback : value;
    }
    private static boolean bool(JsonNode args, String field, boolean fallback) {
        return args != null && args.has(field) ? args.path(field).asBoolean(fallback) : fallback;
    }
    private static int integer(JsonNode args, String field, int fallback) {
        return args != null && args.has(field) ? args.path(field).asInt(fallback) : fallback;
    }
    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) {
        return mapper.convertValue(node, Map.class);
    }
    private Map<String, Map<String, Object>> nestedObjectMap(JsonNode node) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isObject()) result.put(entry.getKey(), objectMap(entry.getValue()));
            });
        }
        return result;
    }
    private static String rootMessage(Throwable value) {
        Throwable current = value; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public List<Map<String, Object>> tools() {
        return List.of(
                tool("parse_document", "使用MinerU/Tika异步解析单个文档，并在Java侧自动切块、Embedding和索引", object(parseProperties(Map.of("file_source", string(), "doc_id", string(), "content_sha256", string(), "metadata", object(Map.of(), List.of()))), List.of("file_source"))),
                tool("parse_documents_batch", "使用MinerU/Tika异步解析多个文档，并在Java侧自动切块、Embedding和索引", object(parseProperties(Map.of("file_sources", array(string()), "content_sha256s", object(Map.of(), List.of()), "doc_ids", object(Map.of(), List.of()), "document_metadatas", object(Map.of(), List.of()))), List.of("file_sources"))),
                tool("get_parse_task", "查询异步文档解析与索引任务状态", object(Map.of("task_id", string()), List.of("task_id"))),
                tool("index_document", "增量新增或更新一个文档，不影响其他文档", object(Map.of("doc_id", string(), "text", string(), "title", string(), "source_uri", string(), "content_sha256", string(), "page_number", integerSchema()), List.of("doc_id", "text"))),
                tool("retrieve_knowledge", "执行向量与关键词混合召回，返回原文证据", object(Map.of("question", string(), "top_k", integerSchema()), List.of("question"))),
                tool("get_chunk_context", "根据召回的Chunk ID返回4096/1024/256父子上下文链", object(Map.of("chunk_id", string()), List.of("chunk_id"))),
                tool("query_knowledge", "基于知识库证据生成带来源回答", object(Map.of("question", string(), "top_k", integerSchema()), List.of("question"))),
                tool("delete_document", "按文档ID删除文档及全部索引", object(Map.of("doc_id", string()), List.of("doc_id"))),
                tool("list_documents", "列出已索引文档", object(Map.of(), List.of())),
                tool("knowledge_base_status", "查看知识库文档、分块和向量数量", object(Map.of(), List.of())),
                tool("upsert_long_term_memory", "写入按owner隔离的长期语义记忆", object(Map.of(
                        "owner_id", string(), "memory_id", string(), "text", string(),
                        "metadata", object(Map.of(), List.of())), List.of("owner_id", "memory_id", "text"))),
                tool("search_long_term_memory", "按owner隔离召回长期语义记忆", object(Map.of(
                        "owner_id", string(), "query", string(), "top_k", integerSchema()),
                        List.of("owner_id", "query"))),
                tool("delete_long_term_memory", "按owner删除会话长期语义记忆", object(Map.of(
                        "owner_id", string()), List.of("owner_id"))));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }
    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("type", "object"); value.put("properties", properties);
        if (!required.isEmpty()) value.put("required", required); value.put("additionalProperties", false); return value;
    }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> parseProperties(Map<String, Object> sourceProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(sourceProperties);
        properties.put("mode", Map.of("type", "string", "enum", List.of("auto", "mineru", "tika"), "default", "auto"));
        properties.put("model", Map.of("type", "string", "enum", List.of("vlm", "pipeline", "MinerU-HTML"), "default", "vlm"));
        properties.put("ocr", boolSchema());
        properties.put("formula", boolSchema());
        properties.put("table", boolSchema());
        properties.put("language", string());
        properties.put("auto_index", boolSchema());
        return properties;
    }
    private static Map<String, Object> boolSchema() { return Map.of("type", "boolean"); }
    private static Map<String, Object> integerSchema() { return Map.of("type", "integer", "minimum", 1, "maximum", 20); }
    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items, "minItems", 1); }
}
