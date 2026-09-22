"""Adapter from the real local RAG tool envelope to evaluator fields."""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass

from mcp_server.tools.llamaIndex_tools import query_knowledge


@dataclass
class RagOutput:
    response: str
    retrieved_contexts: list[str]
    retrieved_context_ids: list[str]
    scores: list[float]
    latency_ms: float
    retrieval_mode: str


async def run_rag(question: str, top_k: int = 5) -> RagOutput:
    started = time.perf_counter()
    envelope = await asyncio.to_thread(query_knowledge, question=question, top_k=top_k)
    if not envelope.get("ok"):
        error = envelope.get("error") or {}
        raise RuntimeError(f"{error.get('code', 'query_failed')}: {error.get('message', error)}")
    result = envelope["data"]
    sources = result.get("sources", [])
    retrieval = result.get("retrieval", {})
    return RagOutput(
        response=str(result.get("answer", "")),
        retrieved_contexts=[str(item.get("quote", "")) for item in sources],
        retrieved_context_ids=[str(item.get("chunkId", "")) for item in sources],
        scores=[float(item.get("score") or 0.0) for item in sources],
        latency_ms=(time.perf_counter() - started) * 1000,
        retrieval_mode=str(retrieval.get("mode", "unknown")),
    )
