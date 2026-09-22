from mcp_server.tools.llamaIndex_tools import (
    _delete_and_verify_redis_document,
    _redis_document_keys,
)


class _SearchDocument:
    def __init__(self, node_id):
        self.id = node_id


class _SearchResult:
    def __init__(self, node_ids):
        self.docs = [_SearchDocument(node_id) for node_id in node_ids]
        self.total = len(node_ids)


class _FakeSearch:
    def __init__(self, node_ids):
        self.node_ids = node_ids

    def search(self, query):
        return _SearchResult(self.node_ids)


class FakePipeline:
    def __init__(self, client):
        self.client = client
        self.operations = []

    def hget(self, key, field):
        self.operations.append(("hget", key, field))
        return self

    def unlink(self, *keys):
        self.operations.append(("unlink", keys))
        return self

    def execute(self):
        results = []
        for operation in self.operations:
            if operation[0] == "hget":
                _, key, field = operation
                results.append(self.client.hashes.get(key, {}).get(field))
            else:
                _, keys = operation
                results.append(self.client.unlink(*keys))
        self.operations.clear()
        return results


class FakeVectorStore:
    def __init__(self, client):
        self.client = client
        self.deleted_doc_ids = []

    def delete(self, doc_id):
        self.deleted_doc_ids.append(doc_id)
        # 模拟当前依赖版本最多先删除一条，剩余键由Pipeline补齐。
        for key, value in list(self.client.hashes.items()):
            if value.get("doc_id") == doc_id.encode():
                self.client.unlink(key)
                break


class FakeRedis:
    def __init__(self):
        self.hashes = {
            b"knowledge_base/vector_1": {"doc_id": b"doc-1"},
            b"knowledge_base/vector_2": {"doc_id": b"doc-2"},
            b"knowledge_base/vector_3": {"doc_id": b"doc-1"},
        }
        self.closed = False

    def scan_iter(self, match, count):
        assert match == "knowledge_base/vector_*"
        return iter(list(self.hashes))

    def pipeline(self, transaction=False):
        assert transaction is False
        return FakePipeline(self)

    def unlink(self, *keys):
        deleted = 0
        for key in keys:
            if self.hashes.pop(key, None) is not None:
                deleted += 1
        return deleted

    def close(self):
        self.closed = True


class IndexedFakeRedis(FakeRedis):
    def ft(self, index_name):
        assert index_name == "knowledge_base"
        return _FakeSearch([
            "knowledge_base/vector_1",
            "knowledge_base/vector_3",
        ])

    def scan_iter(self, match, count):
        raise AssertionError("已有doc_id索引时不应执行全量SCAN")


def test_document_key_scan_matches_exact_doc_id():
    client = FakeRedis()

    assert _redis_document_keys(client, "doc-1") == [
        b"knowledge_base/vector_1",
        b"knowledge_base/vector_3",
    ]


def test_document_key_lookup_prefers_redisearch_index():
    client = IndexedFakeRedis()

    assert _redis_document_keys(client, "doc-1") == [
        "knowledge_base/vector_1",
        "knowledge_base/vector_3",
    ]


def test_delete_unlinks_all_nodes_and_verifies(monkeypatch):
    client = FakeRedis()
    vector_store = FakeVectorStore(client)
    monkeypatch.setattr(
        "mcp_server.tools.llamaIndex_tools._redis_client", lambda: client
    )
    monkeypatch.setattr(
        "mcp_server.tools.llamaIndex_tools._make_vector_store",
        lambda overwrite=False: vector_store,
    )

    result = _delete_and_verify_redis_document("doc-1")

    assert result["redis_nodes_found"] == 2
    assert result["redis_nodes_deleted"] == 2
    assert result["redis_nodes_remaining"] == 0
    assert result["verified"] is True
    assert vector_store.deleted_doc_ids == ["doc-1"]
    assert "redis_pipeline_delete_ms" in result
    assert b"knowledge_base/vector_2" in client.hashes
    assert client.closed is True
