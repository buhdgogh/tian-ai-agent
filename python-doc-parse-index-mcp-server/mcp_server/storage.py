"""SQLite persistence for document state, parse tasks, metadata and BM25 search.

The vector index remains in Redis. SQLite replaces the large mutable JSON files
and gives local development transactional commits, WAL readers and safe restart
behaviour without adding another service.
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator
from urllib.parse import urlsplit, urlunsplit

from llama_index.core.storage.docstore import SimpleDocumentStore


_SERVER_DIR = Path(__file__).resolve().parent


def _resolve_path(env_name: str, default: str) -> Path:
    configured = Path(os.getenv(env_name, default))
    return configured if configured.is_absolute() else _SERVER_DIR / configured


DB_PATH = _resolve_path("KNOWLEDGE_DB_PATH", "storage/knowledge.db")
LEGACY_DOCSTORE_PATH = _SERVER_DIR / "storage" / "docstore.json"
LEGACY_TASK_PATH = _resolve_path("DOCUMENT_RUNTIME_DIR", "runtime") / "parse_tasks.json"
LEGACY_PARSED_DIR = _resolve_path("PARSED_DIR", "parsed")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def bm25_tokens(text: str) -> str:
    """Tokenize mixed Chinese/English text for SQLite FTS5.

    FTS5's default unicode tokenizer does not segment Chinese. Character
    bigrams provide a dependency-free baseline while normal word tokens keep
    English identifiers searchable.
    """
    normalized = text.lower()
    words = re.findall(r"[a-z0-9][a-z0-9_.+-]*", normalized)
    chinese_runs = re.findall(r"[\u3400-\u9fff]+", normalized)
    chinese: list[str] = []
    for run in chinese_runs:
        chinese.extend(run if len(run) == 1 else (run[i : i + 2] for i in range(len(run) - 1)))
    return " ".join([*words, *chinese])


def _task_for_storage(task: dict[str, Any]) -> dict[str, Any]:
    stored = json.loads(json.dumps(task))
    if stored.get("status") not in {
        "completed",
        "completed_with_errors",
        "failed",
        "interrupted",
    }:
        return stored
    for item in stored.get("items", []):
        source = str(item.get("source", ""))
        parsed = urlsplit(source)
        if parsed.scheme in {"http", "https"} and (parsed.query or parsed.fragment):
            item["source"] = urlunsplit(
                (parsed.scheme, parsed.netloc, parsed.path, "", "")
            )
    return stored


class SQLiteKnowledgeStore:
    def __init__(
        self,
        path: Path = DB_PATH,
        *,
        legacy_docstore_path: Path | None = None,
        legacy_task_path: Path | None = None,
        legacy_parsed_dir: Path | None = None,
    ) -> None:
        self.path = path
        self.legacy_docstore_path = legacy_docstore_path
        self.legacy_task_path = legacy_task_path
        self.legacy_parsed_dir = legacy_parsed_dir
        self._init_lock = threading.Lock()
        self._initialized = False

    def _connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=NORMAL")
        connection.execute("PRAGMA busy_timeout=30000")
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    def initialize(self) -> None:
        if self._initialized:
            return
        with self._init_lock:
            if self._initialized:
                return
            with self._connect() as connection:
                connection.executescript(
                    """
                    CREATE TABLE IF NOT EXISTS state (
                        key TEXT PRIMARY KEY,
                        payload TEXT NOT NULL,
                        revision INTEGER NOT NULL DEFAULT 1,
                        updated_at TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS parse_tasks (
                        task_id TEXT PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS documents (
                        doc_id TEXT PRIMARY KEY,
                        title TEXT,
                        original_filename TEXT,
                        source_uri TEXT,
                        content_sha256 TEXT,
                        metadata_json TEXT NOT NULL DEFAULT '{}',
                        updated_at TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS chunks (
                        node_id TEXT PRIMARY KEY,
                        doc_id TEXT NOT NULL,
                        title TEXT,
                        original_filename TEXT,
                        page_number INTEGER,
                        source_uri TEXT,
                        content_sha256 TEXT,
                        text TEXT NOT NULL,
                        metadata_json TEXT NOT NULL DEFAULT '{}'
                    );
                    CREATE INDEX IF NOT EXISTS chunks_doc_id_idx ON chunks(doc_id);
                    CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
                        node_id UNINDEXED,
                        doc_id UNINDEXED,
                        search_text,
                        tokenize='unicode61'
                    );
                    """
                )
                document_columns = {
                    row["name"] for row in connection.execute("PRAGMA table_info(documents)")
                }
                if "original_filename" not in document_columns:
                    connection.execute(
                        "ALTER TABLE documents ADD COLUMN original_filename TEXT"
                    )
                chunk_columns = {
                    row["name"] for row in connection.execute("PRAGMA table_info(chunks)")
                }
                if "original_filename" not in chunk_columns:
                    connection.execute(
                        "ALTER TABLE chunks ADD COLUMN original_filename TEXT"
                    )
            self._initialized = True

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        self.initialize()
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.execute("COMMIT")
        except Exception:
            connection.execute("ROLLBACK")
            raise
        finally:
            connection.close()

    def _docstore_payload(self) -> str | None:
        self.initialize()
        with self._connect() as connection:
            row = connection.execute(
                "SELECT payload FROM state WHERE key='docstore'"
            ).fetchone()
        return str(row["payload"]) if row else None

    def load_docstore(self) -> SimpleDocumentStore:
        payload = self._docstore_payload()
        if payload is not None:
            return SimpleDocumentStore.from_dict(json.loads(payload))
        if self.legacy_docstore_path and self.legacy_docstore_path.exists():
            legacy = SimpleDocumentStore.from_persist_path(str(self.legacy_docstore_path))
            self.commit_docstore(legacy, nodes=legacy.docs.values())
            if self.legacy_parsed_dir:
                self.backfill_titles_from_markdown(self.legacy_parsed_dir)
            return legacy
        return SimpleDocumentStore()

    @staticmethod
    def _node_record(node: Any) -> tuple[Any, ...]:
        metadata = dict(getattr(node, "metadata", {}) or {})
        doc_id = str(
            getattr(node, "ref_doc_id", None)
            or metadata.get("doc_id")
            or metadata.get("document_id")
            or "unknown"
        )
        page = metadata.get("page_number") or metadata.get("page_label")
        try:
            page_number = int(page) if page is not None else None
        except (TypeError, ValueError):
            page_number = None
        return (
            str(node.node_id),
            doc_id,
            metadata.get("title"),
            metadata.get("original_filename"),
            page_number,
            metadata.get("source_uri"),
            metadata.get("content_sha256"),
            node.get_content(),
            json.dumps(metadata, ensure_ascii=False),
        )

    def commit_docstore(
        self,
        docstore: SimpleDocumentStore,
        *,
        replace_doc_id: str | None = None,
        nodes: Iterable[Any] = (),
        document_metadata: dict[str, Any] | None = None,
    ) -> None:
        payload = json.dumps(docstore.to_dict(), ensure_ascii=False)
        records = [self._node_record(node) for node in nodes]
        now = utc_now()
        with self.transaction() as connection:
            connection.execute(
                """
                INSERT INTO state(key, payload, revision, updated_at)
                VALUES('docstore', ?, 1, ?)
                ON CONFLICT(key) DO UPDATE SET
                    payload=excluded.payload,
                    revision=state.revision + 1,
                    updated_at=excluded.updated_at
                """,
                (payload, now),
            )
            if replace_doc_id:
                connection.execute("DELETE FROM chunks WHERE doc_id=?", (replace_doc_id,))
                connection.execute("DELETE FROM chunks_fts WHERE doc_id=?", (replace_doc_id,))
                if document_metadata is None:
                    connection.execute("DELETE FROM documents WHERE doc_id=?", (replace_doc_id,))
            for record in records:
                connection.execute(
                    """
                    INSERT OR REPLACE INTO chunks(
                        node_id, doc_id, title, original_filename, page_number, source_uri,
                        content_sha256, text, metadata_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record,
                )
                connection.execute(
                    "INSERT INTO chunks_fts(node_id, doc_id, search_text) VALUES (?, ?, ?)",
                    (record[0], record[1], bm25_tokens(f"{record[2] or ''} {record[7]}")),
                )
            if replace_doc_id is None and records:
                first_by_doc: dict[str, tuple[Any, ...]] = {}
                for record in records:
                    first_by_doc.setdefault(str(record[1]), record)
                for doc_id, record in first_by_doc.items():
                    metadata = json.loads(record[8])
                    connection.execute(
                        """
                        INSERT OR IGNORE INTO documents(
                            doc_id, title, original_filename, source_uri, content_sha256,
                            metadata_json, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            doc_id,
                            record[2],
                            record[3],
                            record[5],
                            record[6],
                            json.dumps(metadata, ensure_ascii=False),
                            now,
                        ),
                    )
            if replace_doc_id and document_metadata is not None:
                connection.execute(
                    """
                    INSERT INTO documents(
                        doc_id, title, original_filename, source_uri, content_sha256,
                        metadata_json, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(doc_id) DO UPDATE SET
                        title=excluded.title,
                        original_filename=excluded.original_filename,
                        source_uri=excluded.source_uri,
                        content_sha256=excluded.content_sha256,
                        metadata_json=excluded.metadata_json,
                        updated_at=excluded.updated_at
                    """,
                    (
                        replace_doc_id,
                        document_metadata.get("title"),
                        document_metadata.get("original_filename"),
                        document_metadata.get("source_uri"),
                        document_metadata.get("content_sha256"),
                        json.dumps(document_metadata, ensure_ascii=False),
                        now,
                    ),
                )

    def search_bm25(
        self,
        query: str,
        limit: int = 30,
        min_term_coverage: float = 0.0,
    ) -> list[dict[str, Any]]:
        terms = list(dict.fromkeys(bm25_tokens(query).split()))[:24]
        if not terms:
            return []
        expression = " OR ".join(f'"{term.replace(chr(34), chr(34) * 2)}"' for term in terms)
        self.initialize()
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT f.node_id, f.doc_id, c.title, c.page_number,
                       c.source_uri, c.content_sha256, c.text,
                       bm25(chunks_fts) AS rank
                FROM chunks_fts AS f
                JOIN chunks AS c ON c.node_id = f.node_id
                WHERE chunks_fts MATCH ?
                ORDER BY rank LIMIT ?
                """,
                (expression, max(1, min(limit * 4, 200))),
            ).fetchall()
        query_terms = set(terms)
        results = []
        for row in rows:
            result = dict(row)
            document_terms = set(
                bm25_tokens(f"{result.get('title') or ''} {result.get('text') or ''}").split()
            )
            coverage = len(query_terms & document_terms) / len(query_terms)
            if coverage < min_term_coverage:
                continue
            result["term_coverage"] = round(coverage, 6)
            results.append(result)
            if len(results) >= limit:
                break
        return results

    def list_documents(self) -> list[dict[str, Any]]:
        self.initialize()
        with self._connect() as connection:
            rows = connection.execute(
                """SELECT doc_id, title, original_filename, source_uri,
                          content_sha256, updated_at
                   FROM documents ORDER BY updated_at DESC"""
            ).fetchall()
        return [dict(row) for row in rows]

    def document_storage_counts(self, doc_id: str) -> dict[str, int]:
        """返回单文档在SQLite元数据、切块与FTS中的残留数量，用于删除校验。"""
        self.initialize()
        with self._connect() as connection:
            document_count = connection.execute(
                "SELECT COUNT(*) FROM documents WHERE doc_id=?", (doc_id,)
            ).fetchone()[0]
            chunk_count = connection.execute(
                "SELECT COUNT(*) FROM chunks WHERE doc_id=?", (doc_id,)
            ).fetchone()[0]
            fts_count = connection.execute(
                "SELECT COUNT(*) FROM chunks_fts WHERE doc_id=?", (doc_id,)
            ).fetchone()[0]
        return {
            "sqlite_documents_remaining": int(document_count),
            "sqlite_chunks_remaining": int(chunk_count),
            "sqlite_fts_remaining": int(fts_count),
        }

    def backfill_titles_from_markdown(self, parsed_dir: Path) -> int:
        """Enrich legacy rows whose original DocStore only contained doc_id."""
        self.initialize()
        changed = 0
        with self.transaction() as connection:
            rows = connection.execute(
                "SELECT doc_id FROM documents WHERE title IS NULL OR title=''"
            ).fetchall()
            for row in rows:
                path = parsed_dir / f"{row['doc_id']}.md"
                if not path.exists():
                    continue
                title = None
                for line in path.read_text(encoding="utf-8", errors="replace").splitlines()[:100]:
                    if line.startswith("# "):
                        title = line[2:].strip()[:300]
                        break
                if not title:
                    continue
                connection.execute(
                    "UPDATE documents SET title=? WHERE doc_id=?",
                    (title, row["doc_id"]),
                )
                connection.execute(
                    "UPDATE chunks SET title=? WHERE doc_id=? AND (title IS NULL OR title='')",
                    (title, row["doc_id"]),
                )
                changed += 1
        if changed:
            self.rebuild_bm25()
        return changed

    def rebuild_bm25(self) -> int:
        """Rebuild the generated FTS index from transactional chunk rows."""
        self.initialize()
        with self.transaction() as connection:
            rows = connection.execute(
                "SELECT node_id, doc_id, title, text FROM chunks"
            ).fetchall()
            connection.execute("DELETE FROM chunks_fts")
            connection.executemany(
                "INSERT INTO chunks_fts(node_id, doc_id, search_text) VALUES (?, ?, ?)",
                [
                    (
                        row["node_id"],
                        row["doc_id"],
                        bm25_tokens(f"{row['title'] or ''} {row['text']}"),
                    )
                    for row in rows
                ],
            )
        return len(rows)

    def status(self) -> dict[str, Any]:
        self.initialize()
        with self._connect() as connection:
            documents = connection.execute("SELECT COUNT(*) FROM documents").fetchone()[0]
            chunks = connection.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
            state = connection.execute(
                "SELECT revision, updated_at FROM state WHERE key='docstore'"
            ).fetchone()
        return {
            "database": str(self.path.resolve()),
            "documents": documents,
            "chunks": chunks,
            "docstore_revision": state["revision"] if state else 0,
            "docstore_updated_at": state["updated_at"] if state else None,
        }

    def save_task(self, task: dict[str, Any]) -> None:
        self.initialize()
        task = _task_for_storage(task)
        with self.transaction() as connection:
            connection.execute(
                """
                INSERT INTO parse_tasks(task_id, payload, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    payload=excluded.payload, updated_at=excluded.updated_at
                """,
                (
                    task["task_id"],
                    json.dumps(task, ensure_ascii=False),
                    task.get("updated_at") or utc_now(),
                ),
            )

    def migrate_legacy_tasks(self) -> int:
        """Import legacy parse_tasks.json once without modifying the backup."""
        self.initialize()
        with self._connect() as connection:
            done = connection.execute(
                "SELECT 1 FROM state WHERE key='legacy_tasks_migrated'"
            ).fetchone()
        if done:
            return 0
        try:
            legacy = (
                json.loads(self.legacy_task_path.read_text(encoding="utf-8"))
                if self.legacy_task_path and self.legacy_task_path.exists()
                else {}
            )
        except (OSError, json.JSONDecodeError):
            legacy = {}
        now = utc_now()
        with self.transaction() as connection:
            for task in legacy.values():
                task = _task_for_storage(task)
                connection.execute(
                    """
                    INSERT OR IGNORE INTO parse_tasks(task_id, payload, updated_at)
                    VALUES (?, ?, ?)
                    """,
                    (
                        task["task_id"],
                        json.dumps(task, ensure_ascii=False),
                        task.get("updated_at") or now,
                    ),
                )
            connection.execute(
                """
                INSERT INTO state(key, payload, revision, updated_at)
                VALUES('legacy_tasks_migrated', '{}', 1, ?)
                ON CONFLICT(key) DO NOTHING
                """,
                (now,),
            )
        return len(legacy)

    def scrub_stored_task_urls(self) -> int:
        """Remove expired query credentials from terminal task history."""
        self.initialize()
        changed = 0
        with self.transaction() as connection:
            rows = connection.execute("SELECT task_id, payload FROM parse_tasks").fetchall()
            for row in rows:
                task = json.loads(row["payload"])
                cleaned = _task_for_storage(task)
                if cleaned == task:
                    continue
                connection.execute(
                    "UPDATE parse_tasks SET payload=?, updated_at=? WHERE task_id=?",
                    (json.dumps(cleaned, ensure_ascii=False), utc_now(), row["task_id"]),
                )
                changed += 1
        return changed

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        self.migrate_legacy_tasks()
        with self._connect() as connection:
            row = connection.execute(
                "SELECT payload FROM parse_tasks WHERE task_id=?", (task_id,)
            ).fetchone()
        return json.loads(row["payload"]) if row else None

    def load_tasks(self) -> dict[str, dict[str, Any]]:
        self.migrate_legacy_tasks()
        with self._connect() as connection:
            rows = connection.execute("SELECT task_id, payload FROM parse_tasks").fetchall()
        return {str(row["task_id"]): json.loads(row["payload"]) for row in rows}

    def mark_interrupted_tasks(self) -> int:
        changed = 0
        for task in self.load_tasks().values():
            if task.get("status") not in {"queued", "running"}:
                continue
            task["status"] = "interrupted"
            task["updated_at"] = utc_now()
            task["error"] = "服务重启，后台任务已中断；请重新提交解析任务"
            for item in task.get("items", []):
                if item.get("status") in {"queued", "running"}:
                    item["status"] = "interrupted"
                    item["error"] = task["error"]
            self.save_task(task)
            changed += 1
        return changed


knowledge_store = SQLiteKnowledgeStore(
    DB_PATH,
    legacy_docstore_path=LEGACY_DOCSTORE_PATH,
    legacy_task_path=LEGACY_TASK_PATH,
    legacy_parsed_dir=LEGACY_PARSED_DIR,
)
