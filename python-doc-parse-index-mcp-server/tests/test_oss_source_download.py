import hashlib

from mcp_server.tools import mineru_tools


class FakeResponse:
    def __init__(self, payload: bytes):
        self.payload = payload
        self.headers = {"Content-Length": str(len(payload))}

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def raise_for_status(self):
        return None

    def iter_bytes(self, chunk_size):
        yield self.payload


class FakeClient:
    def __init__(self, payload: bytes, **kwargs):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def stream(self, method, url):
        assert method == "GET"
        assert url.startswith("https://bucket.oss.example/")
        return FakeResponse(self.payload)


def test_python_downloads_oss_source_and_verifies_sha256(tmp_path, monkeypatch):
    payload = b"medical-document"
    monkeypatch.setattr(
        mineru_tools.httpx,
        "Client",
        lambda **kwargs: FakeClient(payload, **kwargs),
    )
    item = {
        "item_id": "item-1",
        "doc_id": "doc-1",
        "source": "https://bucket.oss.example/knowledge-base/doc-1.pdf?signature=x",
        "content_sha256": hashlib.sha256(payload).hexdigest(),
    }

    local_source = mineru_tools._download_remote_source(item, tmp_path)

    assert local_source != item["source"]
    assert mineru_tools.Path(local_source).read_bytes() == payload


def test_public_task_removes_presigned_url_credentials():
    task = {
        "task_id": "task-1",
        "items": [{
            "source": "https://bucket.oss.example/doc.pdf?OSSAccessKeyId=x&Signature=y",
            "source_uri": "https://bucket.oss.example/doc.pdf",
        }],
    }

    public = mineru_tools._public_task(task)

    assert public["items"][0]["source"] == "https://bucket.oss.example/doc.pdf"
    assert "OSSAccessKeyId" in task["items"][0]["source"]


def test_original_filename_is_used_when_markdown_has_no_title():
    item = {
        "doc_id": "uuid-doc",
        "source": "https://bucket.oss.example/knowledge-base/uuid-doc.pdf?signature=x",
        "source_uri": "https://bucket.oss.example/knowledge-base/uuid-doc.pdf",
        "content_sha256": "a" * 64,
        "document_metadata": {"original_filename": "阿达木单抗说明书.pdf"},
    }

    metadata = mineru_tools._index_metadata(item, "这里是没有一级标题的正文")

    assert metadata["title"] == "阿达木单抗说明书"
    assert metadata["original_filename"] == "阿达木单抗说明书.pdf"


def test_parsed_title_has_priority_over_original_filename():
    item = {
        "doc_id": "uuid-doc",
        "source": "https://bucket.oss.example/knowledge-base/uuid-doc.pdf",
        "document_metadata": {"original_filename": "上传文件.pdf"},
    }

    metadata = mineru_tools._index_metadata(item, "# 文档内正式标题\n\n正文")

    assert metadata["title"] == "文档内正式标题"
    assert metadata["original_filename"] == "上传文件.pdf"
