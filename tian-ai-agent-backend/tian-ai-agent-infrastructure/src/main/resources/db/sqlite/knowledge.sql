CREATE TABLE IF NOT EXISTS knowledge_spaces(
    space_id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, medical INTEGER NOT NULL,
    deleting INTEGER NOT NULL DEFAULT 0, embedding_binding TEXT
);
CREATE TABLE IF NOT EXISTS parse_tasks(
    task_id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS documents(
    doc_id TEXT NOT NULL PRIMARY KEY, title TEXT, source_uri TEXT, content_sha256 TEXT,
    metadata_json TEXT NOT NULL, updated_at TEXT NOT NULL, space_id TEXT NOT NULL DEFAULT 'medical'
);
CREATE TABLE IF NOT EXISTS chunks(
    node_id TEXT NOT NULL PRIMARY KEY, doc_id TEXT NOT NULL, parent_id TEXT,
    level_no INTEGER NOT NULL, leaf INTEGER NOT NULL, ordinal_no INTEGER NOT NULL,
    title TEXT, source_uri TEXT, content_sha256 TEXT, chunk_text TEXT NOT NULL, metadata_json TEXT NOT NULL,
    vector BLOB, space_id TEXT NOT NULL DEFAULT 'medical'
);
CREATE INDEX IF NOT EXISTS chunks_doc_idx ON chunks(doc_id);
CREATE INDEX IF NOT EXISTS chunks_space_leaf_idx ON chunks(space_id,leaf);
CREATE TABLE IF NOT EXISTS document_deletions(
    doc_id TEXT NOT NULL PRIMARY KEY, space_id TEXT NOT NULL DEFAULT 'medical'
);
CREATE TABLE IF NOT EXISTS semantic_memories(
    memory_id TEXT NOT NULL PRIMARY KEY, owner_id TEXT NOT NULL, memory_text TEXT NOT NULL,
    metadata_json TEXT NOT NULL, vector BLOB NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS semantic_memories_owner_idx ON semantic_memories(owner_id);
UPDATE parse_tasks SET payload=REPLACE(payload, '"status":"running"', '"status":"interrupted"')
    WHERE payload LIKE '%"status":"running"%';
UPDATE parse_tasks SET payload=REPLACE(payload, '"status":"queued"', '"status":"interrupted"')
    WHERE payload LIKE '%"status":"queued"%';
