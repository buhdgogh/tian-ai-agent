import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** One-time migration of a stopped workspace's legacy knowledge database. */
class MigrateKnowledge {
    private static final Path SCHEMA = Path.of("tian-ai-agent-infrastructure/src/main/resources/db/sqlite/knowledge.sql");
    private static final String LEGACY_SUFFIX = ".mv.db";
    private static final Map<String, String> LEGACY_DEFAULTS = Map.of("space_id", "'medical'", "deleting", "FALSE");
    private static final Table[] TABLES = {
            new Table("knowledge_spaces", "space_id,name,medical,deleting"),
            new Table("parse_tasks", "task_id,payload,updated_at"),
            new Table("documents", "doc_id,title,source_uri,content_sha256,metadata_json,updated_at,space_id"),
            new Table("chunks", "node_id,doc_id,parent_id,level_no,leaf,ordinal_no,title,source_uri,"
                    + "content_sha256,chunk_text,metadata_json,vector,space_id"),
            new Table("document_deletions", "doc_id,space_id"),
            new Table("semantic_memories", "memory_id,owner_id,memory_text,metadata_json,vector,updated_at")
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: MigrateKnowledge <legacy.mv.db> <new.sqlite>");
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !source.toString().endsWith(LEGACY_SUFFIX)) {
            throw new IllegalArgumentException("Source must be an existing .mv.db file");
        }
        if (Files.exists(target)) throw new IllegalArgumentException("Destination already exists: " + target);
        String schema = Files.readString(SCHEMA);
        Files.createDirectories(target.getParent());
        Path staged = Files.createTempFile(target.getParent(), ".knowledge-migration-", ".sqlite");
        try {
            String base = source.toString().substring(0, source.toString().length() - LEGACY_SUFFIX.length());
            try (var from = DriverManager.getConnection("jdbc:h2:file:" + base + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
                 var to = DriverManager.getConnection("jdbc:sqlite:" + staged)) {
                to.setAutoCommit(false);
                try (var statement = to.createStatement()) {
                    for (String sql : schema.split(";")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
                for (var table : TABLES) copy(from, to, table);
                to.commit();
                try (var statement = to.createStatement(); var result = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!result.next() || !"ok".equals(result.getString(1))) throw new SQLException("SQLite integrity check failed");
                }
            }
            Files.move(staged, target);
            System.out.println("SQLite database: " + target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void copy(Connection from, Connection to, Table table) throws SQLException {
        String[] columns = table.columns().split(",");
        var existing = new HashSet<String>();
        try (var metadata = from.getMetaData().getColumns(null, "PUBLIC", table.name().toUpperCase(Locale.ROOT), null)) {
            while (metadata.next()) existing.add(metadata.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (existing.isEmpty()) {
            // These tables were added after the first knowledge schema.
            if (!table.name().equals("knowledge_spaces") && !table.name().equals("document_deletions")
                    && !table.name().equals("semantic_memories")) throw new SQLException("Missing table: " + table.name());
            System.out.println(table.name() + ": 0 (not present in source)");
            return;
        }
        String select = String.join(",", Arrays.stream(columns).map(column ->
                existing.contains(column) ? column : LEGACY_DEFAULTS.getOrDefault(column, column)).toList());
        String parameters = String.join(",", java.util.Collections.nCopies(columns.length, "?"));
        long copied = 0;
        try (var read = from.createStatement();
             var rows = read.executeQuery("SELECT " + select + " FROM " + table.name());
             var insert = to.prepareStatement("INSERT INTO " + table.name() + "(" + table.columns() + ") VALUES (" + parameters + ")")) {
            while (rows.next()) {
                for (int i = 0; i < columns.length; i++) {
                    int index = i + 1;
                    switch (columns[i]) {
                        case "vector" -> insert.setBytes(index, rows.getBytes(index));
                        case "leaf", "medical", "deleting" -> insert.setInt(index, rows.getBoolean(index) ? 1 : 0);
                        case "level_no", "ordinal_no" -> insert.setInt(index, rows.getInt(index));
                        default -> insert.setString(index, rows.getString(index));
                    }
                }
                insert.addBatch();
                if (++copied % 256 == 0) insert.executeBatch();
            }
            if (copied % 256 != 0) insert.executeBatch();
        }
        try (var statement = to.createStatement(); var count = statement.executeQuery("SELECT COUNT(*) FROM " + table.name())) {
            if (!count.next() || count.getLong(1) != copied) throw new SQLException("Row count mismatch: " + table.name());
        }
        System.out.println(table.name() + ": " + copied);
    }

    private record Table(String name, String columns) {}
}
