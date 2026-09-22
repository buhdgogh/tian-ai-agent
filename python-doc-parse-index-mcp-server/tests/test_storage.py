from pathlib import Path

from llama_index.core import Document
from llama_index.core.storage.docstore import SimpleDocumentStore

from mcp_server.storage import SQLiteKnowledgeStore, bm25_tokens


def make_store(path: Path) -> SQLiteKnowledgeStore:
    return SQLiteKnowledgeStore(path)


def test_mixed_language_bm25_tokens() -> None:
    tokens = bm25_tokens("三维地质建模 Attention-PointNet++")
    assert "三维" in tokens
    assert "地质" in tokens
    assert "attention-pointnet++" in tokens


def test_docstore_and_bm25_are_committed_together(tmp_path: Path) -> None:
    store = make_store(tmp_path / "knowledge.db")
    docstore = SimpleDocumentStore()
    document = Document(
        text="使用虚拟钻孔和地层层序完成三维地质建模",
        id_="node-1",
        metadata={
            "doc_id": "paper-1",
            "title": "构造地质图建模",
            "original_filename": "构造地质图建模论文.pdf",
            "source_uri": "file:///paper.pdf",
            "content_sha256": "a" * 64,
        },
    )
    docstore.add_documents([document])

    store.commit_docstore(
        docstore,
        replace_doc_id="paper-1",
        nodes=[document],
        document_metadata=document.metadata,
    )

    loaded = store.load_docstore()
    assert loaded.get_node("node-1").get_content() == document.get_content()
    assert store.search_bm25("虚拟钻孔")[0]["node_id"] == "node-1"
    assert store.list_documents()[0]["content_sha256"] == "a" * 64
    assert store.list_documents()[0]["original_filename"] == "构造地质图建模论文.pdf"


def test_task_round_trip(tmp_path: Path) -> None:
    store = make_store(tmp_path / "knowledge.db")
    task = {
        "task_id": "task-1",
        "status": "queued",
        "updated_at": "2026-09-03T00:00:00+00:00",
        "items": [],
    }
    store.save_task(task)
    assert store.get_task("task-1") == task


def test_replacing_document_removes_old_bm25_rows(tmp_path: Path) -> None:
    store = make_store(tmp_path / "knowledge.db")
    first_store = SimpleDocumentStore()
    first = Document(text="旧内容 金矿", id_="old-node", metadata={"doc_id": "d1"})
    first_store.add_documents([first])
    store.commit_docstore(
        first_store,
        replace_doc_id="d1",
        nodes=[first],
        document_metadata={"doc_id": "d1", "title": "旧文档"},
    )

    second_store = SimpleDocumentStore()
    second = Document(text="新内容 隧道", id_="new-node", metadata={"doc_id": "d1"})
    second_store.add_documents([second])
    store.commit_docstore(
        second_store,
        replace_doc_id="d1",
        nodes=[second],
        document_metadata={"doc_id": "d1", "title": "新文档"},
    )

    assert store.search_bm25("金矿") == []
    assert store.search_bm25("隧道")[0]["node_id"] == "new-node"


def test_document_storage_counts_reach_zero_after_delete(tmp_path: Path) -> None:
    store = make_store(tmp_path / "knowledge.db")
    docstore = SimpleDocumentStore()
    document = Document(text="待删除内容", id_="node-delete", metadata={"doc_id": "d-delete"})
    docstore.add_documents([document])
    store.commit_docstore(
        docstore,
        replace_doc_id="d-delete",
        nodes=[document],
        document_metadata={"doc_id": "d-delete", "title": "待删除文档"},
    )

    assert store.document_storage_counts("d-delete") == {
        "sqlite_documents_remaining": 1,
        "sqlite_chunks_remaining": 1,
        "sqlite_fts_remaining": 1,
    }

    docstore.delete_ref_doc("d-delete", raise_error=False)
    store.commit_docstore(docstore, replace_doc_id="d-delete")

    assert store.document_storage_counts("d-delete") == {
        "sqlite_documents_remaining": 0,
        "sqlite_chunks_remaining": 0,
        "sqlite_fts_remaining": 0,
    }


def test_legacy_json_is_imported_without_being_deleted(tmp_path: Path) -> None:
    legacy_path = tmp_path / "docstore.json"
    legacy = SimpleDocumentStore()
    document = Document(text="legacy knowledge", id_="legacy-node")
    legacy.add_documents([document])
    legacy.persist(persist_path=str(legacy_path))

    store = SQLiteKnowledgeStore(
        tmp_path / "knowledge.db",
        legacy_docstore_path=legacy_path,
    )
    loaded = store.load_docstore()

    assert loaded.get_node("legacy-node").get_content() == "legacy knowledge"
    assert legacy_path.exists()
    assert store.status()["docstore_revision"] == 1
