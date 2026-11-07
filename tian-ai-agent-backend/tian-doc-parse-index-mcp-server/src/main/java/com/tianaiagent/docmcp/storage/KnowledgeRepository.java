package com.tianaiagent.docmcp.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.model.ParseTask;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianaiagent.docmcp.wiki.WikiRepository;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private WikiRepository wiki;

    @Autowired
    void setWikiRepository(WikiRepository wiki) { this.wiki = wiki; }

    public KnowledgeRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS parse_tasks(
                  task_id VARCHAR(64) PRIMARY KEY, payload CLOB NOT NULL, updated_at VARCHAR(40) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS documents(
                  doc_id VARCHAR(300) PRIMARY KEY, title VARCHAR(1000), source_uri CLOB,
                  content_sha256 VARCHAR(64), metadata_json CLOB NOT NULL, updated_at VARCHAR(40) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chunks(
                  node_id VARCHAR(64) PRIMARY KEY, doc_id VARCHAR(300) NOT NULL,
                  parent_id VARCHAR(64), level_no INT NOT NULL, leaf BOOLEAN NOT NULL,
                  ordinal_no INT NOT NULL, title VARCHAR(1000), source_uri CLOB,
                  content_sha256 VARCHAR(64), chunk_text CLOB NOT NULL,
                  metadata_json CLOB NOT NULL, vector BLOB)
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_doc_idx ON chunks(doc_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_leaf_idx ON chunks(leaf)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS semantic_memories(
                  memory_id VARCHAR(128) PRIMARY KEY, owner_id VARCHAR(300) NOT NULL,
                  memory_text CLOB NOT NULL, metadata_json CLOB NOT NULL,
                  vector BLOB NOT NULL, updated_at VARCHAR(40) NOT NULL)
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS semantic_memories_owner_idx ON semantic_memories(owner_id)");
        jdbc.update("UPDATE parse_tasks SET payload=REPLACE(payload, '\"status\":\"running\"', '\"status\":\"interrupted\"') WHERE payload LIKE '%\"status\":\"running\"%'");
        jdbc.update("UPDATE parse_tasks SET payload=REPLACE(payload, '\"status\":\"queued\"', '\"status\":\"interrupted\"') WHERE payload LIKE '%\"status\":\"queued\"%'");
    }

    public synchronized void saveTask(ParseTask task) {
        task.setUpdatedAt(Instant.now().toString());
        try {
            jdbc.update("MERGE INTO parse_tasks(task_id,payload,updated_at) KEY(task_id) VALUES (?,?,?)",
                    task.getTaskId(), mapper.writeValueAsString(task), task.getUpdatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析任务序列化失败", e);
        }
    }

    public Optional<ParseTask> findTask(String taskId) {
        List<String> values = jdbc.query("SELECT payload FROM parse_tasks WHERE task_id=?",
                (rs, row) -> rs.getString(1), taskId);
        if (values.isEmpty()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(values.getFirst(), ParseTask.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析任务数据损坏: " + taskId, e);
        }
    }

    public void replaceDocument(DocumentRecord document, List<ChunkRecord> chunks) {
        replaceDocument(document, chunks, null);
    }

    public void replaceDocument(DocumentRecord document, List<ChunkRecord> chunks, String markdown) {
        transactions.executeWithoutResult(status -> {
            if (wiki != null) {
                String version = fingerprint(document, chunks);
                wiki.sourceChanged(document.docId(), version);
                archive(document, chunks, markdown, version);
            }
            jdbc.update("DELETE FROM chunks WHERE doc_id=?", document.docId());
            jdbc.update("DELETE FROM documents WHERE doc_id=?", document.docId());
            jdbc.update("INSERT INTO documents(doc_id,title,source_uri,content_sha256,metadata_json,updated_at) VALUES (?,?,?,?,?,?)",
                    document.docId(), document.title(), document.sourceUri(), document.contentSha256(),
                    writeJson(document.metadata()), Instant.now().toString());
            for (ChunkRecord chunk : chunks) {
                jdbc.update("INSERT INTO chunks(node_id,doc_id,parent_id,level_no,leaf,ordinal_no,title,source_uri,content_sha256,chunk_text,metadata_json,vector) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        chunk.nodeId(), chunk.docId(), chunk.parentId(), chunk.level(), chunk.leaf(),
                        chunk.ordinal(), chunk.title(), chunk.sourceUri(), chunk.contentSha256(), chunk.text(),
                        writeJson(chunk.metadata()), floatsToBytes(chunk.vector()));
            }
        });
    }

    public boolean deleteDocument(String docId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (wiki != null) wiki.sourceDeleted(docId);
            jdbc.update("DELETE FROM chunks WHERE doc_id=?", docId);
            return jdbc.update("DELETE FROM documents WHERE doc_id=?", docId) > 0;
        }));
    }

    public void registerWikiSource(String docId) {
        if (wiki == null) return;
        transactions.executeWithoutResult(status -> {
            wiki.lockSources();
            var documents = jdbc.query("SELECT * FROM documents WHERE doc_id=?", (rs, i) -> new DocumentRecord(rs.getString("doc_id"), rs.getString("title"), rs.getString("source_uri"), rs.getString("content_sha256"), readMap(rs.getString("metadata_json"))), docId);
            if (!documents.isEmpty()) {
                var document = documents.getFirst(); var chunks = loadDocumentChunks(docId);
                String version = fingerprint(document, chunks);
                wiki.sourceChanged(docId, version);
                archive(document, chunks, null, version);
            }
        });
    }

    private String fingerprint(DocumentRecord document, List<ChunkRecord> chunks) {
        return WikiRepository.key(document.docId() + "\n" + document.title() + "\n" + document.sourceUri()
                + "\n" + writeJson(new java.util.TreeMap<>(document.metadata())) + "\n"
                + chunks.stream().filter(ChunkRecord::leaf).sorted(java.util.Comparator.comparingInt(ChunkRecord::ordinal))
                .map(c -> c.nodeId() + ":" + c.text()).collect(java.util.stream.Collectors.joining("\n")));
    }

    private void archive(DocumentRecord document, List<ChunkRecord> chunks, String markdown, String version) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("doc_id", document.docId()); snapshot.put("source_version", version);
        snapshot.put("title", document.title()); snapshot.put("source_uri", document.sourceUri()); snapshot.put("metadata", document.metadata());
        snapshot.put("markdown", markdown == null ? "" : markdown);
        snapshot.put("archive_format", markdown == null ? "canonical-chunks" : "parsed-markdown");
        snapshot.put("chunks", chunks.stream().filter(ChunkRecord::leaf).map(c -> Map.of("node_id", c.nodeId(), "text", c.text())).toList());
        wiki.archive(document.docId(), version, snapshot);
    }

    public List<ChunkRecord> loadLeafChunks() {
        return jdbc.query("SELECT * FROM chunks WHERE leaf=TRUE", this::mapChunk);
    }

    public List<ChunkRecord> loadLeafChunksAfter(String afterId, int limit) {
        return jdbc.query("SELECT * FROM chunks WHERE leaf=TRUE AND node_id>? ORDER BY node_id LIMIT ?",
                this::mapChunk, afterId, limit);
    }

    public List<ChunkRecord> loadDocumentChunks(String docId) {
        return jdbc.query("SELECT * FROM chunks WHERE doc_id=? ORDER BY level_no,ordinal_no", this::mapChunk, docId);
    }

    public Optional<ChunkRecord> findChunk(String nodeId) {
        List<ChunkRecord> chunks = jdbc.query("SELECT * FROM chunks WHERE node_id=?", this::mapChunk, nodeId);
        return chunks.stream().findFirst();
    }

    public void upsertMemory(MemoryRecord memory) {
        jdbc.update("MERGE INTO semantic_memories(memory_id,owner_id,memory_text,metadata_json,vector,updated_at) "
                        + "KEY(memory_id) VALUES (?,?,?,?,?,?)",
                memory.memoryId(), memory.ownerId(), memory.text(), writeJson(memory.metadata()),
                floatsToBytes(memory.vector()), Instant.now().toString());
    }

    public List<MemoryRecord> loadMemories(String ownerId) {
        return jdbc.query("SELECT * FROM semantic_memories WHERE owner_id=?", (rs, row) ->
                new MemoryRecord(rs.getString("memory_id"), rs.getString("owner_id"),
                        rs.getString("memory_text"), readMap(rs.getString("metadata_json")),
                        bytesToFloats(rs.getBytes("vector"))), ownerId);
    }

    public void deleteMemories(String ownerId) {
        jdbc.update("DELETE FROM semantic_memories WHERE owner_id=?", ownerId);
    }

    public List<Map<String, Object>> listDocuments() {
        return jdbc.query("SELECT doc_id,title,source_uri,content_sha256,updated_at FROM documents ORDER BY updated_at DESC",
                (rs, row) -> {
                    Map<String, Object> value = new java.util.LinkedHashMap<>();
                    value.put("doc_id", rs.getString("doc_id"));
                    value.put("title", rs.getString("title"));
                    value.put("source_uri", rs.getString("source_uri"));
                    value.put("content_sha256", rs.getString("content_sha256"));
                    value.put("updated_at", rs.getString("updated_at"));
                    return value;
                });
    }

    public Map<String, Object> status() {
        Integer documents = jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class);
        Integer chunks = jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class);
        Integer vectors = jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE vector IS NOT NULL", Integer.class);
        return Map.of("documents", documents == null ? 0 : documents,
                "chunks", chunks == null ? 0 : chunks,
                "vectors", vectors == null ? 0 : vectors);
    }

    private ChunkRecord mapChunk(ResultSet rs, int row) throws SQLException {
        return new ChunkRecord(rs.getString("node_id"), rs.getString("doc_id"),
                rs.getString("parent_id"), rs.getInt("level_no"), rs.getBoolean("leaf"),
                rs.getInt("ordinal_no"), rs.getString("title"), rs.getString("source_uri"),
                rs.getString("content_sha256"), rs.getString("chunk_text"),
                readMap(rs.getString("metadata_json")), bytesToFloats(rs.getBytes("vector")));
    }

    private String writeJson(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try { return mapper.readValue(value, Map.class); }
        catch (JsonProcessingException e) { return Map.of(); }
    }

    static byte[] floatsToBytes(float[] values) {
        if (values == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }

    static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < result.length; i++) result[i] = buffer.getFloat();
        return result;
    }

    public record DocumentRecord(String docId, String title, String sourceUri,
                                 String contentSha256, Map<String, Object> metadata) {}
    public record ChunkRecord(String nodeId, String docId, String parentId, int level,
                              boolean leaf, int ordinal, String title, String sourceUri,
                              String contentSha256, String text, Map<String, Object> metadata,
                              float[] vector) {}
    public record MemoryRecord(String memoryId, String ownerId, String text,
                               Map<String, Object> metadata, float[] vector) {}
}
