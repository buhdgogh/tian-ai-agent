"""MinerU 异步批量解析、SHA-256 去重缓存与自动增量索引工具。

调用方提交任务后立即获得本地 task_id；耗时的 MinerU 轮询、结果保存和
LlamaIndex 索引在后台线程执行。批量 Precision 模式使用 SDK 的
``extract_batch``，它在内部只提交一次 MinerU 批任务。

远程 URL 无法在不重复下载文件的情况下计算内容哈希。生产上传服务应在
接收文件时计算 SHA-256，并通过 ``content_sha256s`` 传入；本地文件会自动
计算内容哈希。没有内容哈希的 URL 只能退化为 URL 字符串指纹。
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import logging
import os
import ssl
import re
import shutil
import socket
import threading
import time
import uuid
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

from mineru.client import (
    ExtractResult,
    FileParam,
    MinerU,
    _parse_task_result,
    parse_zip,
)
import httpx

from mcp_server.instance import mcp
from mcp_server.api import error, ok
from mcp_server.storage import knowledge_store


_SERVER_DIR = Path(__file__).resolve().parents[1]
_runtime_config = Path(os.environ.get("DOCUMENT_RUNTIME_DIR", "runtime"))
_RUNTIME_DIR = _runtime_config if _runtime_config.is_absolute() else _SERVER_DIR / _runtime_config
_CACHE_DIR = _RUNTIME_DIR / "parse_cache"
_OSS_DOWNLOAD_DIR = _RUNTIME_DIR / "oss_downloads"
_parsed_config = Path(os.environ.get("PARSED_DIR", "parsed"))
_DEFAULT_OUTPUT_DIR = _parsed_config if _parsed_config.is_absolute() else _SERVER_DIR / _parsed_config
_MAX_BATCH_SIZE = 200
_MAX_REMOTE_SOURCE_BYTES = max(
    1, int(os.environ.get("REMOTE_SOURCE_MAX_BYTES", str(250 * 1024 * 1024)))
)
_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_NETWORK_RETRIES = max(0, int(os.environ.get("MINERU_NETWORK_RETRIES", "3")))
_NETWORK_RETRY_BASE_SECONDS = max(
    0.1, float(os.environ.get("MINERU_NETWORK_RETRY_BASE_SECONDS", "1"))
)
_PROXY_ENV_LOCK = threading.Lock()
_CLASH_FAKE_IP_NETWORK = ipaddress.ip_network("198.18.0.0/15")

logger = logging.getLogger(__name__)

_task_lock = threading.RLock()
_executor = ThreadPoolExecutor(
    max_workers=max(1, int(os.environ.get("DOCUMENT_PARSE_WORKERS", "2"))),
    thread_name_prefix="mineru-parse",
)

def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _token() -> str | None:
    return os.getenv("MINERU_TOKEN") or os.getenv("MINERU_API_TOKEN")


def _is_transient_network_error(exc: BaseException) -> bool:
    """识别可安全重试的 TLS、连接中断和超时异常。"""
    current: BaseException | None = exc
    visited: set[int] = set()
    while current is not None and id(current) not in visited:
        visited.add(id(current))
        if isinstance(
            current,
            (httpx.TransportError, ssl.SSLError, ConnectionError, TimeoutError),
        ):
            return True
        if isinstance(current, OSError) and getattr(current, "winerror", None) in {
            10053, 10054, 10060, 10061,
        }:
            return True
        message = str(current).lower()
        if any(
            marker in message
            for marker in (
                "unexpected_eof_while_reading",
                "connection reset",
                "connection aborted",
                "server disconnected",
                "timed out",
            )
        ):
            return True
        current = current.__cause__ or current.__context__
    return False


def _network_retry(operation: str, callback: Any) -> Any:
    """仅重试瞬时网络异常；鉴权、参数和 HTTP 4xx 错误立即失败。"""
    started = time.perf_counter()
    for attempt in range(_NETWORK_RETRIES + 1):
        try:
            result = callback()
            logger.info(
                "MinerU网络阶段完成 operation=%s attempts=%d elapsed_ms=%d",
                operation,
                attempt + 1,
                int((time.perf_counter() - started) * 1000),
            )
            return result
        except Exception as exc:
            transient = _is_transient_network_error(exc)
            if not transient:
                raise
            if attempt >= _NETWORK_RETRIES:
                raise ConnectionError(
                    "MinerU云端网络连接重试后仍失败；请检查DNS、代理或防火墙，"
                    "使用代理时配置 MINERU_PROXY_URL。"
                    f" operation={operation}, attempts={attempt + 1}, error={exc}"
                ) from exc
            delay = min(_NETWORK_RETRY_BASE_SECONDS * (2 ** attempt), 8.0)
            logger.warning(
                "MinerU网络调用失败，将在%.1f秒后重试 operation=%s attempt=%d/%d error=%s",
                delay,
                operation,
                attempt + 1,
                _NETWORK_RETRIES,
                exc,
            )
            time.sleep(delay)


@contextmanager
def _mineru_proxy_environment():
    """只在创建 MinerU HTTP 客户端时临时注入代理，不污染其他 SDK。"""
    proxy_url = _resolve_mineru_proxy()
    if not proxy_url:
        yield
        return

    names = ("HTTP_PROXY", "HTTPS_PROXY")
    with _PROXY_ENV_LOCK:
        previous = {name: os.environ.get(name) for name in names}
        try:
            for name in names:
                os.environ[name] = proxy_url
            yield
        finally:
            for name, value in previous.items():
                if value is None:
                    os.environ.pop(name, None)
                else:
                    os.environ[name] = value


def _resolve_mineru_proxy() -> str:
    """显式配置优先；检测到 Clash Fake-IP 时尝试本机 mixed-port。"""
    configured = os.environ.get("MINERU_PROXY_URL", "").strip()
    if configured:
        return configured
    if os.environ.get("MINERU_PROXY_AUTO_DETECT", "true").lower() not in {
        "1", "true", "yes", "on",
    }:
        return ""

    try:
        addresses = {
            ipaddress.ip_address(item[4][0])
            for item in socket.getaddrinfo("mineru.net", 443, type=socket.SOCK_STREAM)
        }
    except (OSError, ValueError):
        return ""
    if not any(address in _CLASH_FAKE_IP_NETWORK for address in addresses):
        return ""

    raw_ports = os.environ.get("MINERU_LOCAL_PROXY_PORTS", "7890")
    for raw_port in raw_ports.split(","):
        try:
            port = int(raw_port.strip())
            with socket.create_connection(("127.0.0.1", port), timeout=0.3):
                proxy_url = f"http://127.0.0.1:{port}"
                logger.warning(
                    "检测到 mineru.net 使用 Clash Fake-IP，自动启用本机代理 %s",
                    proxy_url,
                )
                return proxy_url
        except (OSError, ValueError):
            continue
    return ""


class _ResilientMinerU(MinerU):
    """在 SDK 的单次网络边界重试，避免轮询失败后重复提交整个任务。"""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        with _mineru_proxy_environment():
            super().__init__(*args, **kwargs)

    def _submit_urls_batch(self, *args: Any, **kwargs: Any) -> str:
        return _network_retry(
            "submit_urls_batch",
            lambda: super(_ResilientMinerU, self)._submit_urls_batch(
                *args, **kwargs
            ),
        )

    def _upload_and_submit(self, *args: Any, **kwargs: Any) -> str:
        return _network_retry(
            "upload_and_submit",
            lambda: super(_ResilientMinerU, self)._upload_and_submit(
                *args, **kwargs
            ),
        )

    def get_batch(self, batch_id: str) -> list[ExtractResult]:
        """分别重试状态查询和结果下载，避免把两类故障混为一谈。"""
        body = _network_retry(
            "get_batch_status",
            lambda: self._require_auth().get(
                f"/extract-results/batch/{batch_id}"
            ),
        )
        results: list[ExtractResult] = []
        for item in body["data"].get("extract_result", []):
            result = _parse_task_result(item)
            if result.state == "done" and result.zip_url:
                result = self._download_result_zip(result)
            results.append(result)
        return results

    def _download_result_zip(self, result: ExtractResult) -> ExtractResult:
        """下载结果包；对结果CDN分别尝试直连和MinerU代理。"""
        assert result.zip_url is not None
        host = urlparse(result.zip_url).hostname or "unknown-host"
        proxy_url = _resolve_mineru_proxy()
        routes: list[tuple[str, str | None]] = [("direct", None)]
        if proxy_url:
            routes.append(("proxy", proxy_url))

        errors: list[str] = []
        for route_name, route_proxy in routes:
            operation = f"download_result_zip[{route_name},host={host}]"

            def download_and_parse() -> ExtractResult:
                # trust_env=False 确保 direct 不会被 HTTP_PROXY 再次改写；
                # proxy 路线也只使用这里明确传入的代理。
                with httpx.Client(
                    proxy=route_proxy,
                    trust_env=False,
                    timeout=httpx.Timeout(30.0, read=300.0),
                    follow_redirects=True,
                    headers={"Accept": "application/zip, application/octet-stream, */*"},
                ) as download_client:
                    response = download_client.get(result.zip_url)
                    response.raise_for_status()
                    parsed = parse_zip(
                        response.content,
                        task_id=result.task_id,
                        filename=result.filename,
                    )
                    parsed.zip_url = result.zip_url
                    return parsed

            try:
                logger.info(
                    "下载MinerU结果包 host=%s route=%s", host, route_name
                )
                return _network_retry(operation, download_and_parse)
            except Exception as exc:
                errors.append(f"{route_name}: {exc}")
                logger.warning(
                    "MinerU结果包下载路线失败 host=%s route=%s error=%s",
                    host,
                    route_name,
                    exc,
                )

        raise ConnectionError(
            f"MinerU结果包下载失败 host={host}; " + " | ".join(errors)
        )

    def _flash_submit_url(self, *args: Any, **kwargs: Any) -> str:
        return _network_retry(
            "flash_submit_url",
            lambda: super(_ResilientMinerU, self)._flash_submit_url(
                *args, **kwargs
            ),
        )

    def _flash_submit_file(self, *args: Any, **kwargs: Any) -> str:
        return _network_retry(
            "flash_submit_file",
            lambda: super(_ResilientMinerU, self)._flash_submit_file(
                *args, **kwargs
            ),
        )

    def _flash_get_task(self, task_id: str) -> Any:
        return _network_retry(
            "flash_get_task",
            lambda: super(_ResilientMinerU, self)._flash_get_task(task_id),
        )


def _source_name(source: str) -> str:
    parsed = urlparse(source)
    raw_name = Path(unquote(parsed.path)).name if parsed.scheme else Path(source).name
    return raw_name or "remote-document"


def _index_metadata(item: dict[str, Any], markdown: str) -> dict[str, Any]:
    """Build stable document metadata without treating the OSS key as its name."""
    supplied = dict(item.get("document_metadata") or {})
    explicit_title = str(supplied.get("title") or "").strip()
    original_filename = str(
        supplied.get("original_filename")
        or supplied.get("originalFilename")
        or ""
    ).strip()
    title_match = re.search(r"^#\s+(.+)$", markdown, flags=re.MULTILINE)
    parsed_title = title_match.group(1).strip() if title_match else ""
    original_title = Path(_source_name(original_filename)).stem if original_filename else ""
    source_title = Path(_source_name(str(item["source"]))).stem

    metadata = supplied
    metadata["title"] = (
        explicit_title or parsed_title or original_title or source_title or str(item["doc_id"])
    )[:300]
    if original_filename:
        metadata["original_filename"] = original_filename[:500]
    metadata["source_uri"] = item.get("source_uri") or item["source"]
    metadata["content_sha256"] = item.get("content_sha256")
    return metadata


def _public_source_uri(source: str) -> str:
    """Remove expiring credentials from URLs retained as provenance metadata."""
    parsed = urlparse(source)
    if parsed.scheme in {"http", "https"}:
        return parsed._replace(query="", fragment="").geturl()
    try:
        return str(Path(source).resolve())
    except OSError:
        return source


def _public_task(task: dict[str, Any]) -> dict[str, Any]:
    """返回可通过MCP公开的任务副本，移除OSS查询参数中的临时凭据。"""
    public = json.loads(json.dumps(task))
    for item in public.get("items", []):
        source = str(item.get("source") or "")
        item["source"] = item.get("source_uri") or _public_source_uri(source)
    return public


def _scrub_persisted_task_sources(task_id: str) -> None:
    """任务结束后不再需要签名URL，将持久化任务中的source替换为脱敏来源。"""
    with _task_lock:
        task = knowledge_store.get_task(task_id)
        if task is None:
            return
        for item in task.get("items", []):
            item["source"] = item.get("source_uri") or _public_source_uri(
                str(item.get("source") or "")
            )
        task["updated_at"] = _now()
        knowledge_store.save_task(task)


def _download_remote_source(item: dict[str, Any], task_dir: Path) -> str:
    """由Python下载OSS预签名URL，校验大小和SHA-256后返回临时本地路径。"""
    source = str(item["source"])
    parsed = urlparse(source)
    if parsed.scheme not in {"http", "https"}:
        return source
    if parsed.scheme != "https":
        raise ValueError("远程知识库文档只允许使用HTTPS地址")

    suffix = Path(_source_name(source)).suffix
    target = task_dir / f"{item['item_id']}{suffix}"
    expected_sha256 = str(item.get("content_sha256") or "").lower()

    def download() -> str:
        task_dir.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        size = 0
        try:
            with httpx.Client(
                trust_env=False,
                timeout=httpx.Timeout(30.0, read=300.0),
                follow_redirects=False,
                headers={"Accept": "application/octet-stream, */*"},
            ) as client:
                with client.stream("GET", source) as response:
                    response.raise_for_status()
                    content_length = response.headers.get("Content-Length")
                    if content_length and int(content_length) > _MAX_REMOTE_SOURCE_BYTES:
                        raise ValueError("OSS文档超过Python允许的最大下载大小")
                    with target.open("wb") as output:
                        for chunk in response.iter_bytes(1024 * 1024):
                            size += len(chunk)
                            if size > _MAX_REMOTE_SOURCE_BYTES:
                                raise ValueError("OSS文档超过Python允许的最大下载大小")
                            digest.update(chunk)
                            output.write(chunk)
        except Exception:
            target.unlink(missing_ok=True)
            raise

        actual_sha256 = digest.hexdigest()
        if expected_sha256 and actual_sha256 != expected_sha256:
            target.unlink(missing_ok=True)
            raise ValueError(
                "OSS下载文档SHA-256校验失败: "
                f"doc_id={item['doc_id']}, expected={expected_sha256}, actual={actual_sha256}"
            )
        logger.info(
            "Python已从OSS下载并校验文档 doc_id=%s bytes=%d sha256=%s",
            item["doc_id"], size, actual_sha256,
        )
        return str(target)

    host = parsed.hostname or "unknown-host"
    return _network_retry(f"download_oss_source[host={host}]", download)


def _safe_stem(source: str) -> str:
    stem = Path(_source_name(source)).stem or "document"
    stem = re.sub(r'[^0-9A-Za-z._\-\u4e00-\u9fff]+', "_", stem).strip("._")
    return stem or "document"


"""
_cache_key 解决了如何唯一地标识一次“文档解析”操作。
精确缓存命中：系统缓存不仅是按文档内容存，还取决于你用什么参数去解析它。
例如，用户 A 用 {"mode": "ocr"} 解析 财报.pdf，用户 B 用 {"mode": "fast"} 解析同一个 财报.pdf，解析结果（如提取的文本质量、表格识别精度）会不同。
_cache_key 通过将 content_digest（文件内容）和 options（解析参数）一起纳入哈希计算，确保了这两种场景生成的缓存键不同。这样，系统就能为同一文档的不同解析方案，分别存储和复用正确的缓存结果。
保证缓存键的稳定性与一致性：
sort_keys=True 保证了无论 options 字典的键是 {"mode": "ocr", "lang": "zh"} 还是 {"lang": "zh", "mode": "ocr"}，生成的 JSON 字符串都是一样的，从而避免了因键顺序不同而产生不同的缓存键，浪费存储空间或造成缓存未命中。

这个函数是 MinerU 文档解析缓存机制的核心组成部分。其完整的工作流可能是这样的：
计算文档指纹：通过 _hash_local_file 计算文件的 SHA-256，得到 content_digest。
生成缓存键：调用 _cache_key(content_digest, options)，生成本次解析操作的唯一键 cache_key。
检查缓存：系统用 cache_key 去缓存存储中查找。如果存在，直接返回上次解析好的 Markdown 结果，跳过解析过程。
执行与存储：如果缓存未命中，则调用 MinerU 执行解析。解析完成后，将 cache_key 和解析结果一起存入缓存，供下次使用。
"""
def _hash_local_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _fingerprint(source: str, supplied: str | None) -> tuple[str, str]:
    """返回 SHA-256 和指纹类型（content/source）。"""
    if supplied:
        supplied = supplied.lower()
        if not _SHA256_RE.fullmatch(supplied):
            raise ValueError(f"{source} 的 content_sha256 必须是64位十六进制")
        return supplied, "content"

    parsed = urlparse(source)
    if parsed.scheme not in {"http", "https"}:
        path = Path(source)
        if path.is_file():
            return _hash_local_file(path), "content"

    # 仍使用 SHA-256 形成稳定缓存键，但它不能证明不同URL的内容相同。
    digest = hashlib.sha256(f"source:{source}".encode("utf-8")).hexdigest()
    return digest, "source"


def _cache_key(content_digest: str, options: dict[str, Any]) -> str:
    material = json.dumps(
        {"digest": content_digest, "parser": "mineru", "options": options},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()

"""
加载当前任务的状态：
    解析的文档可能比较大，缓存当前解析的状态，解析中？解析完成？解析到的页码
"""
def _get_task(task_id: str) -> dict[str, Any] | None:
    with _task_lock:
        task = knowledge_store.get_task(task_id)
        # JSON round-trip prevents callers from mutating shared nested values.
        return json.loads(json.dumps(task)) if task is not None else None


def _update_task(task_id: str, **changes: Any) -> dict[str, Any]:
    with _task_lock:
        task = knowledge_store.get_task(task_id)
        if task is None:
            raise KeyError(task_id)
        task.update(changes)
        task["updated_at"] = _now()
        knowledge_store.save_task(task)
        return json.loads(json.dumps(task))


def _update_item(task_id: str, item_id: str, **changes: Any) -> None:
    with _task_lock:
        task = knowledge_store.get_task(task_id)
        if task is None:
            raise KeyError(task_id)
        for item in task["items"]:
            if item["item_id"] == item_id:
                item.update(changes)
                break
        task["updated_at"] = _now()
        knowledge_store.save_task(task)


def _index_markdown(
    doc_id: str,
    markdown: str,
    metadata: dict[str, Any],
    content_list: list[dict[str, Any]] | None = None,
) -> tuple[int, dict[str, int]]:
    """延迟导入，避免模块初始化阶段形成循环依赖。"""
    from mcp_server.tools.llamaIndex_tools import _upsert_document_with_metrics

    _, leaf_count, timings = _upsert_document_with_metrics(
        doc_id,
        markdown,
        source_metadata=metadata,
        content_list=content_list,
    )
    return leaf_count, timings


def _finish_item(
    task_id: str,
    item: dict[str, Any],
    markdown: str,
    output_dir: Path,
    auto_index: bool,
    cache_hit: bool,
    content_list: list[dict[str, Any]] | None = None,
) -> None:
    item_started = time.perf_counter()
    timings: dict[str, Any] = {}
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{item['doc_id']}.md"
    markdown_started = time.perf_counter()
    output_path.write_text(markdown, encoding="utf-8")
    timings["result_markdown_write_ms"] = int(
        (time.perf_counter() - markdown_started) * 1000
    )

    leaf_count = None
    if auto_index:
        metadata = _index_metadata(item, markdown)
        leaf_count, index_timings = _index_markdown(
            item["doc_id"], markdown, metadata, content_list
        )
        timings["index"] = index_timings
    timings["finish_item_total_ms"] = int(
        (time.perf_counter() - item_started) * 1000
    )

    _update_item(
        task_id,
        item["item_id"],
        status="completed",
        cache_hit=cache_hit,
        output_path=str(output_path.resolve()),
        indexed=auto_index,
        leaf_node_count=leaf_count,
        timings=timings,
        error=None,
    )


def _result_item(result: Any, pending: list[dict[str, Any]]) -> dict[str, Any]:
    """用 data_id 优先、文件名次之，把批量结果映射回提交项。"""
    data_id = getattr(result, "data_id", None)
    if data_id:
        for item in pending:
            if item["item_id"] == data_id:
                return item

    filename = Path(str(getattr(result, "filename", ""))).name
    matches = [
        item for item in pending
        if _source_name(item.get("_parse_source", item["source"])) == filename
    ]
    if len(matches) == 1:
        return matches[0]
    if len(pending) == 1:
        return pending[0]
    raise RuntimeError(f"无法将 MinerU 批量结果 {filename!r} 映射回原始文件")


def _run_parse_task(task_id: str) -> None:
    total_started = time.perf_counter()
    task = _get_task(task_id)
    if task is None:
        return

    created_at = datetime.fromisoformat(task["created_at"])
    timings: dict[str, int] = {
        "python_queue_wait_ms": max(
            0, int((datetime.now(timezone.utc) - created_at).total_seconds() * 1000)
        ),
        "batch_document_count": len(task["items"]),
    }
    _update_task(task_id, status="running", error=None, timings=timings)
    output_dir = Path(task["output_dir"])
    options = task["options"]
    task_download_dir = _OSS_DOWNLOAD_DIR / task_id

    try:
        # 同一缓存键只解析一次，其余项目复用结果。
        cache_started = time.perf_counter()
        groups: dict[str, list[dict[str, Any]]] = {}
        for item in task["items"]:
            groups.setdefault(item["cache_key"], []).append(item)

        representatives: list[dict[str, Any]] = []
        for key, items in groups.items():
            cache_file = _CACHE_DIR / f"{key}.md"
            if cache_file.exists():
                markdown = cache_file.read_text(encoding="utf-8")
                content_file = _CACHE_DIR / f"{key}.content.json"
                try:
                    content_list = json.loads(content_file.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError):
                    content_list = None
                for item in items:
                    _finish_item(
                        task_id, item, markdown, output_dir,
                        task["auto_index"], cache_hit=True,
                        content_list=content_list,
                    )
            else:
                representatives.append(items[0])
        timings["cache_lookup_and_hits_ms"] = int(
            (time.perf_counter() - cache_started) * 1000
        )
        timings["cache_hit_documents"] = sum(
            len(items) for key, items in groups.items()
            if (_CACHE_DIR / f"{key}.md").exists()
        )

        if representatives:
            token = _token()
            mode = options["mode"]
            use_precision = mode == "precision" or (mode == "auto" and bool(token))
            if mode == "precision" and not token:
                raise ValueError("Precision模式需要配置 MINERU_TOKEN")
            if len(representatives) > 1 and not use_precision:
                raise ValueError("MinerU批量API需要Precision模式和MINERU_TOKEN；Flash只支持单文件")

            download_started = time.perf_counter()
            for item in representatives:
                item["_parse_source"] = _download_remote_source(item, task_download_dir)
            sources = [item["_parse_source"] for item in representatives]
            timings["python_oss_download_ms"] = int(
                (time.perf_counter() - download_started) * 1000
            )
            mineru_started = time.perf_counter()
            with (
                _ResilientMinerU(token)
                if use_precision
                else _ResilientMinerU()
            ) as client:
                if use_precision:
                    file_params = {
                        item["source"]: FileParam(data_id=item["item_id"])
                        for item in representatives
                    }
                    results = client.extract_batch(
                        sources,
                        model=options["model"],
                        ocr=options["ocr"],
                        formula=options["formula"],
                        table=options["table"],
                        language=options["language"],
                        file_params=file_params,
                    )
                else:
                    # 单文件Flash仍在后台执行，保持对MCP调用方异步。
                    results = [
                        client.flash_extract(
                            sources[0],
                            language=options["language"],
                            is_ocr=options["ocr"],
                            enable_formula=options["formula"],
                            enable_table=options["table"],
                        )
                    ]
                timings["mineru_remote_parse_download_ms"] = int(
                    (time.perf_counter() - mineru_started) * 1000
                )

                result_started = time.perf_counter()
                pending = list(representatives)
                for result in results:
                    item = _result_item(result, pending)
                    pending.remove(item)
                    state = str(getattr(result, "state", "done")).lower()
                    markdown = getattr(result, "markdown", None)
                    if state == "failed" or not markdown:
                        error = getattr(result, "error", None) or "MinerU未返回Markdown"
                        for grouped_item in groups[item["cache_key"]]:
                            _update_item(
                                task_id,
                                grouped_item["item_id"],
                                status="failed",
                                error=str(error),
                            )
                        continue

                    _CACHE_DIR.mkdir(parents=True, exist_ok=True)
                    (_CACHE_DIR / f"{item['cache_key']}.md").write_text(
                        markdown, encoding="utf-8"
                    )
                    content_list = getattr(result, "content_list", None)
                    if content_list:
                        (_CACHE_DIR / f"{item['cache_key']}.content.json").write_text(
                            json.dumps(content_list, ensure_ascii=False), encoding="utf-8"
                        )
                    for grouped_item in groups[item["cache_key"]]:
                        _finish_item(
                            task_id, grouped_item, markdown, output_dir,
                            task["auto_index"], cache_hit=False,
                            content_list=content_list,
                        )

                for item in pending:
                    for grouped_item in groups[item["cache_key"]]:
                        _update_item(
                            task_id,
                            grouped_item["item_id"],
                            status="failed",
                            error="MinerU批任务未返回该文件的结果",
                        )
                timings["result_cache_and_index_ms"] = int(
                    (time.perf_counter() - result_started) * 1000
                )
        else:
            timings["mineru_remote_parse_download_ms"] = 0

        final_task = _get_task(task_id)
        assert final_task is not None
        failed = [item for item in final_task["items"] if item["status"] == "failed"]
        status = "completed_with_errors" if failed else "completed"
        timings["python_task_total_ms"] = int(
            (time.perf_counter() - total_started) * 1000
        )
        _update_task(task_id, status=status, timings=timings)
        logger.info(
            "MinerU批量解析索引完成 task_id=%s documents=%d timings=%s",
            task_id,
            len(task["items"]),
            timings,
        )
    except Exception as exc:
        current = _get_task(task_id)
        if current is not None:
            for item in current["items"]:
                if item["status"] in {"queued", "running"}:
                    _update_item(
                        task_id, item["item_id"], status="failed", error=str(exc)
                    )
        timings["python_task_total_ms"] = int(
            (time.perf_counter() - total_started) * 1000
        )
        _update_task(task_id, status="failed", error=str(exc), timings=timings)
        logger.exception(
            "MinerU批量解析索引失败 task_id=%s timings=%s", task_id, timings
        )
    finally:
        _scrub_persisted_task_sources(task_id)
        # OSS原文只在解析期间落临时盘；解析结果和索引按既有策略持久化。
        shutil.rmtree(task_download_dir, ignore_errors=True)


def _submit_parse_task(
    file_sources: list[str],
    output_dir: str | None,
    content_sha256s: dict[str, str] | None,
    doc_ids: dict[str, str] | None,
    document_metadatas: dict[str, dict[str, Any]] | None,
    model: str,
    ocr: bool,
    formula: bool,
    table: bool,
    language: str,
    auto_index: bool,
    mode: str,
) -> dict[str, Any]:
    if not file_sources:
        raise ValueError("file_sources不能为空")
    if len(file_sources) > _MAX_BATCH_SIZE:
        raise ValueError(f"单批最多 {_MAX_BATCH_SIZE} 个文件")
    if len(set(file_sources)) != len(file_sources):
        raise ValueError("同一批次不能包含重复的file_source")
    if model not in {"vlm", "pipeline", "html"}:
        raise ValueError("model必须是 vlm、pipeline 或 html")
    if mode not in {"auto", "precision", "flash"}:
        raise ValueError("mode必须是 auto、precision 或 flash")

    options = {
        "mode": mode,
        "model": model,
        "ocr": ocr,
        "formula": formula,
        "table": table,
        "language": language,
    }
    hashes = content_sha256s or {}
    requested_doc_ids = doc_ids or {}
    supplied_metadata = document_metadatas or {}
    items = []
    for source in file_sources:
        digest, fingerprint_kind = _fingerprint(source, hashes.get(source))
        key = _cache_key(digest, options)
        requested_doc_id = requested_doc_ids.get(source)
        items.append(
            {
                "item_id": str(uuid.uuid4()),
                "source": source,
                "source_uri": _public_source_uri(source),
                "doc_id": (
                    _safe_stem(requested_doc_id)
                    if requested_doc_id
                    else f"{_safe_stem(source)}-{digest[:12]}"
                ),
                "content_sha256": digest,
                "document_metadata": dict(supplied_metadata.get(source) or {}),
                "fingerprint_kind": fingerprint_kind,
                "cache_key": key,
                "status": "queued",
                "cache_hit": False,
                "indexed": False,
                "output_path": None,
                "timings": {},
                "error": None,
            }
        )

    task_id = str(uuid.uuid4())
    task = {
        "task_id": task_id,
        "status": "queued",
        "created_at": _now(),
        "updated_at": _now(),
        "output_dir": str(Path(output_dir) if output_dir else _DEFAULT_OUTPUT_DIR),
        "auto_index": auto_index,
        "options": options,
        "items": items,
        "timings": {},
        "error": None,
    }
    with _task_lock:
        knowledge_store.save_task(task)

    _executor.submit(_run_parse_task, task_id)
    return task


@mcp.tool()
def parse_document(
    file_source: str,
    doc_id: str | None = None,
    mode: str = "auto",
    content_sha256: str | None = None,
    document_metadata: dict[str, Any] | None = None,
    model: str = "vlm",
    ocr: bool = False,
    formula: bool = True,
    table: bool = True,
    language: str = "ch",
    auto_index: bool = True,
) -> dict[str, Any]:
    """异步提交单个文档，立即返回task_id，不等待解析和索引完成。"""
    hashes = {file_source: content_sha256} if content_sha256 else None
    doc_ids = {file_source: doc_id} if doc_id else None
    task = _submit_parse_task(
        [file_source], None, hashes, doc_ids,
        {file_source: document_metadata} if document_metadata else None,
        model, ocr, formula, table, language,
        auto_index, mode,
    )
    return ok(_public_task(task))


@mcp.tool()
def parse_documents_batch(
    file_sources: list[str],
    output_dir: str | None = None,
    mode: str = "auto",
    content_sha256s: dict[str, str] | None = None,
    doc_ids: dict[str, str] | None = None,
    document_metadatas: dict[str, dict[str, Any]] | None = None,
    model: str = "vlm",
    ocr: bool = False,
    formula: bool = True,
    table: bool = True,
    language: str = "ch",
    auto_index: bool = True,
) -> dict[str, Any]:
    """异步提交MinerU批量任务；多文件需要配置MINERU_TOKEN。"""
    task = _submit_parse_task(
        file_sources,
        output_dir,
        content_sha256s,
        doc_ids,
        document_metadatas,
        model,
        ocr,
        formula,
        table,
        language,
        auto_index,
        mode,
    )
    return ok(_public_task(task))


@mcp.tool()
def get_parse_task(task_id: str) -> dict[str, Any]:
    """查询异步解析、缓存和增量索引进度。"""
    task = _get_task(task_id)
    if task is None:
        return error("task_not_found", "解析任务不存在", task_id=task_id)
    return ok(_public_task(task))
