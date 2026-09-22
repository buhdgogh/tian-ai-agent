"""LlamaIndex RAG 工具：父子分块、Redis 向量和文档级增量索引。

存储分工：
- RedisVectorStore 保存叶子节点向量与文本；
- 本地 docstore 保存全部父子节点，供叶子重排完成后的回答上下文扩展；
- 每次新增/更新只删除并重建一个 ref_doc_id，不再清空整个知识库。

环境变量：DASHSCOPE_API_KEY、DASHSCOPE_WORKSPACE_ID、
DASHSCOPE_RERANK_ENDPOINT、REDIS_URL、REDIS_INDEX_NAME、
REDIS_INDEX_PREFIX、REDIS_VECTOR_DIMS、PARSED_DIR、PERSIST_DIR。
"""

from __future__ import annotations

import json
import logging
import os
import re
import threading
import time
import urllib.error
import urllib.request
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mcp_server.instance import mcp
from mcp_server.api import error, ok
from mcp_server.storage import DB_PATH, knowledge_store

from llama_index.core import (
    Document,
    Settings,
    StorageContext,
    VectorStoreIndex,
)
from llama_index.core.node_parser import HierarchicalNodeParser, get_leaf_nodes
from llama_index.core.schema import MetadataMode, NodeRelationship, NodeWithScore
from llama_index.core.storage.docstore import SimpleDocumentStore
from llama_index.embeddings.dashscope import DashScopeEmbedding
from llama_index.llms.dashscope import DashScope
from llama_index.vector_stores.redis import RedisVectorStore
from redis import Redis
from redis.commands.search.query import Query
from redis.exceptions import RedisError
from redisvl.query.filter import Tag
from redisvl.schema import IndexSchema


CHUNK_SIZES = [int(value) for value in os.getenv("CHUNK_SIZES", "4096,1024,256").split(",")]
RETRIEVAL_TOP_K = int(os.getenv("RETRIEVAL_TOP_K", "15"))
RERANK_TOP_N = int(os.getenv("RERANK_TOP_N", "5"))
RERANK_MODEL = os.getenv("RERANK_MODEL", "qwen3.7-text-rerank")
MAX_CANDIDATES_PER_DOC = 4
NEIGHBOR_SCORE_DECAY = 0.92
MMR_RELEVANCE_WEIGHT = 0.78
RERANK_TIMEOUT_SECONDS = 30
MAX_RERANK_CHARS_PER_DOCUMENT = 12000
RERANK_TOTAL_CHAR_BUDGET = 300000
QA_MAX_OUTPUT_TOKENS = int(os.getenv("QA_MAX_OUTPUT_TOKENS", "800"))
OVERVIEW_MAX_OUTPUT_TOKENS = int(os.getenv("OVERVIEW_MAX_OUTPUT_TOKENS", "8192"))
MIN_VECTOR_SCORE = float(os.getenv("MIN_VECTOR_SCORE", "0.15"))
MIN_RERANK_SCORE = float(os.getenv("MIN_RERANK_SCORE", "0.10"))
MIN_BM25_TERM_COVERAGE = float(os.getenv("MIN_BM25_TERM_COVERAGE", "0.20"))
HYBRID_DENSE_WEIGHT = float(os.getenv("HYBRID_DENSE_WEIGHT", "0.65"))
HYBRID_BM25_WEIGHT = float(os.getenv("HYBRID_BM25_WEIGHT", "0.35"))
EXACT_ENTITY_BOOST = float(os.getenv("EXACT_ENTITY_BOOST", "0.30"))
SECTION_MATCH_BOOST = float(os.getenv("SECTION_MATCH_BOOST", "0.15"))
QUERY_REWRITE_ENABLED = os.getenv("QUERY_REWRITE_ENABLED", "true").lower() == "true"
QUERY_REWRITE_COUNT = max(0, min(int(os.getenv("QUERY_REWRITE_COUNT", "2")), 4))
LOG_RETRIEVAL_CHUNKS = os.getenv("LOG_RETRIEVAL_CHUNKS", "true").lower() == "true"
ANSWER_PLANNING_MODE = os.getenv("ANSWER_PLANNING_MODE", "adaptive").lower()
ANSWER_VALIDATION_MODE = os.getenv("ANSWER_VALIDATION_MODE", "high_risk").lower()
MAX_SOURCE_CHARS = 1600
MAX_CONTEXT_CHARS_PER_SOURCE = 5000
MAX_CONTEXT_TOTAL_CHARS = int(os.getenv("MAX_CONTEXT_TOTAL_CHARS", "12000"))
EVIDENCE_PARENT_MAX_CHARS = int(os.getenv("EVIDENCE_PARENT_MAX_CHARS", "3000"))
EVIDENCE_NEIGHBOR_WINDOW = max(0, int(os.getenv("EVIDENCE_NEIGHBOR_WINDOW", "0")))
MAX_OVERVIEW_DOCS = 20
MAX_OVERVIEW_CHARS_PER_DOC = 5000
_MARKDOWN_IMAGE_RE = re.compile(r"!?\[[^\]]*\]\([^)]*\)")
_PROVENANCE_METADATA_KEYS = (
    "doc_id",
    "title",
    "original_filename",
    "source_uri",
    "content_sha256",
    "page_number",
    "page_start",
    "page_end",
    "char_start",
    "char_end",
    "block_order",
)
logger = logging.getLogger(__name__)


class _ReadWriteLock:
    """写优先读写锁：查询可并发，索引提交保持原子。"""

    def __init__(self) -> None:
        self._condition = threading.Condition(threading.Lock())
        self._readers = 0
        self._writer = False
        self._waiting_writers = 0

    @contextmanager
    def read(self):
        with self._condition:
            while self._writer or self._waiting_writers:
                self._condition.wait()
            self._readers += 1
        try:
            yield
        finally:
            with self._condition:
                self._readers -= 1
                if self._readers == 0:
                    self._condition.notify_all()

    @contextmanager
    def write(self):
        with self._condition:
            self._waiting_writers += 1
            try:
                while self._writer or self._readers:
                    self._condition.wait()
                self._writer = True
            finally:
                self._waiting_writers -= 1
        try:
            yield
        finally:
            with self._condition:
                self._writer = False
                self._condition.notify_all()

@dataclass
class RetrievalOutcome:
    nodes: list[NodeWithScore]
    evidence_contexts: list[dict[str, object]]
    mode: str
    queries: list[str]
    dense_candidates: int
    bm25_candidates: int
    query_profile: dict[str, list[str]]
    timings: dict[str, int]
    rejected_by_threshold: bool = False


def _is_task_payload(text: str) -> bool:
    """识别曾被错误当作正文索引的异步任务 JSON。"""
    if not text.lstrip().startswith("{"):
        return False
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return False
    return isinstance(payload, dict) and {"task_id", "status", "items"}.issubset(payload)


def _clean_markdown_images(text: str) -> str:
    text = _MARKDOWN_IMAGE_RE.sub("", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def _is_meaningful_text(text: str) -> bool:
    if _is_task_payload(text):
        return False
    cleaned = _clean_markdown_images(text)
    alphanumeric = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff]+", "", cleaned)
    return len(alphanumeric) >= 60


def _filter_retrieved_nodes(nodes: list[NodeWithScore]) -> list[NodeWithScore]:
    """排除无效片段，以及非当前有效/未审核的医疗证据。"""
    return [
        node for node in nodes
        if _is_meaningful_text(node.node.get_content())
        and _is_approved_medical_metadata(dict(node.node.metadata or {}))
    ]


def _is_approved_medical_metadata(metadata: dict[str, object]) -> bool:
    return (
        str(metadata.get("effectiveStatus") or metadata.get("effective_status") or "").upper() == "ACTIVE"
        and str(metadata.get("reviewStatus") or metadata.get("review_status") or "").upper() == "APPROVED"
        and bool(metadata.get("issuer"))
        and bool(metadata.get("version"))
        and bool(metadata.get("publishedAt") or metadata.get("published_at"))
        and bool(metadata.get("audience"))
    )


def _node_doc_id(node: object) -> str:
    return str(
        getattr(node, "ref_doc_id", None)
        or getattr(node, "metadata", {}).get("doc_id")
        or getattr(node, "metadata", {}).get("document_id")
        or "unknown"
    )


def _text_shingles(text: str, limit: int = 3000) -> set[str]:
    normalized = re.sub(r"\s+", "", text.lower())[:limit]
    if len(normalized) < 2:
        return {normalized} if normalized else set()
    return {normalized[index:index + 2] for index in range(len(normalized) - 1)}


def _is_direct_parent_child(left: object, right: object) -> bool:
    """判断两个节点是否存在直接父子关系。"""
    left_id = getattr(left, "node_id", None)
    right_id = getattr(right, "node_id", None)
    for node, target_id in ((left, right_id), (right, left_id)):
        parent = getattr(node, "relationships", {}).get(NodeRelationship.PARENT)
        if parent is not None and not isinstance(parent, list):
            if parent.node_id == target_id:
                return True
    return False


def _expand_neighbor_nodes(
    nodes: list[NodeWithScore],
    docstore: SimpleDocumentStore,
) -> list[NodeWithScore]:
    """补充命中节点同层级的前后相邻节点，并继承衰减后的锚点分数。"""
    expanded = list(nodes)
    seen = {item.node.node_id for item in nodes}
    for item in nodes:
        for relation in (NodeRelationship.PREVIOUS, NodeRelationship.NEXT):
            related = item.node.relationships.get(relation)
            if related is None or isinstance(related, list):
                continue
            node_id = related.node_id
            if node_id in seen:
                continue
            try:
                neighbor = docstore.get_node(node_id)
            except (KeyError, ValueError):
                continue
            if _node_doc_id(neighbor) != _node_doc_id(item.node):
                continue
            score = (
                float(item.score) * NEIGHBOR_SCORE_DECAY
                if item.score is not None
                else None
            )
            expanded.append(NodeWithScore(node=neighbor, score=score))
            seen.add(node_id)
    return expanded


def _rerank_endpoint() -> str:
    endpoint = os.getenv("DASHSCOPE_RERANK_ENDPOINT", "").strip()
    if endpoint:
        return endpoint
    workspace_id = os.getenv("DASHSCOPE_WORKSPACE_ID", "").strip()
    if not workspace_id:
        raise ValueError(
            "使用 qwen3.7-text-rerank 需要配置 DASHSCOPE_WORKSPACE_ID"
        )
    if not re.fullmatch(r"[A-Za-z0-9_-]+", workspace_id):
        raise ValueError("DASHSCOPE_WORKSPACE_ID 格式不正确")
    return (
        f"https://{workspace_id}.cn-beijing.maas.aliyuncs.com/api/v1/"
        "services/rerank/text-rerank/text-rerank"
    )


def _qwen_rerank(
    question: str,
    nodes: list[NodeWithScore],
) -> list[tuple[NodeWithScore, float]]:
    """调用百炼 qwen3.7-text-rerank，返回模型排序及相关性分数。"""
    if not DASHSCOPE_API_KEY:
        raise ValueError("缺少环境变量 DASHSCOPE_API_KEY")

    per_document_limit = min(
        MAX_RERANK_CHARS_PER_DOCUMENT,
        max(1000, RERANK_TOTAL_CHAR_BUDGET // len(nodes)),
    )
    documents = [
        _clean_markdown_images(item.node.get_content())[:per_document_limit]
        for item in nodes
    ]
    payload = json.dumps(
        {
            "model": RERANK_MODEL,
            "input": {"query": question, "documents": documents},
            "parameters": {
                "top_n": len(documents),
                "instruct": (
                    "Given an enterprise knowledge-base question, rank passages "
                    "that contain evidence needed to answer it."
                ),
            },
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        _rerank_endpoint(),
        data=payload,
        headers={
            "Authorization": f"Bearer {DASHSCOPE_API_KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=RERANK_TIMEOUT_SECONDS) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"{RERANK_MODEL} HTTP {exc.code}: {detail}") from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"{RERANK_MODEL} 调用失败: {exc}") from exc

    results = body.get("output", {}).get("results", [])
    if not results:
        raise RuntimeError(
            f"{RERANK_MODEL} 未返回排序结果: {body.get('message', 'unknown error')}"
        )

    ranked: list[tuple[NodeWithScore, float]] = []
    seen_indexes: set[int] = set()
    for result in results:
        index = int(result["index"])
        if index < 0 or index >= len(nodes) or index in seen_indexes:
            continue
        ranked.append((nodes[index], float(result["relevance_score"])))
        seen_indexes.add(index)
    ranked.extend((item, 0.0) for index, item in enumerate(nodes) if index not in seen_indexes)
    return ranked


_MEDICAL_SECTION_TERMS: dict[str, tuple[str, ...]] = {
    "适应症": ("适应症", "用于治疗", "治疗什么", "适用于"),
    "用法用量": ("用法", "用量", "剂量", "给药", "怎么服用", "怎么使用"),
    "禁忌": ("禁忌", "禁用", "不能使用", "不应使用"),
    "不良反应": ("不良反应", "副作用", "毒性", "风险"),
    "药物相互作用": ("相互作用", "合用", "联用"),
    "特殊人群": ("孕妇", "妊娠", "哺乳", "儿童", "老年", "肝功能", "肾功能"),
    "注意事项": ("注意事项", "警告", "监测", "慎用"),
    "就医提示": ("就医", "医院", "医师", "药师", "立即就诊", "及时就诊"),
}
_MEDICINE_DOSAGE_FORMS = (
    "粉针剂", "口服液", "混悬液", "注射液", "滴眼液", "喷雾剂",
    "软胶囊", "胶囊", "咀嚼片", "分散片", "缓释片", "肠溶片",
    "片剂", "颗粒", "糖浆", "软膏", "乳膏", "凝胶", "滴剂",
    "丸剂", "丸", "散剂", "散", "栓剂", "栓", "贴剂", "贴",
)
_MEDICINE_ENTITY_RE = re.compile(
    rf"[0-9A-Za-z\u4e00-\u9fff·-]{{2,32}}?(?:{'|'.join(_MEDICINE_DOSAGE_FORMS)})"
)
_LEAFLET_ENTITY_RE = re.compile(r"[0-9A-Za-z\u4e00-\u9fff·-]{2,32}?说明书")
_MEDICINE_LEADING_NOISE_RE = re.compile(
    r"^(?:请帮我|帮我|请问|我想了解|我想知道|我想查询|想了解|想知道|"
    r"想查询|请|总结|汇总|查询|介绍|说明|分析|比较|关于|有关|针对|"
    r"这个|这种|该|一下|和|与|及|、)+"
)
_GENERIC_MEDICINE_REFERENCES = (
    "药品说明书", "药物说明书", "说明书中的", "药品的", "药物的",
    "该药", "这种药", "本药",
)


def _clean_medicine_entity(value: str) -> str:
    candidate = _MEDICINE_LEADING_NOISE_RE.sub("", value).strip(" ，,。！？?的")
    if not candidate or any(
        generic in candidate for generic in ("说明书", "药品", "药物", "用药")
    ):
        return ""
    return candidate


def _medical_query_profile(question: str) -> dict[str, list[str]]:
    dosage_form_entities = [
        candidate
        for raw in _MEDICINE_ENTITY_RE.findall(question)
        if (candidate := _clean_medicine_entity(raw))
    ]
    leaflet_entities = [
        candidate
        for raw in _LEAFLET_ENTITY_RE.findall(question)
        if (candidate := _clean_medicine_entity(raw.removesuffix("说明书")))
    ]
    entities = list(dict.fromkeys([*dosage_form_entities, *leaflet_entities]))
    sections = [
        section
        for section, terms in _MEDICAL_SECTION_TERMS.items()
        if any(term in question for term in terms)
    ]
    return {"entities": entities, "sections": sections}


def _needs_medicine_clarification(question: str) -> bool:
    """泛指说明书章节而未指定药品时，禁止从召回证据反推用户对象。"""
    profile = _medical_query_profile(question)
    normalized = re.sub(r"\s+", "", question)
    return (
        bool(profile["sections"])
        and not profile["entities"]
        and any(value in normalized for value in _GENERIC_MEDICINE_REFERENCES)
    )


def _introduced_medicine_entities(original: str, generated: str) -> list[str]:
    allowed = set(_medical_query_profile(original)["entities"])
    generated_entities = set(_medical_query_profile(generated)["entities"])
    return sorted(generated_entities - allowed)


def _clarification_message() -> str:
    return (
        "请指定需要查询的具体药品名称（最好同时提供剂型或批准文号）。"
        "例如：‘总结安尔眠胶囊的禁忌、不良反应和就医提示’。"
    )


def _rewrite_queries(question: str, *, use_llm: bool = True) -> list[str]:
    """Build deterministic variants first; use the LLM only on explicit fallback."""
    queries = [question]
    profile = _medical_query_profile(question)
    if profile["entities"] or profile["sections"]:
        expanded_sections = [
            term
            for section in profile["sections"]
            for term in _MEDICAL_SECTION_TERMS[section]
        ]
        deterministic = " ".join([
            *profile["entities"], *profile["sections"], *expanded_sections[:4]
        ]).strip()
        if deterministic and deterministic != question:
            queries.append(deterministic)
    if not use_llm or not QUERY_REWRITE_ENABLED or QUERY_REWRITE_COUNT == 0:
        return queries
    prompt = f"""把下面的知识库问题改写为最多 {QUERY_REWRITE_COUNT} 个检索查询。
保留专有名词、数字和限定条件，不回答问题。只返回 JSON 字符串数组。
问题：{question}
JSON："""
    try:
        raw = str(Settings.llm.complete(prompt, max_tokens=256)).strip()
        match = re.search(r"\[[\s\S]*\]", raw)
        values = json.loads(match.group(0) if match else raw)
    except Exception as exc:
        logger.warning("查询改写失败，使用原问题: %s", exc)
        return queries
    if isinstance(values, list):
        for value in values:
            candidate = str(value).strip()
            introduced = _introduced_medicine_entities(question, candidate)
            if introduced:
                logger.warning("丢弃引入新药品实体的查询改写: %s", introduced)
                continue
            if candidate and candidate not in queries:
                queries.append(candidate)
            if len(queries) >= QUERY_REWRITE_COUNT + 1:
                break
    return queries


def _candidates_need_query_rewrite(
    candidates: list[NodeWithScore],
    query_profile: dict[str, list[str]],
    top_n: int,
) -> bool:
    """Retry with an LLM rewrite only when first-pass recall is visibly weak."""
    if len(candidates) < max(3, top_n):
        return True
    entities = query_profile["entities"]
    if not entities:
        return False
    top_text = "\n".join(
        f"{item.node.metadata.get('title', '')}\n{item.node.get_content()}"
        for item in candidates[: max(5, top_n)]
    ).lower()
    return any(entity.lower() not in top_text for entity in entities)


def _hybrid_candidates(
    queries: list[str],
    docstore: SimpleDocumentStore,
    query_profile: dict[str, list[str]],
) -> tuple[list[NodeWithScore], int, int]:
    """Fuse leaf-only dense and BM25 candidates with weighted RRF."""
    retriever = _build_retriever(RETRIEVAL_TOP_K, docstore)
    leaf_ids = _leaf_node_ids(docstore)
    fused: dict[str, float] = {}
    by_id: dict[str, Any] = {}
    dense_count = 0
    bm25_count = 0
    for query in queries:
        dense = _only_leaf_nodes(
            _filter_retrieved_nodes(retriever.retrieve(query)), leaf_ids
        )
        dense_count += len(dense)
        for rank, item in enumerate(dense, start=1):
            raw_score = float(item.score or 0.0)
            if raw_score < MIN_VECTOR_SCORE:
                continue
            node_id = str(item.node.node_id)
            by_id[node_id] = item.node
            fused[node_id] = fused.get(node_id, 0.0) + HYBRID_DENSE_WEIGHT / (60 + rank)

        lexical = _search_bm25_leaf(
            query, RETRIEVAL_TOP_K, leaf_ids
        )
        bm25_count += len(lexical)
        for rank, row in enumerate(lexical, start=1):
            node_id = str(row["node_id"])
            try:
                node = docstore.get_node(node_id)
            except (KeyError, ValueError):
                continue
            if not _is_meaningful_text(node.get_content()):
                continue
            by_id[node_id] = node
            fused[node_id] = fused.get(node_id, 0.0) + HYBRID_BM25_WEIGHT / (60 + rank)

    if not fused:
        return [], dense_count, bm25_count
    peak = max(fused.values()) or 1.0
    candidates = []
    for node_id, score in fused.items():
        node = by_id[node_id]
        metadata = dict(getattr(node, "metadata", {}) or {})
        searchable = f"{metadata.get('title', '')}\n{node.get_content()}".lower()
        normalized_score = score / peak
        if any(entity.lower() in searchable for entity in query_profile["entities"]):
            normalized_score += EXACT_ENTITY_BOOST
        section = str(metadata.get("section") or metadata.get("section_path") or "")
        if any(value in section for value in query_profile["sections"]):
            normalized_score += SECTION_MATCH_BOOST
        candidates.append(NodeWithScore(node=node, score=normalized_score))
    candidates.sort(key=lambda item: float(item.score or 0.0), reverse=True)
    return candidates[: RETRIEVAL_TOP_K * 2], dense_count, bm25_count


def _rerank_nodes(
    question: str,
    nodes: list[NodeWithScore],
    top_n: int,
) -> tuple[list[NodeWithScore], str]:
    """以向量分数+问题覆盖率排序，再用MMR、文档配额消除重复。"""
    unique: list[NodeWithScore] = []
    seen_ids: set[str] = set()
    seen_text: set[str] = set()
    for item in _filter_retrieved_nodes(nodes):
        text_key = re.sub(r"\s+", "", item.node.get_content()).lower()
        if item.node.node_id in seen_ids or text_key in seen_text:
            continue
        seen_ids.add(item.node.node_id)
        seen_text.add(text_key)
        unique.append(item)

    if not unique:
        return [], "none"

    try:
        model_ranked = _qwen_rerank(question, unique)
        ranking_mode = "rerank"
    except (RuntimeError, ValueError) as exc:
        # 远程排序不可用时保持知识库可查询，使用原召回分数降级。
        logger.warning("%s；降级使用原始向量分数进行排序", exc)
        model_ranked = sorted(
            ((item, float(item.score or 0.0)) for item in unique),
            key=lambda pair: pair[1],
            reverse=True,
        )
        ranking_mode = "hybrid_fallback"

    minimum_score = MIN_RERANK_SCORE if ranking_mode == "rerank" else MIN_VECTOR_SCORE
    model_ranked = [pair for pair in model_ranked if pair[1] >= minimum_score]
    if not model_ranked:
        return [], ranking_mode

    candidates = [
        (item, relevance, _text_shingles(item.node.get_content()), item.node.get_content())
        for item, relevance in model_ranked
    ]

    selected: list[tuple[NodeWithScore, float, set[str], str]] = []
    per_doc: dict[str, int] = {}
    while candidates and len(selected) < top_n:
        best_index = -1
        best_score = float("-inf")
        for index, (item, relevance, shingles, text) in enumerate(candidates):
            doc_id = _node_doc_id(item.node)
            if per_doc.get(doc_id, 0) >= MAX_CANDIDATES_PER_DOC:
                continue
            redundancy = 0.0
            normalized = re.sub(r"\s+", "", text).lower()
            hard_duplicate = False
            for chosen_item, _, chosen_shingles, chosen_text in selected:
                chosen_normalized = re.sub(r"\s+", "", chosen_text).lower()
                if (
                    len(normalized) >= 80
                    and (normalized in chosen_normalized or chosen_normalized in normalized)
                ):
                    similarity = 1.0
                else:
                    union = shingles | chosen_shingles
                    similarity = len(shingles & chosen_shingles) / len(union) if union else 0.0
                if _is_direct_parent_child(item.node, chosen_item.node) or similarity >= 0.88:
                    hard_duplicate = True
                    break
                redundancy = max(redundancy, similarity)
            if hard_duplicate:
                continue
            mmr_score = (
                MMR_RELEVANCE_WEIGHT * relevance
                - (1 - MMR_RELEVANCE_WEIGHT) * redundancy
            )
            if mmr_score > best_score:
                best_score = mmr_score
                best_index = index
        if best_index < 0:
            break
        chosen = candidates.pop(best_index)
        selected.append(chosen)
        doc_id = _node_doc_id(chosen[0].node)
        per_doc[doc_id] = per_doc.get(doc_id, 0) + 1

    return ([
        NodeWithScore(node=item.node, score=round(relevance, 6))
        for item, relevance, _, _ in selected
    ], ranking_mode)


def _retrieve_context_nodes(question: str, top_n: int) -> RetrievalOutcome:
    """查询改写 → 混合召回 → 锚点重排 → 连续证据上下文构建。"""
    total_started = time.perf_counter()
    timings: dict[str, int] = {}
    rewrite_started = time.perf_counter()
    queries = [question]
    query_profile = _medical_query_profile(question)
    timings["deterministic_rewrite_ms"] = int(
        (time.perf_counter() - rewrite_started) * 1000
    )
    retrieval_started = time.perf_counter()
    with _storage_lock.read():
        docstore = _load_docstore()
        candidates, dense_count, bm25_count = _hybrid_candidates(
            queries, docstore, query_profile
        )
    timings["initial_retrieval_ms"] = int(
        (time.perf_counter() - retrieval_started) * 1000
    )
    timings["deterministic_retry_ms"] = 0
    timings["llm_rewrite_ms"] = 0
    timings["rewrite_retry_ms"] = 0
    if _candidates_need_query_rewrite(candidates, query_profile, top_n):
        deterministic_queries = _rewrite_queries(question, use_llm=False)
        if deterministic_queries != queries:
            retry_started = time.perf_counter()
            queries = deterministic_queries
            with _storage_lock.read():
                docstore = _load_docstore()
                candidates, dense_count, bm25_count = _hybrid_candidates(
                    queries, docstore, query_profile
                )
            timings["deterministic_retry_ms"] = int(
                (time.perf_counter() - retry_started) * 1000
            )
    if (
        QUERY_REWRITE_ENABLED
        and _candidates_need_query_rewrite(candidates, query_profile, top_n)
    ):
        llm_rewrite_started = time.perf_counter()
        expanded_queries = _rewrite_queries(question, use_llm=True)
        timings["llm_rewrite_ms"] = int(
            (time.perf_counter() - llm_rewrite_started) * 1000
        )
        if expanded_queries != queries:
            retry_started = time.perf_counter()
            queries = expanded_queries
            with _storage_lock.read():
                docstore = _load_docstore()
                candidates, dense_count, bm25_count = _hybrid_candidates(
                    queries, docstore, query_profile
                )
            timings["rewrite_retry_ms"] = int(
                (time.perf_counter() - retry_started) * 1000
            )
    rerank_started = time.perf_counter()
    nodes, ranking_mode = _rerank_nodes(question, candidates, top_n)
    timings["rerank_ms"] = int((time.perf_counter() - rerank_started) * 1000)
    if LOG_RETRIEVAL_CHUNKS:
        logger.info(
            "检索过滤重排结果 mode=%s question=%s chunks=%s",
            ranking_mode,
            question[:160],
            json.dumps(
                [
                    {
                        "rank": rank,
                        "rankedChunkId": str(item.node.node_id),
                        "docId": _node_doc_id(item.node),
                        "score": round(float(item.score or 0.0), 6),
                        "section": (getattr(item.node, "metadata", {}) or {}).get("section"),
                    }
                    for rank, item in enumerate(nodes, start=1)
                ],
                ensure_ascii=False,
            ),
        )
    context_started = time.perf_counter()
    with _storage_lock.read():
        docstore = _load_docstore()
        evidence_contexts = _build_evidence_contexts(nodes, docstore, top_n)
    timings["evidence_context_ms"] = int(
        (time.perf_counter() - context_started) * 1000
    )
    if LOG_RETRIEVAL_CHUNKS:
        logger.info(
            "回答证据扩展结果 question=%s contexts=%s",
            question[:160],
            json.dumps(
                [
                    {
                        "rank": rank,
                        "rankedChunkId": source.get("node_id"),
                        "contextKind": source.get("context_kind"),
                        "contextChunkIds": source.get("context_node_ids") or [],
                        "anchorChunkIds": source.get("anchor_node_ids") or [],
                    }
                    for rank, source in enumerate(evidence_contexts, start=1)
                ],
                ensure_ascii=False,
            ),
        )
    timings["total_retrieval_ms"] = int(
        (time.perf_counter() - total_started) * 1000
    )
    return RetrievalOutcome(
        nodes=nodes,
        evidence_contexts=evidence_contexts,
        mode=ranking_mode,
        queries=queries,
        dense_candidates=dense_count,
        bm25_candidates=bm25_count,
        query_profile=query_profile,
        timings=timings,
        rejected_by_threshold=bool(candidates and not nodes),
    )


_SERVER_DIR = Path(__file__).resolve().parents[1]
_parsed_config = Path(os.environ.get("PARSED_DIR", "parsed"))
PARSED_DIR = _parsed_config if _parsed_config.is_absolute() else _SERVER_DIR / _parsed_config
PERSIST_DIR = DB_PATH.parent
DELETION_MARKER_DIR = PERSIST_DIR / "deletion-markers"

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6380")
REDIS_INDEX_NAME = os.getenv("REDIS_INDEX_NAME", "knowledge_base")
REDIS_INDEX_PREFIX = os.getenv("REDIS_INDEX_PREFIX", f"{REDIS_INDEX_NAME}/vector")
REDIS_VECTOR_DIMS = int(os.getenv("REDIS_VECTOR_DIMS", "1024"))
REDIS_DELETE_SCAN_COUNT = max(10, int(os.getenv("REDIS_DELETE_SCAN_COUNT", "500")))
REDIS_DELETE_BATCH_SIZE = max(1, int(os.getenv("REDIS_DELETE_BATCH_SIZE", "500")))
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY")

_settings_ready = False
_settings_lock = threading.Lock()
_storage_lock = _ReadWriteLock()


def _ensure_settings() -> None:
    """初始化全局 LLM 与 Embedding（幂等）。"""
    global _settings_ready
    if _settings_ready:
        return
    with _settings_lock:
        if _settings_ready:
            return
        if not DASHSCOPE_API_KEY:
            raise ValueError("缺少环境变量 DASHSCOPE_API_KEY")
        Settings.llm = DashScope(
            model_name=os.getenv("LLM_MODEL", "qwen-plus"),
            temperature=0,
            max_tokens=QA_MAX_OUTPUT_TOKENS,
            context_window=1_000_000,
            api_key=DASHSCOPE_API_KEY,
        )
        Settings.embed_model = DashScopeEmbedding(
            model_name=os.getenv("EMBEDDING_MODEL", "text-embedding-v3"),
            embed_batch_size=10,
            api_key=DASHSCOPE_API_KEY,
        )
        _settings_ready = True


def _make_index_schema() -> IndexSchema:
    """构造 RedisVL schema；向量维度必须与 Embedding 输出保持一致。"""
    if REDIS_VECTOR_DIMS <= 0:
        raise ValueError("REDIS_VECTOR_DIMS 必须是正整数")

    return IndexSchema.from_dict(
        {
            "index": {
                "name": REDIS_INDEX_NAME,
                "prefix": REDIS_INDEX_PREFIX,
                "key_separator": "_",
                "storage_type": "hash",
            },
            "fields": [
                {"name": "id", "type": "tag"},
                {"name": "doc_id", "type": "tag"},
                {"name": "text", "type": "text"},
                {
                    "name": "vector",
                    "type": "vector",
                    "attrs": {
                        "algorithm": "flat",
                        "datatype": "float32",
                        "dims": REDIS_VECTOR_DIMS,
                        "distance_metric": "cosine",
                    },
                },
            ],
        }
    )


def _make_vector_store(overwrite: bool = False) -> RedisVectorStore:
    return RedisVectorStore(
        schema=_make_index_schema(),
        redis_url=REDIS_URL,
        overwrite=overwrite,  # overwrite=False 表示在初始化时不能覆盖或删除 Redis 中已存在的索引和数据，这是安全的查询模式。
    )


def _redis_client() -> Redis:
    """创建仅用于存储维护的客户端，删除操作不依赖LLM或Embedding配置。"""
    return Redis.from_url(
        REDIS_URL,
        decode_responses=False,
        socket_connect_timeout=5,
        socket_timeout=30,
    )


def _redis_document_keys_by_scan(client: Redis, doc_id: str) -> list[bytes | str]:
    """RediSearch不可用时的兼容回退：SCAN + Pipeline HGET。"""
    matched: list[bytes | str] = []
    pending: list[bytes | str] = []
    pattern = f"{REDIS_INDEX_PREFIX}_*"

    def inspect(keys: list[bytes | str]) -> None:
        if not keys:
            return
        pipe = client.pipeline(transaction=False)
        for key in keys:
            pipe.hget(key, "doc_id")
        for key, value in zip(keys, pipe.execute()):
            if isinstance(value, bytes):
                value = value.decode("utf-8", errors="replace")
            if value == doc_id:
                matched.append(key)

    for key in client.scan_iter(match=pattern, count=REDIS_DELETE_SCAN_COUNT):
        pending.append(key)
        if len(pending) >= REDIS_DELETE_BATCH_SIZE:
            inspect(pending)
            pending = []
    inspect(pending)
    return matched


def _redis_document_keys(client: Redis, doc_id: str) -> list[bytes | str]:
    """优先通过已索引的doc_id Tag分页定位节点，避免扫描整个Redis前缀。"""
    try:
        expression = str(Tag("doc_id") == doc_id)
        keys: list[bytes | str] = []
        offset = 0
        while True:
            result = client.ft(REDIS_INDEX_NAME).search(
                Query(expression)
                .no_content()
                .paging(offset, REDIS_DELETE_BATCH_SIZE)
                .dialect(2)
            )
            page = [document.id for document in result.docs]
            keys.extend(page)
            offset += len(page)
            if not page or offset >= int(result.total):
                return keys
    except (RedisError, AttributeError, TypeError, ValueError) as exc:
        logger.warning("RediSearch按doc_id定位失败，回退SCAN: %s", exc)
        return _redis_document_keys_by_scan(client, doc_id)


def _pipeline_unlink(client: Redis, keys: list[bytes | str]) -> int:
    """将多个UNLINK批次放入一个非事务Pipeline，减少网络往返并异步释放内存。"""
    if not keys:
        return 0
    pipe = client.pipeline(transaction=False)
    for start in range(0, len(keys), REDIS_DELETE_BATCH_SIZE):
        batch = keys[start:start + REDIS_DELETE_BATCH_SIZE]
        pipe.unlink(*batch)
    return sum(int(value or 0) for value in pipe.execute())


def _delete_and_verify_redis_document(doc_id: str) -> dict[str, int | bool]:
    """RedisVectorStore过滤删除 + Pipeline UNLINK残留 + RediSearch校验。"""
    client = _redis_client()
    try:
        lookup_started = time.perf_counter()
        keys = _redis_document_keys(client, doc_id)
        lookup_ms = int((time.perf_counter() - lookup_started) * 1000)

        vector_delete_started = time.perf_counter()
        # 当前LlamaIndex实现内部使用Pipeline，但过滤查询默认最多返回10条；
        # 因此先使用官方接口，再由下方分页查询和Pipeline补齐全部节点。
        vector_store_fallback = False
        try:
            _make_vector_store(overwrite=False).delete(doc_id)
        except Exception as exc:
            # 不让依赖版本的过滤删除限制阻断后续自主可控的批量删除。
            vector_store_fallback = True
            logger.warning("RedisVectorStore删除未完成，继续Pipeline补偿: %s", exc)
        vector_store_delete_ms = int(
            (time.perf_counter() - vector_delete_started) * 1000
        )

        pipeline_started = time.perf_counter()
        residual = _redis_document_keys(client, doc_id)
        _pipeline_unlink(client, residual)
        pipeline_delete_ms = int((time.perf_counter() - pipeline_started) * 1000)

        verify_started = time.perf_counter()
        remaining = len(_redis_document_keys(client, doc_id))
        verify_ms = int((time.perf_counter() - verify_started) * 1000)
        if remaining:
            raise RuntimeError(
                f"Redis向量删除校验失败: doc_id={doc_id}, remaining={remaining}"
            )
        return {
            "redis_nodes_found": len(keys),
            "redis_nodes_deleted": max(0, len(keys) - remaining),
            "redis_nodes_remaining": remaining,
            "redis_delete_lookup_ms": lookup_ms,
            "redis_vector_store_delete_ms": vector_store_delete_ms,
            "redis_vector_store_fallback": vector_store_fallback,
            "redis_pipeline_delete_ms": pipeline_delete_ms,
            "redis_delete_verify_ms": verify_ms,
            "verified": True,
        }
    finally:
        client.close()


def _count_redis_document_nodes(doc_id: str) -> int:
    client = _redis_client()
    try:
        return len(_redis_document_keys(client, doc_id))
    finally:
        client.close()


def _safe_doc_id(doc_id: str) -> str:
    """清理文档标识，防止路径穿越。"""
    name = Path(doc_id).name
    for ch in '<>:"/\\|?*':
        name = name.replace(ch, "_")
    return name.strip() or "document"


def _deletion_marker(doc_id: str) -> Path:
    return DELETION_MARKER_DIR / f"{_safe_doc_id(doc_id)}.deleted"


def _mark_document_deleting(doc_id: str) -> None:
    """持久化删除墓碑，阻止已在解析中的同一文档稍后重新写回索引。"""
    DELETION_MARKER_DIR.mkdir(parents=True, exist_ok=True)
    marker = _deletion_marker(doc_id)
    marker.write_text(str(time.time_ns()), encoding="ascii")


def _normalized_document_metadata(
    doc_id: str,
    text: str,
    supplied: dict[str, Any] | None,
) -> dict[str, Any]:
    metadata = dict(supplied or {})
    title_match = re.search(r"^#\s+(.+)$", text, flags=re.MULTILINE)
    metadata["doc_id"] = doc_id
    metadata["title"] = str(
        metadata.get("title")
        or (title_match.group(1).strip() if title_match else doc_id)
    )[:300]
    # Redis hashes reject None values. Optional provenance fields are omitted
    # until a real value is available rather than represented as JSON null.
    return {key: value for key, value in metadata.items() if value is not None}


def _annotate_node_pages(
    nodes: list[Any],
    content_list: list[dict[str, Any]] | None,
) -> None:
    """Best-effort page provenance from MinerU content_list blocks."""
    if not content_list:
        return
    fragments: list[tuple[str, int]] = []
    for block in content_list:
        raw_page = block.get("page_idx", block.get("page_number"))
        raw_text = block.get("text") or block.get("content") or block.get("table_body")
        if raw_page is None or not raw_text:
            continue
        try:
            page = int(raw_page) + (1 if "page_idx" in block else 0)
        except (TypeError, ValueError):
            continue
        normalized = re.sub(r"\s+", "", str(raw_text)).lower()
        if len(normalized) >= 12:
            fragments.append((normalized[:80], page))
    for node in nodes:
        normalized_node = re.sub(r"\s+", "", node.get_content()).lower()
        pages = sorted({page for fragment, page in fragments if fragment in normalized_node})
        if not pages:
            continue
        if len(pages) == 1:
            node.metadata["page_number"] = pages[0]
        else:
            node.metadata.pop("page_number", None)
        node.metadata["page_start"] = pages[0]
        node.metadata["page_end"] = pages[-1]


def _annotate_node_structure(nodes: list[Any], markdown: str) -> None:
    """把Markdown标题路径和原文顺序附加到所有层级节点。"""
    headings: list[tuple[int, str, str]] = []
    stack: list[tuple[int, str]] = []
    for match in re.finditer(r"^(#{1,6})\s+(.+?)\s*$", markdown, flags=re.MULTILINE):
        level = len(match.group(1))
        title = match.group(2).strip()[:200]
        while stack and stack[-1][0] >= level:
            stack.pop()
        stack.append((level, title))
        headings.append((match.start(), title, " / ".join(item[1] for item in stack)))

    positioned: list[tuple[int, int | None, Any]] = []
    for node in nodes:
        start = getattr(node, "start_char_idx", None)
        end = getattr(node, "end_char_idx", None)
        if start is None:
            probe = node.get_content()[:120].strip()
            found = markdown.find(probe) if probe else -1
            start = found if found >= 0 else 0
        positioned.append((int(start), int(end) if end is not None else None, node))
    positioned.sort(key=lambda item: (item[0], item[1] or 0))

    heading_index = 0
    active_heading: tuple[int, str, str] | None = None
    for order, (start, end, node) in enumerate(positioned):
        while heading_index < len(headings) and headings[heading_index][0] <= start:
            active_heading = headings[heading_index]
            heading_index += 1
        if active_heading is not None:
            node.metadata["section"] = active_heading[1]
            node.metadata["section_path"] = active_heading[2]
        node.metadata["block_order"] = order
        node.metadata["char_start"] = int(start)
        if end is not None:
            node.metadata["char_end"] = int(end)


def _attach_provenance_metadata(
    nodes: list[Any],
    metadata: dict[str, Any],
) -> None:
    """Attach citation metadata after splitting, without embedding it as text.

    LlamaIndex's metadata-aware splitter reserves chunk tokens for metadata.
    Long titles and source URIs can therefore be larger than the smallest
    hierarchical chunk.  Provenance is stored on every node for filtering and
    citations, but does not need to be sent to the embedding or LLM models.
    """
    excluded_keys = list(_PROVENANCE_METADATA_KEYS)
    clean_metadata = {
        key: value for key, value in metadata.items() if value is not None
    }
    for node in nodes:
        node.metadata.update(clean_metadata)
        for key in tuple(node.metadata):
            if node.metadata[key] is None:
                del node.metadata[key]
        node.excluded_embed_metadata_keys = sorted(
            set(node.excluded_embed_metadata_keys).union(excluded_keys)
        )
        node.excluded_llm_metadata_keys = sorted(
            set(node.excluded_llm_metadata_keys).union(excluded_keys)
        )


def _sanitize_node_metadata_for_redis(nodes: list[Any]) -> None:
    """Remove null metadata values before RedisVL serializes hash fields."""
    for node in nodes:
        for key in tuple(node.metadata):
            if node.metadata[key] is None:
                del node.metadata[key]


def _leaf_nodes_for_document(
    docstore: SimpleDocumentStore,
    doc_id: str,
) -> list[Any]:
    ref_info = docstore.get_ref_doc_info(doc_id)
    if ref_info is None:
        return []
    nodes = []
    for node_id in ref_info.node_ids:
        try:
            nodes.append(docstore.get_node(node_id))
        except (KeyError, ValueError):
            continue
    return get_leaf_nodes(nodes) if nodes else []


def _restore_redis_document(
    vector_store: RedisVectorStore,
    docstore: SimpleDocumentStore,
    doc_id: str,
) -> None:
    vector_store.delete(doc_id)
    old_leaves = _leaf_nodes_for_document(docstore, doc_id)
    if old_leaves:
        _sanitize_node_metadata_for_redis(old_leaves)
        missing = [node for node in old_leaves if not node.embedding]
        if missing:
            embeddings = Settings.embed_model.get_text_embedding_batch(
                [
                    node.get_content(metadata_mode=MetadataMode.EMBED)
                    for node in missing
                ],
                show_progress=False,
            )
            for node, embedding in zip(missing, embeddings):
                node.embedding = embedding
        context = StorageContext.from_defaults(
            docstore=docstore,
            vector_store=vector_store,
        )
        VectorStoreIndex(old_leaves, storage_context=context)


@contextmanager
def _docstore_process_lock(timeout_seconds: float = 120.0):
    """跨进程串行化DocStore提交，避免多个MCP进程互相覆盖。"""
    PERSIST_DIR.mkdir(parents=True, exist_ok=True)
    lock_path = PERSIST_DIR / "docstore.lock"
    handle = lock_path.open("a+b")
    handle.seek(0, os.SEEK_END)
    if handle.tell() == 0:
        handle.write(b"\0")
        handle.flush()

    deadline = time.monotonic() + timeout_seconds
    locked = False
    try:
        if os.name == "nt":
            import msvcrt

            while not locked:
                try:
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                    locked = True
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("等待DocStore跨进程写锁超时")
                    time.sleep(0.05)
        else:
            import fcntl

            while not locked:
                try:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    locked = True
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("等待DocStore跨进程写锁超时")
                    time.sleep(0.05)
        yield
    finally:
        if locked:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


"""
文档存储加载器：
    从本地磁盘加载已经持久化的 SimpleDocumentStore 对象
    用于保存文档的节点信息（父子关系）
"""
def _load_docstore() -> SimpleDocumentStore:
    return knowledge_store.load_docstore()

"""
持久化保存文档信息
"""
def _persist_docstore(
    docstore: SimpleDocumentStore,
    *,
    replace_doc_id: str | None = None,
    nodes: list[Any] | None = None,
    document_metadata: dict[str, Any] | None = None,
) -> None:
    knowledge_store.commit_docstore(
        docstore,
        replace_doc_id=replace_doc_id,
        nodes=nodes or [],
        document_metadata=document_metadata,
    )


def _leaf_node_ids(docstore: SimpleDocumentStore) -> set[str]:
    """Return the canonical retrieval units from the production hierarchy."""
    return {
        str(node.node_id)
        for node in get_leaf_nodes(list(docstore.docs.values()))
    }


def _only_leaf_nodes(
    nodes: list[NodeWithScore], leaf_ids: set[str]
) -> list[NodeWithScore]:
    """Prevent parent IDs from entering fusion, reranking, or leaf metrics."""
    return [item for item in nodes if str(item.node.node_id) in leaf_ids]


def _search_bm25_leaf(
    query: str,
    limit: int,
    leaf_ids: set[str],
) -> list[dict[str, Any]]:
    """Over-fetch the all-level FTS index, then retain production leaves only."""
    limit = max(1, limit)
    rows = knowledge_store.search_bm25(
        query,
        200,
        min_term_coverage=MIN_BM25_TERM_COVERAGE,
    )
    return [row for row in rows if str(row["node_id"]) in leaf_ids][:limit]


def _build_retriever(
    similarity_top_k: int,
    docstore: SimpleDocumentStore,
) -> Any:
    """Create the raw Redis leaf retriever; expand parents only after reranking."""
    if not docstore.docs:
        raise ValueError("知识库为空，请先添加文档")

    vector_store = _make_vector_store(overwrite=False)
    index = VectorStoreIndex.from_vector_store(vector_store)
    return index.as_retriever(similarity_top_k=similarity_top_k)


def _source_dict(
    source: object,
    max_chars: int = MAX_SOURCE_CHARS,
) -> dict[str, object]:
    """把 NodeWithScore 转为可供调用方核验的原始片段。"""
    node = source.node  # type: ignore[attr-defined]
    text = node.get_content().strip()
    metadata = dict(getattr(node, "metadata", {}) or {})
    doc_id = (
        getattr(node, "ref_doc_id", None)
        or metadata.get("doc_id")
        or metadata.get("document_id")
        or "unknown"
    )
    score = getattr(source, "score", None)
    return {
        "doc_id": doc_id,
        "node_id": node.node_id,
        "score": round(float(score), 6) if score is not None else None,
        "title": metadata.get("title"),
        "original_filename": metadata.get("original_filename"),
        "page_number": metadata.get("page_number") or metadata.get("page_label"),
        "page_start": metadata.get("page_start"),
        "page_end": metadata.get("page_end"),
        "source_uri": metadata.get("source_uri"),
        "content_sha256": metadata.get("content_sha256"),
        "issuer": metadata.get("issuer"),
        "version": metadata.get("version"),
        "published_at": metadata.get("publishedAt") or metadata.get("published_at"),
        "audience": metadata.get("audience"),
        "effective_status": metadata.get("effectiveStatus") or metadata.get("effective_status"),
        "review_status": metadata.get("reviewStatus") or metadata.get("review_status"),
        "document_type": metadata.get("documentType") or metadata.get("document_type"),
        "section": metadata.get("section"),
        "section_path": metadata.get("section_path"),
        "block_order": metadata.get("block_order"),
        "char_start": metadata.get("char_start", getattr(node, "start_char_idx", None)),
        "char_end": metadata.get("char_end", getattr(node, "end_char_idx", None)),
        "text": text[:max_chars],
        "truncated": len(text) > max_chars,
    }


def _node_position(node: Any) -> tuple[int, int]:
    metadata = dict(getattr(node, "metadata", {}) or {})
    start = metadata.get("char_start", getattr(node, "start_char_idx", None))
    end = metadata.get("char_end", getattr(node, "end_char_idx", None))
    return (
        int(start) if isinstance(start, (int, float)) else 0,
        int(end) if isinstance(end, (int, float)) else 0,
    )


def _merge_context_texts(nodes: list[Any], max_chars: int) -> str:
    """按原文顺序合并节点，并消除父子节点或滑窗造成的重复文本。"""
    merged = ""
    for node in sorted(nodes, key=_node_position):
        text = _clean_markdown_images(node.get_content()).strip()
        if not text or text in merged:
            continue
        if merged and merged in text:
            merged = text
            continue
        overlap = 0
        for size in range(min(600, len(merged), len(text)), 19, -1):
            if merged[-size:] == text[:size]:
                overlap = size
                break
        if not merged:
            merged = text
        elif overlap:
            merged = f"{merged}{text[overlap:]}"
        else:
            merged = f"{merged}\n{text}"
        if len(merged) >= max_chars:
            return merged[:max_chars]
    return merged[:max_chars]


def _related_node(node: Any, relation: NodeRelationship,
                  docstore: SimpleDocumentStore) -> Any | None:
    related = getattr(node, "relationships", {}).get(relation)
    if related is None or isinstance(related, list):
        return None
    try:
        candidate = docstore.get_node(related.node_id)
    except (KeyError, ValueError):
        return None
    return candidate if _node_doc_id(candidate) == _node_doc_id(node) else None


def _evidence_nodes_for_anchor(anchor: Any,
                               docstore: SimpleDocumentStore) -> tuple[list[Any], str]:
    """优先使用可控大小的父节点，否则回退到锚点及同文档相邻节点。"""
    parent = _related_node(anchor, NodeRelationship.PARENT, docstore)
    if parent is not None:
        parent_text = _clean_markdown_images(parent.get_content()).strip()
        if _is_meaningful_text(parent_text) and len(parent_text) <= EVIDENCE_PARENT_MAX_CHARS:
            return [parent], "parent"

    result = [anchor]
    for relation in (NodeRelationship.PREVIOUS, NodeRelationship.NEXT):
        cursor = anchor
        for _ in range(EVIDENCE_NEIGHBOR_WINDOW):
            cursor = _related_node(cursor, relation, docstore)
            if cursor is None:
                break
            result.append(cursor)
    return result, "neighbor_window"


def _build_evidence_contexts(
    anchors: list[NodeWithScore],
    docstore: SimpleDocumentStore,
    top_n: int,
) -> list[dict[str, object]]:
    """把离散重排锚点转换成按章节和原文顺序组织的连续证据包。"""
    contexts: list[dict[str, object]] = []
    by_key: dict[str, dict[str, object]] = {}
    total_chars = 0
    for anchor in anchors:
        evidence_nodes, context_kind = _evidence_nodes_for_anchor(anchor.node, docstore)
        context_ids = [node.node_id for node in evidence_nodes]
        key = f"{_node_doc_id(anchor.node)}:{context_ids[0]}"
        if key in by_key:
            existing_ids = by_key[key].setdefault("anchor_node_ids", [])
            if anchor.node.node_id not in existing_ids:
                existing_ids.append(anchor.node.node_id)
            existing_score = float(by_key[key].get("score") or 0.0)
            by_key[key]["score"] = max(existing_score, float(anchor.score or 0.0))
            continue

        text = _merge_context_texts(evidence_nodes, EVIDENCE_PARENT_MAX_CHARS)
        if not _is_meaningful_text(text):
            continue
        remaining = max(0, MAX_CONTEXT_TOTAL_CHARS - total_chars)
        if remaining < 60:
            break
        if len(text) > remaining:
            text = text[:remaining]

        source = _source_dict(anchor, EVIDENCE_PARENT_MAX_CHARS)
        source["text"] = text
        source["truncated"] = len(text) >= EVIDENCE_PARENT_MAX_CHARS
        source["context_kind"] = context_kind
        source["context_node_ids"] = context_ids
        source["anchor_node_ids"] = [anchor.node.node_id]
        pages = sorted({
            int(page)
            for node in evidence_nodes
            for page in (
                node.metadata.get("page_start"),
                node.metadata.get("page_number"),
                node.metadata.get("page_end"),
            )
            if isinstance(page, (int, float))
        })
        if pages:
            source["page_number"] = pages[0] if len(pages) == 1 else None
            source["page_start"] = pages[0]
            source["page_end"] = pages[-1]
        contexts.append(source)
        by_key[key] = source
        total_chars += len(text)
        if len(contexts) >= max(1, top_n):
            break
    return contexts


def _source_section(text: str) -> str:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            return stripped.lstrip("#").strip()[:100] or "相关片段"
    return "相关片段"


def _parse_json_object(raw: str) -> dict[str, Any]:
    cleaned = raw.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", cleaned, flags=re.IGNORECASE)
    match = re.search(r"\{[\s\S]*\}", cleaned)
    value = json.loads(match.group(0) if match else cleaned)
    if not isinstance(value, dict):
        raise ValueError("模型没有返回JSON对象")
    return value


def _fallback_answer_plan(question: str) -> dict[str, Any]:
    return {
        "answer_type": "evidence_synthesis",
        "sections": ["直接回答", "证据说明", "不确定性与安全提示"],
        "focus": [question],
    }


def _deterministic_answer_plan(
    question: str, sources: list[dict[str, object]]
) -> dict[str, Any]:
    profile = _medical_query_profile(question)
    sections = profile["sections"] or ["直接回答", "证据说明"]
    comparison_markers = ("比较", "区别", "差异", "分别", "相比", "哪个")
    if len(profile["entities"]) > 1 or any(value in question for value in comparison_markers):
        answer_type = "comparison"
    elif any(value in sections for value in (
        "禁忌", "不良反应", "注意事项", "特殊人群", "就医提示"
    )):
        answer_type = "risk"
    elif "用法用量" in sections:
        answer_type = "procedure"
    elif profile["sections"]:
        answer_type = "fact"
    else:
        answer_type = "evidence_synthesis"
    return {
        "answer_type": answer_type,
        "sections": sections,
        "focus": profile["entities"] or [question],
        "conflict_checks": ["来源版本", "适用人群"] if len(sources) > 1 else [],
        "planner": "deterministic",
    }


def _requires_llm_answer_plan(question: str, *, overview: bool = False) -> bool:
    if ANSWER_PLANNING_MODE == "always":
        return True
    if ANSWER_PLANNING_MODE == "never":
        return False
    profile = _medical_query_profile(question)
    complex_markers = ("比较", "区别", "差异", "分别", "综合", "冲突", "所有", "全部")
    return overview or len(profile["entities"]) > 1 or any(
        value in question for value in complex_markers
    )


def _requires_model_answer_validation(question: str, *, overview: bool = False) -> bool:
    if ANSWER_VALIDATION_MODE == "always":
        return True
    if ANSWER_VALIDATION_MODE == "never":
        return False
    high_risk_sections = {
        "适应症", "用法用量", "禁忌", "不良反应", "药物相互作用",
        "特殊人群", "注意事项", "就医提示"
    }
    return overview or bool(
        high_risk_sections.intersection(_medical_query_profile(question)["sections"])
    )


def _plan_answer(
    question: str,
    sources: list[dict[str, object]],
    *,
    overview: bool = False,
) -> dict[str, Any]:
    if not _requires_llm_answer_plan(question, overview=overview):
        return _deterministic_answer_plan(question, sources)
    inventory = [
        {
            "citation": index,
            "title": source.get("title"),
            "section": source.get("section") or _source_section(str(source.get("text") or "")),
            "document_type": source.get("document_type"),
            "audience": source.get("audience"),
        }
        for index, source in enumerate(sources, start=1)
    ]
    prompt = f"""你是循证医疗回答规划器。只规划回答结构，不回答问题，也不得补充证据之外的事实。
根据问题和证据目录返回JSON对象：
{{"answer_type":"fact|comparison|procedure|risk|summary","sections":["..."],"focus":["..."],"conflict_checks":["..."]}}
sections最多5项；focus必须保留问题中的药品、疾病、人群和限定条件。

问题：{question}
证据目录：{json.dumps(inventory, ensure_ascii=False)}
JSON："""
    try:
        plan = _parse_json_object(str(Settings.llm.complete(prompt, max_tokens=512)))
        focus_text = " ".join(str(value) for value in plan.get("focus", []))
        introduced = _introduced_medicine_entities(question, focus_text)
        if introduced:
            raise ValueError(f"回答规划引入了用户未指定的药品实体: {introduced}")
        plan["planner"] = "llm"
        return plan
    except Exception as exc:
        logger.warning("回答规划失败，使用保守规划: %s", exc)
        return _deterministic_answer_plan(question, sources)


def _citation_integrity(answer: str, source_count: int) -> dict[str, Any]:
    references = [int(value) for value in re.findall(r"\[(\d+)]", answer)]
    invalid = sorted({value for value in references if value < 1 or value > source_count})
    sentences = [
        sentence.strip()
        for sentence in re.findall(
            r"[^。！？!?；;\n]+[。！？!?；;]?(?:\s*\[\d+])?", answer
        )
        if len(re.sub(r"[^0-9A-Za-z\u4e00-\u9fff]", "", sentence)) >= 12
        and not sentence.strip().startswith("#")
    ]
    cited = sum(bool(re.search(r"\[\d+]", sentence)) for sentence in sentences)
    return {
        "referenced_citations": sorted(set(references)),
        "invalid_citations": invalid,
        "substantive_sentence_count": len(sentences),
        "cited_sentence_count": cited,
        "citation_coverage": round(cited / len(sentences), 4) if sentences else 1.0,
    }


def _validate_answer(
    question: str,
    answer: str,
    context_parts: list[str],
    source_count: int,
) -> tuple[str, dict[str, Any]]:
    integrity = _citation_integrity(answer, source_count)
    prompt = f"""你是医疗知识库回答校验器。逐项核对回答是否完全由证据支持，不得使用外部知识。
检查：事实是否有证据、引用编号是否正确、限定人群/剂量/时间是否被遗漏、来源是否冲突、是否出现诊断或处方越界。
只返回JSON：
{{"valid":true,"unsupported_claims":[],"missing_citations":[],"conflicts":[],"revised_answer":""}}
如不合格，revised_answer必须删除无证据内容并保留正确引用；如合格则留空。

问题：{question}
证据：
{chr(10).join(context_parts)}

待校验回答：
{answer}
JSON："""
    try:
        model_result = _parse_json_object(
            str(Settings.llm.complete(prompt, max_tokens=1536))
        )
        revised = str(model_result.get("revised_answer") or "").strip()
        candidate = revised if revised and not bool(model_result.get("valid")) else answer
        candidate_integrity = _citation_integrity(candidate, source_count)
        if candidate_integrity["invalid_citations"]:
            candidate = answer
            candidate_integrity = integrity
        passed = (
            bool(model_result.get("valid"))
            and not candidate_integrity["invalid_citations"]
        )
        status = "passed" if passed else ("revised" if candidate != answer else "needs_review")
        return candidate, {
            "status": status,
            "model_valid": bool(model_result.get("valid")),
            "unsupported_claims": list(model_result.get("unsupported_claims") or []),
            "missing_citations": list(model_result.get("missing_citations") or []),
            "conflicts": list(model_result.get("conflicts") or []),
            "citation_integrity": candidate_integrity,
        }
    except Exception as exc:
        logger.warning("回答校验模型不可用，保留确定性引用校验结果: %s", exc)
        return answer, {
            "status": "deterministic_only",
            "model_valid": None,
            "unsupported_claims": [],
            "missing_citations": [],
            "conflicts": [],
            "citation_integrity": integrity,
        }


def _answer_from_sources(
    question: str,
    sources: list[dict[str, object]],
    overview: bool = False,
) -> dict[str, Any]:
    """把召回知识注入LLM上下文，返回回答与可追溯来源的结构化JSON。"""
    sources = sources if overview else [
        source for source in sources if _is_approved_medical_source(source)
    ]
    if not sources:
        return {
            "question": question,
            "answer": "知识库中没有足够信息。",
            "sources": [],
        }

    context_parts = []
    response_sources = []
    for citation, source in enumerate(sources, start=1):
        text = str(source["text"]).strip()
        context_parts.append(
            f"[{citation}] 文档ID：{source['doc_id']}；发布机构：{source.get('issuer')}；"
            f"版本：{source.get('version')}；发布日期：{source.get('published_at')}；"
            f"适用人群：{source.get('audience')}\n{text}"
        )
        response_sources.append(
            {
                "citation": citation,
                "documentId": source["doc_id"],
                "chunkId": source.get("node_id"),
                "title": source.get("title"),
                "originalFilename": source.get("original_filename"),
                "pageNumber": source.get("page_number"),
                "pageStart": source.get("page_start"),
                "pageEnd": source.get("page_end"),
                "sourceUri": source.get("source_uri"),
                "contentSha256": source.get("content_sha256"),
                "issuer": source.get("issuer"),
                "version": source.get("version"),
                "publishedAt": source.get("published_at"),
                "audience": source.get("audience"),
                "effectiveStatus": source.get("effective_status"),
                "reviewStatus": source.get("review_status"),
                "documentType": source.get("document_type"),
                "section": source.get("section") or _source_section(text),
                "sectionPath": source.get("section_path"),
                "score": source.get("score"),
                "quote": text[:MAX_CONTEXT_CHARS_PER_SOURCE],
                "truncated": len(text) > MAX_CONTEXT_CHARS_PER_SOURCE,
                "contextKind": source.get("context_kind"),
                "contextNodeIds": source.get("context_node_ids") or [source.get("node_id")],
                "anchorNodeIds": source.get("anchor_node_ids") or [source.get("node_id")],
            }
        )

    timings: dict[str, int] = {}
    planning_started = time.perf_counter()
    plan = _plan_answer(question, sources, overview=overview)
    timings["answer_planning_ms"] = int(
        (time.perf_counter() - planning_started) * 1000
    )

    task_instruction = (
        f"这是全库概览问题，共有 {len(sources)} 篇文档。必须严格按照 [1] 到 "
        f"[{len(sources)}] 的顺序逐篇概括主题、主要方法和核心结论，不得遗漏任何一篇，"
        "最后再总结这些文档共同关注的内容。"
        if overview
        else "请综合相关片段直接回答用户问题，不要逐段复述检索结果。"
    )
    prompt = f"""你是循证医疗知识库助手，仅用于健康教育和决策支持，不能替代医生或药师。
只能使用下面编号且处于 ACTIVE/APPROVED 状态的知识库片段回答，禁止使用外部知识或猜测。
{task_instruction}
回答规划：{json.dumps(plan, ensure_ascii=False)}
要求：
1. 严格按照回答规划组织内容，先给直接结论，再说明依据和限制。
2. 不要把原始片段简单拼接或整段照抄。
3. 每个医疗事实所在句必须使用 [1]、[2] 形式标注来源；编号必须对应下方证据包。
4. 如果片段不足以回答，明确说明“知识库中没有足够的已审核医疗证据”。
5. 如果来源版本、适用人群或结论冲突，必须明确列出冲突，不得自行消除。
6. 不得给出确定诊断、处方、停药换药或个体化剂量；高风险内容要求医生或药师审核。

用户问题：{question}

按原文顺序整理后的证据包：
{chr(10).join(context_parts)}

回答："""
    max_tokens = OVERVIEW_MAX_OUTPUT_TOKENS if overview else QA_MAX_OUTPUT_TOKENS
    generation_started = time.perf_counter()
    generated_answer = str(Settings.llm.complete(prompt, max_tokens=max_tokens)).strip()
    timings["answer_generation_ms"] = int(
        (time.perf_counter() - generation_started) * 1000
    )
    validation_started = time.perf_counter()
    if _requires_model_answer_validation(question, overview=overview):
        answer, validation = _validate_answer(
            question, generated_answer, context_parts, len(response_sources)
        )
    else:
        answer = generated_answer
        validation = {
            "status": "deterministic_only",
            "model_valid": None,
            "reason": "low_risk_fast_path",
            "unsupported_claims": [],
            "missing_citations": [],
            "conflicts": [],
            "citation_integrity": _citation_integrity(
                generated_answer, len(response_sources)
            ),
        }
    timings["answer_validation_ms"] = int(
        (time.perf_counter() - validation_started) * 1000
    )
    return {
        "question": question,
        "answer": answer,
        "plan": plan,
        "validation": validation,
        "timings": timings,
        "sources": response_sources,
    }


def _is_approved_medical_source(source: dict[str, object]) -> bool:
    """医疗检索只允许当前有效且完成审核的证据。"""
    return _is_approved_medical_metadata(source)


def _is_corpus_overview_question(question: str) -> bool:
    normalized = re.sub(r"\s+", "", question).lower()
    overview_phrases = (
        "这些文档主要讲",
        "这些文件主要讲",
        "所有文档主要讲",
        "全部文档主要讲",
        "知识库主要讲",
        "文档总体内容",
        "概括这些文档",
        "总结这些文档",
        "总结所有文档",
        "summarizeall",
        "whatarethesedocumentsabout",
    )
    return any(phrase in normalized for phrase in overview_phrases)


def _overview_documents() -> list[dict[str, str]]:
    """读取每篇有效 Markdown 的代表性原文，供全库概览使用。"""
    documents: list[dict[str, str]] = []
    for path in sorted(PARSED_DIR.glob("*.md")):
        text = path.read_text(encoding="utf-8").strip()
        if not text or _is_task_payload(text):
            continue
        cleaned = _clean_markdown_images(text)
        if not _is_meaningful_text(cleaned):
            continue

        # 开头通常包含标题、作者和摘要；末尾补充 Discussion/Conclusion。
        head = cleaned[:3500]
        tail_start = max(
            cleaned.lower().rfind("## discussion"),
            cleaned.lower().rfind("## conclusion"),
            cleaned.lower().rfind("## conclusions"),
            cleaned.lower().rfind("## 总结"),
            cleaned.lower().rfind("## 结论"),
        )
        tail = cleaned[tail_start:tail_start + 1500] if tail_start >= 0 else ""
        excerpt = head if not tail or tail in head else f"{head}\n\n{tail}"
        documents.append(
            {
                "doc_id": path.stem,
                "text": excerpt[:MAX_OVERVIEW_CHARS_PER_DOC],
            }
        )
        if len(documents) >= MAX_OVERVIEW_DOCS:
            break
    return documents


def _overview_sources_payload(question: str) -> dict[str, object]:
    documents = _overview_documents()
    return {
        "question": question,
        "mode": "corpus_overview",
        "count": len(documents),
        "sources": [
            {
                "doc_id": document["doc_id"],
                "node_id": None,
                "score": None,
                "text": document["text"],
                "truncated": False,
            }
            for document in documents
        ],
    }


def _query_corpus_overview(question: str) -> dict[str, Any]:
    """全库概览绕过低信息量向量查询，按文档代表性原文直接总结。"""
    _ensure_settings()
    with _storage_lock.read():
        documents = _overview_documents()
    sources = [
        {
            "doc_id": document["doc_id"],
            "node_id": None,
            "score": None,
            "text": document["text"],
        }
        for document in documents
    ]
    return _answer_from_sources(question, sources, overview=True)


def _upsert_document_with_metrics(
    doc_id: str,
    text: str,
    source_metadata: dict[str, Any] | None = None,
    content_list: list[dict[str, Any]] | None = None,
) -> tuple[str, int, dict[str, int]]:
    """切块与Embedding并行执行，只在提交Redis/DocStore时获取写锁。"""
    total_started = time.perf_counter()
    timings: dict[str, int] = {}
    _ensure_settings()
    safe_id = _safe_doc_id(doc_id)
    if not text.strip():
        raise ValueError("文档内容不能为空")
    metadata = _normalized_document_metadata(safe_id, text, source_metadata)

    parse_started = time.perf_counter()
    # Split the source text before attaching potentially long provenance.
    # Otherwise the metadata-aware splitter can reject the 128-token leaf
    # level when a title/URI/hash alone consumes that budget.
    document = Document(text=text, doc_id=safe_id)
    parser = HierarchicalNodeParser.from_defaults(chunk_sizes=CHUNK_SIZES)
    nodes = parser.get_nodes_from_documents([document])
    _annotate_node_structure(nodes, text)
    _attach_provenance_metadata(nodes, metadata)
    _annotate_node_pages(nodes, content_list)
    leaf_nodes = get_leaf_nodes(nodes)
    _sanitize_node_metadata_for_redis(leaf_nodes)
    timings["hierarchical_parse_ms"] = int(
        (time.perf_counter() - parse_started) * 1000
    )

    embedding_started = time.perf_counter()
    embeddings = Settings.embed_model.get_text_embedding_batch(
        [node.get_content(metadata_mode=MetadataMode.EMBED) for node in leaf_nodes],
        show_progress=False,
    )
    for node, embedding in zip(leaf_nodes, embeddings):
        node.embedding = embedding
    timings["embedding_ms"] = int(
        (time.perf_counter() - embedding_started) * 1000
    )

    commit_started = time.perf_counter()
    with _docstore_process_lock(), _storage_lock.write():
        if _deletion_marker(safe_id).exists():
            raise RuntimeError(f"文档已进入删除流程，拒绝重新写入: doc_id={safe_id}")
        load_started = time.perf_counter()
        vector_store = _make_vector_store(overwrite=False)
        docstore = _load_docstore()
        previous_docstore = SimpleDocumentStore.from_dict(docstore.to_dict())
        timings["storage_load_ms"] = int(
            (time.perf_counter() - load_started) * 1000
        )

        # 更新语义：只在旧文档存在时删除同 ref_doc_id 的向量和父子节点。
        delete_started = time.perf_counter()
        if docstore.get_ref_doc_info(safe_id) is not None:
            docstore.delete_ref_doc(safe_id, raise_error=False)
        # Always remove this ref_doc_id: a previous interrupted write may have
        # left vectors that were never committed to the document store.
        vector_store.delete(safe_id)
        timings["delete_previous_ms"] = int(
            (time.perf_counter() - delete_started) * 1000
        )

        storage_context = StorageContext.from_defaults(
            docstore=docstore,
            vector_store=vector_store,
        )
        storage_context.docstore.add_documents(nodes)
        vector_started = time.perf_counter()
        try:
            VectorStoreIndex(leaf_nodes, storage_context=storage_context)
            timings["redis_vector_write_ms"] = int(
                (time.perf_counter() - vector_started) * 1000
            )
            persist_started = time.perf_counter()
            _persist_docstore(
                storage_context.docstore,
                replace_doc_id=safe_id,
                nodes=nodes,
                document_metadata=metadata,
            )
        except Exception:
            logger.exception("索引提交失败，正在恢复 Redis 文档 doc_id=%s", safe_id)
            _restore_redis_document(vector_store, previous_docstore, safe_id)
            raise
        timings["docstore_persist_ms"] = int(
            (time.perf_counter() - persist_started) * 1000
        )

        markdown_started = time.perf_counter()
        PARSED_DIR.mkdir(parents=True, exist_ok=True)
        (PARSED_DIR / f"{safe_id}.md").write_text(text, encoding="utf-8")
        timings["markdown_write_ms"] = int(
            (time.perf_counter() - markdown_started) * 1000
        )
    timings["commit_lock_ms"] = int((time.perf_counter() - commit_started) * 1000)
    timings["total_index_ms"] = int((time.perf_counter() - total_started) * 1000)
    logger.info(
        "LlamaIndex增量索引完成 doc_id=%s leaf_nodes=%d timings=%s",
        safe_id,
        len(leaf_nodes),
        timings,
    )
    return safe_id, len(leaf_nodes), timings


def _delete_document(doc_id: str) -> dict[str, Any]:
    """幂等删除向量及本地索引状态，并校验Redis不存在残留节点。"""
    total_started = time.perf_counter()
    timings: dict[str, int] = {}
    safe_id = _safe_doc_id(doc_id)
    _mark_document_deleting(safe_id)

    with _docstore_process_lock(), _storage_lock.write():
        md_file = PARSED_DIR / f"{safe_id}.md"
        docstore = _load_docstore()
        docstore_exists = docstore.get_ref_doc_info(safe_id) is not None
        markdown_exists = md_file.exists()
        sqlite_exists = any(
            row.get("doc_id") == safe_id for row in knowledge_store.list_documents()
        )

        # 单调删除：失败后重试只需继续清理剩余存储，不回滚复活已删除的医疗数据。
        redis_started = time.perf_counter()
        redis_result = _delete_and_verify_redis_document(safe_id)
        timings["redis_delete_and_verify_ms"] = int(
            (time.perf_counter() - redis_started) * 1000
        )
        if docstore_exists:
            docstore.delete_ref_doc(safe_id, raise_error=False)
        # 即使DocStore已经缺失也执行replace删除，以清理SQLite/FTS孤儿记录。
        persist_started = time.perf_counter()
        _persist_docstore(docstore, replace_doc_id=safe_id)
        timings["sqlite_docstore_persist_ms"] = int(
            (time.perf_counter() - persist_started) * 1000
        )
        if md_file.exists():
            md_file.unlink()

        # _delete_and_verify_redis_document 已执行删除后索引校验，避免再次全量检查。
        final_remaining = int(redis_result["redis_nodes_remaining"])
        sqlite_remaining = knowledge_store.document_storage_counts(safe_id)
        docstore_remaining = docstore.get_ref_doc_info(safe_id) is not None
        markdown_remaining = md_file.exists()
        if final_remaining:
            raise RuntimeError(
                f"Redis删除后最终校验失败: doc_id={safe_id}, remaining={final_remaining}"
            )
        if docstore_remaining or markdown_remaining or any(sqlite_remaining.values()):
            raise RuntimeError(
                "本地索引删除后校验失败: "
                f"doc_id={safe_id}, docstore={docstore_remaining}, "
                f"markdown={markdown_remaining}, sqlite={sqlite_remaining}"
            )
        existed = bool(
            docstore_exists
            or markdown_exists
            or sqlite_exists
            or int(redis_result["redis_nodes_found"]) > 0
        )
        timings["total_delete_ms"] = int(
            (time.perf_counter() - total_started) * 1000
        )
        return {
            "doc_id": safe_id,
            "deleted": True,
            "previously_existed": existed,
            "docstore_deleted": docstore_exists,
            "sqlite_deleted": sqlite_exists,
            "markdown_deleted": markdown_exists,
            "deletion_marker": True,
            **redis_result,
            **sqlite_remaining,
            "docstore_remaining": docstore_remaining,
            "markdown_remaining": markdown_remaining,
            "redis_nodes_remaining": final_remaining,
            "verified": True,
            "timings": timings,
        }


@mcp.tool()
def index_document(
    doc_id: str,
    text: str,
    title: str | None = None,
    source_uri: str | None = None,
    content_sha256: str | None = None,
    page_number: int | None = None,
) -> dict[str, Any]:
    """增量新增或更新一个文档，不影响知识库中的其他文档。"""
    safe_id, leaf_count, timings = _upsert_document_with_metrics(
        doc_id,
        text,
        source_metadata={
            "title": title,
            "source_uri": source_uri,
            "content_sha256": content_sha256,
            "page_number": page_number,
        },
    )
    return ok({"doc_id": safe_id, "leaf_node_count": leaf_count, "timings": timings})


@mcp.tool()
def query_knowledge(question: str, top_k: int = RERANK_TOP_N) -> dict[str, Any]:
    """检索知识、注入LLM上下文并返回回答与可追溯来源JSON。"""
    query_started = time.perf_counter()
    if not question.strip():
        return error("invalid_question", "查询问题不能为空")
    top_k = max(1, min(top_k, 20))
    if _needs_medicine_clarification(question):
        profile = _medical_query_profile(question)
        return ok({
            "question": question,
            "answer": _clarification_message(),
            "plan": {"answer_type": "clarification", "sections": [], "focus": []},
            "validation": {"status": "not_run", "reason": "missing_medicine_entity"},
            "sources": [],
            "retrieval": {
                "mode": "clarification_required",
                "queries": [question],
                "query_profile": profile,
                "dense_candidates": 0,
                "bm25_candidates": 0,
                "reranked_anchors": 0,
                "evidence_contexts": 0,
                "rejected_by_threshold": False,
            },
        })
    try:
        _ensure_settings()
        outcome = _retrieve_context_nodes(question, top_k)
        sources = outcome.evidence_contexts
        sources = [source for source in sources if _is_approved_medical_source(source)]
        answer = _answer_from_sources(question, sources)
        answer["retrieval"] = {
            "mode": outcome.mode,
            "queries": outcome.queries,
            "query_profile": outcome.query_profile,
            "dense_candidates": outcome.dense_candidates,
            "bm25_candidates": outcome.bm25_candidates,
            "reranked_anchors": len(outcome.nodes),
            "evidence_contexts": len(outcome.evidence_contexts),
            "rejected_by_threshold": outcome.rejected_by_threshold,
            "timings": outcome.timings,
        }
        answer.setdefault("timings", {})["total_query_ms"] = int(
            (time.perf_counter() - query_started) * 1000
        )
        return ok(answer)
    except Exception as exc:
        logger.exception("知识库问答失败")
        return error("query_failed", str(exc))


@mcp.tool()
def retrieve_knowledge(question: str, top_k: int = RERANK_TOP_N) -> dict[str, Any]:
    """只执行知识库召回，不调用大模型，返回原始片段、文档ID和相似度。"""
    if not question.strip():
        return error("invalid_question", "检索问题不能为空")
    top_k = max(1, min(top_k, 20))
    if _needs_medicine_clarification(question):
        return ok({
            "question": question,
            "count": 0,
            "retrieval_top_k": RETRIEVAL_TOP_K,
            "rerank_top_n": top_k,
            "rerank_model": RERANK_MODEL,
            "retrieval_mode": "clarification_required",
            "clarification_required": True,
            "message": _clarification_message(),
            "queries": [question],
            "query_profile": _medical_query_profile(question),
            "dense_candidates": 0,
            "bm25_candidates": 0,
            "rejected_by_threshold": False,
            "sources": [],
        })
    try:
        _ensure_settings()
        outcome = _retrieve_context_nodes(question, top_k)
        return ok(
            {
                "question": question,
                "count": len(outcome.nodes),
                "retrieval_top_k": RETRIEVAL_TOP_K,
                "rerank_top_n": top_k,
                "rerank_model": RERANK_MODEL,
                "retrieval_mode": outcome.mode,
                "queries": outcome.queries,
                "query_profile": outcome.query_profile,
                "dense_candidates": outcome.dense_candidates,
                "bm25_candidates": outcome.bm25_candidates,
                "rejected_by_threshold": outcome.rejected_by_threshold,
                "timings": outcome.timings,
                "sources": [source for source in outcome.evidence_contexts
                            if _is_approved_medical_source(source)],
            }
        )
    except Exception as exc:
        logger.exception("知识库检索失败")
        return error("retrieval_failed", str(exc))


@mcp.tool()
def delete_document(doc_id: str) -> dict[str, Any]:
    """幂等删除文档并返回Redis删除数量与最终校验结果。"""
    safe_id = _safe_doc_id(doc_id)
    try:
        return ok(_delete_document(safe_id))
    except Exception as exc:
        logger.exception("文档删除失败 doc_id=%s", safe_id)
        return error("document_delete_failed", str(exc), doc_id=safe_id)


@mcp.tool()
def list_documents() -> dict[str, Any]:
    """列出SQLite中记录的知识库文档及来源元数据。"""
    documents = knowledge_store.list_documents()
    return ok({"count": len(documents), "documents": documents})


@mcp.tool()
def knowledge_base_status() -> dict[str, Any]:
    """返回本地存储、检索配置和知识库规模。"""
    return ok(
        {
            **knowledge_store.status(),
            "redis_url": REDIS_URL,
            "redis_index_name": REDIS_INDEX_NAME,
            "retrieval": {
                "chunk_sizes": CHUNK_SIZES,
                "dense_weight": HYBRID_DENSE_WEIGHT,
                "bm25_weight": HYBRID_BM25_WEIGHT,
                "exact_entity_boost": EXACT_ENTITY_BOOST,
                "section_match_boost": SECTION_MATCH_BOOST,
                "min_vector_score": MIN_VECTOR_SCORE,
                "min_rerank_score": MIN_RERANK_SCORE,
                "min_bm25_term_coverage": MIN_BM25_TERM_COVERAGE,
                "query_rewrite_enabled": QUERY_REWRITE_ENABLED,
                "query_rewrite_strategy": "low_recall_only",
                "answer_planning_mode": ANSWER_PLANNING_MODE,
                "answer_validation_mode": ANSWER_VALIDATION_MODE,
                "evidence_parent_max_chars": EVIDENCE_PARENT_MAX_CHARS,
                "evidence_neighbor_window": EVIDENCE_NEIGHBOR_WINDOW,
                "max_context_total_chars": MAX_CONTEXT_TOTAL_CHARS,
            },
        }
    )
