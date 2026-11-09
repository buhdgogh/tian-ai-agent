CREATE TABLE IF NOT EXISTS parse_tasks(
    task_id VARCHAR(64) PRIMARY KEY, payload CLOB NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS documents(
    doc_id VARCHAR(300) PRIMARY KEY, title VARCHAR(1000), source_uri CLOB,
    content_sha256 VARCHAR(64), metadata_json CLOB NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS chunks(
    node_id VARCHAR(64) PRIMARY KEY, doc_id VARCHAR(300) NOT NULL,
    parent_id VARCHAR(64), level_no INT NOT NULL, leaf BOOLEAN NOT NULL,
    ordinal_no INT NOT NULL, title VARCHAR(1000), source_uri CLOB,
    content_sha256 VARCHAR(64), chunk_text CLOB NOT NULL, metadata_json CLOB NOT NULL, vector BLOB
);
CREATE INDEX IF NOT EXISTS chunks_doc_idx ON chunks(doc_id);
CREATE INDEX IF NOT EXISTS chunks_leaf_idx ON chunks(leaf);
CREATE TABLE IF NOT EXISTS document_deletions(doc_id VARCHAR(300) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS knowledge_spaces(
    space_id VARCHAR(32) PRIMARY KEY, name VARCHAR(100) NOT NULL, medical BOOLEAN NOT NULL
);
ALTER TABLE knowledge_spaces ADD COLUMN IF NOT EXISTS deleting BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS space_id VARCHAR(32) DEFAULT 'medical' NOT NULL;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS space_id VARCHAR(32) DEFAULT 'medical' NOT NULL;
ALTER TABLE document_deletions ADD COLUMN IF NOT EXISTS space_id VARCHAR(32) DEFAULT 'medical' NOT NULL;
CREATE INDEX IF NOT EXISTS chunks_space_leaf_idx ON chunks(space_id,leaf);
CREATE TABLE IF NOT EXISTS semantic_memories(
    memory_id VARCHAR(128) PRIMARY KEY, owner_id VARCHAR(300) NOT NULL,
    memory_text CLOB NOT NULL, metadata_json CLOB NOT NULL, vector BLOB NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS semantic_memories_owner_idx ON semantic_memories(owner_id);
UPDATE parse_tasks SET payload=REPLACE(payload, '"status":"running"', '"status":"interrupted"')
    WHERE payload LIKE '%"status":"running"%';
UPDATE parse_tasks SET payload=REPLACE(payload, '"status":"queued"', '"status":"interrupted"')
    WHERE payload LIKE '%"status":"queued"%';
