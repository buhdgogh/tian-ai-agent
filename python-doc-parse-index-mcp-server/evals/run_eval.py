"""Run the real RAG and judge end-to-end answers with Ragas metrics."""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import os
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv

load_dotenv(ROOT / ".env")

from evals.dataset import read_jsonl
from evals.rag_adapter import run_rag


def id_recall(retrieved_ids: list[str], reference_ids: list[str]) -> float:
    expected = set(reference_ids)
    return len(expected & set(retrieved_ids)) / len(expected) if expected else 0.0


def hit_at_k(retrieved_ids: list[str], reference_ids: list[str], k: int) -> float:
    return float(bool(set(retrieved_ids[:k]) & set(reference_ids)))


def export_ares_tsv(path: Path, rows: list[dict[str, Any]]) -> None:
    """Export genuine query-document-answer triples without fabricated labels."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["Query", "Document", "Answer"], delimiter="\t")
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "Query": row["user_input"],
                    "Document": "\n\n".join(row["retrieved_contexts"]),
                    "Answer": row["response"],
                }
            )


async def run(args: argparse.Namespace) -> None:
    try:
        from openai import AsyncOpenAI
        from ragas.llms import llm_factory
        from ragas.metrics.collections import AnswerAccuracy, ContextPrecision, ContextRecall, Faithfulness
    except ImportError as exc:
        missing = getattr(exc, "name", None) or str(exc)
        raise SystemExit(
            f"evaluation dependency import failed: {missing}\n"
            'repair this environment with: python -m pip install -U --upgrade-strategy eager -e ".[eval]"'
        ) from exc

    api_key = os.getenv("EVAL_LLM_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("EVAL_LLM_API_KEY is required in .env or the process environment")
    client = AsyncOpenAI(api_key=api_key, base_url=os.getenv("EVAL_LLM_BASE_URL") or None)
    evaluator_llm = llm_factory(os.getenv("EVAL_LLM_MODEL", "gpt-4o-mini"), client=client)
    metrics = {
        "context_precision": ContextPrecision(llm=evaluator_llm),
        "context_recall": ContextRecall(llm=evaluator_llm),
        "faithfulness": Faithfulness(llm=evaluator_llm),
        "answer_accuracy": AnswerAccuracy(llm=evaluator_llm),
    }
    dataset = read_jsonl(args.dataset)
    results = []
    for row in dataset:
        try:
            output = await run_rag(row["user_input"], top_k=args.top_k)
            common = {"user_input": row["user_input"], "retrieved_contexts": output.retrieved_contexts}
            precision = await metrics["context_precision"].ascore(**common, reference=row["reference"])
            recall = await metrics["context_recall"].ascore(**common, reference=row["reference"])
            faithfulness = await metrics["faithfulness"].ascore(**common, response=output.response)
            accuracy = await metrics["answer_accuracy"].ascore(
                user_input=row["user_input"], response=output.response, reference=row["reference"]
            )
            reference_ids = [str(value) for value in row.get("reference_context_ids", [])]
            results.append(
                {
                    "id": row["id"],
                    "user_input": row["user_input"],
                    "reference": row["reference"],
                    "response": output.response,
                    "retrieved_contexts": output.retrieved_contexts,
                    "retrieved_context_ids": output.retrieved_context_ids,
                    "retrieval_mode": output.retrieval_mode,
                    "context_precision": float(precision.value),
                    "context_recall": float(recall.value),
                    "faithfulness": float(faithfulness.value),
                    "answer_accuracy": float(accuracy.value),
                    f"id_recall@{args.top_k}": id_recall(output.retrieved_context_ids, reference_ids),
                    "hit@1": hit_at_k(output.retrieved_context_ids, reference_ids, 1),
                    f"hit@{args.top_k}": hit_at_k(output.retrieved_context_ids, reference_ids, args.top_k),
                    "latency_ms": round(output.latency_ms, 3),
                }
            )
        except Exception as exc:
            results.append({"id": row["id"], "user_input": row["user_input"], "error": f"{type(exc).__name__}: {exc}"})

    successful = [row for row in results if "error" not in row]
    score_fields = (
        "context_precision",
        "context_recall",
        "faithfulness",
        "answer_accuracy",
        f"id_recall@{args.top_k}",
        "hit@1",
        f"hit@{args.top_k}",
    )
    summary = {
        "cases": len(results),
        "successful": len(successful),
        "errors": len(results) - len(successful),
        **{
            field: round(sum(float(row[field]) for row in successful) / len(successful), 6)
            if successful
            else None
            for field in score_fields
        },
        "mean_latency_ms": round(sum(row["latency_ms"] for row in successful) / len(successful), 3)
        if successful
        else None,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    export_ares_tsv(args.ares_output, successful)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "evals" / "datasets" / "gold.jsonl")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--output", type=Path, default=ROOT / "evals" / "results" / "end_to_end.json")
    parser.add_argument("--ares-output", type=Path, default=ROOT / "evals" / "results" / "ares_unlabeled.tsv")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
