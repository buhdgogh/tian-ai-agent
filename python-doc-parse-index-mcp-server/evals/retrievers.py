"""Comparable adapters for the four retrieval stages in the current RAG."""

from __future__ import annotations

from typing import Any

from llama_index.core.schema import NodeWithScore

from mcp_server.tools.llamaIndex_tools import (
    MIN_VECTOR_SCORE,
    RETRIEVAL_TOP_K,
    _build_retriever,
    _ensure_settings,
    _filter_retrieved_nodes,
    _hybrid_candidates,
    _leaf_node_ids,
    _load_docstore,
    _medical_query_profile,
    _retrieve_context_nodes,
    _search_bm25_leaf,
    _rewrite_queries,
    _source_dict,
)


RETRIEVER_NAMES = ("bm25", "dense", "hybrid", "reranker")


def _dedupe(nodes: list[NodeWithScore], top_k: int) -> list[NodeWithScore]:
    seen: set[str] = set()
    result = []
    for item in nodes:
        node_id = str(item.node.node_id)
        if node_id in seen:
            continue
        seen.add(node_id)
        result.append(item)
        if len(result) >= top_k:
            break
    return result


def retrieve(question: str, mode: str, top_k: int) -> list[dict[str, Any]]:
    """Return ranked leaf anchors at a retrieval stage, without parent expansion."""
    if mode not in RETRIEVER_NAMES:
        raise ValueError(f"unknown retriever: {mode}")
    top_k = max(1, top_k)
    if mode == "bm25":
        docstore = _load_docstore()
        rows = _search_bm25_leaf(
            question, max(top_k, RETRIEVAL_TOP_K), _leaf_node_ids(docstore)
        )[:top_k]
        return [
            {
                "node_id": str(row["node_id"]),
                "doc_id": str(row["doc_id"]),
                "title": row.get("title"),
                "text": row.get("text", ""),
                "score": -float(row.get("rank", 0.0)),
            }
            for row in rows
        ]

    _ensure_settings()
    if mode == "reranker":
        outcome = _retrieve_context_nodes(question, top_k)
        return [_source_dict(item, max_chars=5000) for item in outcome.nodes]

    docstore = _load_docstore()
    leaf_ids = _leaf_node_ids(docstore)
    if mode == "dense":
        retriever = _build_retriever(max(top_k, RETRIEVAL_TOP_K), docstore)
        nodes = [
            item
            for item in _filter_retrieved_nodes(retriever.retrieve(question))
            if str(item.node.node_id) in leaf_ids
            if float(item.score or 0.0) >= MIN_VECTOR_SCORE
        ]
    else:
        nodes, _, _ = _hybrid_candidates(
            _rewrite_queries(question, use_llm=False),
            docstore,
            _medical_query_profile(question),
        )
    return [_source_dict(item, max_chars=5000) for item in _dedupe(nodes, top_k)]
