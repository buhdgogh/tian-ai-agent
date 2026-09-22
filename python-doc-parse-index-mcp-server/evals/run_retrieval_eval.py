"""Evaluate BM25, dense, hybrid, and reranked retrieval on one BEIR split."""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv

load_dotenv(ROOT / ".env")

from evals.dataset import read_jsonl
from evals.retrievers import RETRIEVER_NAMES, retrieve


def load_qrels(path: Path) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    with path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            result.setdefault(row["query-id"], {})[row["corpus-id"]] = int(row["score"])
    return result


def score_query(ranked_ids: list[str], relevant: dict[str, int], k: int) -> dict[str, float]:
    ranked = ranked_ids[:k]
    relevant_ids = {key for key, score in relevant.items() if score > 0}
    hits = [index for index, item in enumerate(ranked, start=1) if item in relevant_ids]
    recall = len(set(ranked) & relevant_ids) / len(relevant_ids) if relevant_ids else 0.0
    reciprocal_rank = 1.0 / hits[0] if hits else 0.0
    precisions = [sum(value in relevant_ids for value in ranked[:rank]) / rank for rank in hits]
    average_precision = (
        sum(precisions) / min(len(relevant_ids), k) if relevant_ids else 0.0
    )
    dcg = sum(1.0 / math.log2(rank + 1) for rank in hits)
    ideal_hits = min(len(relevant_ids), k)
    idcg = sum(1.0 / math.log2(rank + 1) for rank in range(1, ideal_hits + 1))
    return {
        f"recall@{k}": recall,
        f"mrr@{k}": reciprocal_rank,
        f"map@{k}": average_precision,
        f"ndcg@{k}": dcg / idcg if idcg else 0.0,
    }


def evaluate_system(
    name: str, queries: list[dict[str, Any]], qrels: dict[str, dict[str, int]], top_k: int
) -> dict[str, Any]:
    rows = []
    for query in queries:
        started = time.perf_counter()
        try:
            results = retrieve(query["text"], name, top_k)
            ranked_ids = [str(item["node_id"]) for item in results]
            rows.append(
                {
                    "query_id": query["_id"],
                    "ranked_ids": ranked_ids,
                    "latency_ms": round((time.perf_counter() - started) * 1000, 3),
                    **score_query(ranked_ids, qrels.get(query["_id"], {}), top_k),
                }
            )
        except Exception as exc:
            rows.append(
                {
                    "query_id": query["_id"],
                    "ranked_ids": [],
                    "latency_ms": round((time.perf_counter() - started) * 1000, 3),
                    "error": f"{type(exc).__name__}: {exc}",
                }
            )
    successful = [row for row in rows if "error" not in row]
    metric_names = (f"recall@{top_k}", f"mrr@{top_k}", f"map@{top_k}", f"ndcg@{top_k}")
    summary = {
        "retriever": name,
        "queries": len(rows),
        "successful": len(successful),
        "errors": len(rows) - len(successful),
        **{
            metric: round(sum(row[metric] for row in successful) / len(successful), 6)
            if successful
            else None
            for metric in metric_names
        },
        "mean_latency_ms": round(sum(row["latency_ms"] for row in successful) / len(successful), 3)
        if successful
        else None,
    }
    return {"summary": summary, "queries": rows}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--beir-dir", type=Path, default=ROOT / "evals" / "beir" / "current-rag")
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--retrievers", nargs="+", choices=RETRIEVER_NAMES, default=list(RETRIEVER_NAMES))
    parser.add_argument("--output", type=Path, default=ROOT / "evals" / "results" / "retrieval.json")
    args = parser.parse_args()

    queries = read_jsonl(args.beir_dir / "queries.jsonl")
    qrels = load_qrels(args.beir_dir / "qrels" / "test.tsv")
    unknown = [row["_id"] for row in queries if row["_id"] not in qrels]
    if unknown:
        raise SystemExit(f"queries without qrels: {unknown}")
    systems = [evaluate_system(name, queries, qrels, args.top_k) for name in args.retrievers]
    report = {"top_k": args.top_k, "systems": systems}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps([item["summary"] for item in systems], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
