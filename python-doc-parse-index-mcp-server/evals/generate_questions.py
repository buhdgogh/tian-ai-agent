"""Generate Ragas candidate questions and a human-review worksheet."""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evals.dataset import (
    DEFAULT_DB,
    export_review_csv,
    load_controlled_evidence_bundles,
    load_corpus,
    match_controlled_bundle_evidence,
    match_prechunked_evidence,
    write_jsonl,
)


def select_source_units_round_robin(
    corpus: list[dict[str, Any]], limit: int
) -> list[dict[str, Any]]:
    """Interleave source-ordered units across documents deterministically."""
    by_doc: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in corpus:
        by_doc[str(row["metadata"]["doc_id"])].append(row)
    for rows in by_doc.values():
        rows.sort(
            key=lambda row: (
                row["metadata"].get("char_start")
                if isinstance(row["metadata"].get("char_start"), (int, float))
                else 0,
                str(row["_id"]),
            )
        )

    document_ids = sorted(by_doc)
    target = len(corpus) if limit <= 0 else min(limit, len(corpus))
    selected: list[dict[str, Any]] = []
    round_index = 0
    while len(selected) < target:
        added = False
        for doc_id in document_ids:
            rows = by_doc[doc_id]
            if round_index < len(rows):
                selected.append(rows[round_index])
                added = True
                if len(selected) == target:
                    break
        if not added:
            break
        round_index += 1
    return selected


def is_generation_unit_eligible(row: dict[str, Any]) -> bool:
    """Reject fragments that are unlikely to support a self-contained question."""
    text = str(row.get("text", "")).strip()
    if len(text) < 100:
        return False
    lowered = text.lower()
    has_table_cells = "<td" in lowered or "</tr>" in lowered
    has_table_boundary = "<table" in lowered and "</table>" in lowered
    if has_table_cells and not has_table_boundary:
        return False
    return True


_CJK_RE = re.compile(r"[\u3400-\u9fff]")
_AMBIGUOUS_TABLE_RE = re.compile(
    r"(?:第[一二三四五六七八九十\d]+列|第[一二三四五六七八九十\d]+行|"
    r"first column|second column|third column|fourth column|in the context)",
    flags=re.IGNORECASE,
)
_RATE_QUESTION_RE = re.compile(r"发生率|比例|百分比|incidence|rate|percentage", re.IGNORECASE)
_NUMBER_RE = re.compile(r"\d+(?:\.\d+)?")


def candidate_quality_warnings(
    question: str, reference: str, evidence_count: int
) -> list[str]:
    """Add deterministic review warnings; these are not automatic rejection labels."""
    warnings: list[str] = []
    if not _CJK_RE.search(question):
        warnings.append("non_chinese_question")
    if not _CJK_RE.search(reference):
        warnings.append("non_chinese_reference")
    if _AMBIGUOUS_TABLE_RE.search(reference):
        warnings.append("ambiguous_table_reference")
    if (
        _RATE_QUESTION_RE.search(question)
        and _NUMBER_RE.search(reference)
        and "%" not in reference
        and "百分之" not in reference
    ):
        warnings.append("possible_missing_unit_in_reference")
    if evidence_count > 4:
        warnings.append(f"review_candidate_evidence_count={evidence_count}")
    return warnings


def frame_value(row: Any, name: str, default: Any = "") -> Any:
    value = row.get(name, default)
    if value is None:
        return default
    return value


def context_list(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, (list, tuple)):
        return [str(item) for item in value if str(item).strip()]
    return []


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", type=int, default=30)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument(
        "--max-source-units",
        "--max-source-chunks",
        dest="max_source_units",
        type=int,
        default=None,
        help=(
            "Maximum controlled bundles (or leaf chunks) sent to Ragas. "
            "Default is twice --size; 0 means all."
        ),
    )
    parser.add_argument(
        "--source-mode",
        choices=("controlled-bundles", "leaf-chunks"),
        default="controlled-bundles",
        help=(
            "controlled-bundles uses each production parent plus its traceable leaf IDs; "
            "leaf-chunks retains the older one-leaf-per-context behavior"
        ),
    )
    parser.add_argument(
        "--transform-mode",
        choices=("auto", "optimized", "ragas-default"),
        default="auto",
        help=(
            "auto uses lightweight single-hop transforms and Ragas defaults for mixed mode"
        ),
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=4,
        help="Maximum concurrent Ragas jobs; lower this if the model endpoint rate-limits.",
    )
    parser.add_argument(
        "--query-mode",
        choices=("single-hop", "mixed"),
        default="single-hop",
        help="Use single-hop by default; mixed enables Ragas multi-hop synthesis.",
    )
    parser.add_argument("--output", type=Path, default=ROOT / "evals" / "datasets" / "candidates.jsonl")
    parser.add_argument("--review-csv", type=Path, default=ROOT / "evals" / "datasets" / "candidate_review.csv")
    args = parser.parse_args()
    if args.size < 1:
        raise SystemExit("--size must be at least 1")
    if args.max_source_units is not None and args.max_source_units < 0:
        raise SystemExit("--max-source-units must be 0 or a positive integer")

    tracing_enabled = os.getenv("EVAL_ENABLE_LANGSMITH_TRACING", "").strip().lower() in {
        "1", "true", "yes", "on"
    }
    if not tracing_enabled:
        os.environ["LANGSMITH_TRACING"] = "false"
        os.environ["LANGCHAIN_TRACING_V2"] = "false"
        os.environ["LANGCHAIN_TRACING"] = "false"
    os.environ.setdefault("RAGAS_DO_NOT_TRACK", "true")

    try:
        from dotenv import load_dotenv
        from langchain_core.documents import Document as LangchainDocument
        from llama_index.embeddings.dashscope import DashScopeEmbedding
        from llama_index.llms.dashscope import DashScope
        from ragas.testset import TestsetGenerator
        from ragas.run_config import RunConfig
        from ragas.testset.persona import Persona
        from ragas.testset.synthesizers.single_hop.specific import (
            SingleHopSpecificQuerySynthesizer,
        )
    except ImportError as exc:
        missing = getattr(exc, "name", None) or str(exc)
        raise SystemExit(
            f"evaluation dependency import failed: {missing}\n"
            f"original import error: {exc}\n"
            'repair this environment with: python -m pip install -U --upgrade-strategy eager -e ".[eval]"'
        ) from exc

    load_dotenv(ROOT / ".env")
    api_key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("DASHSCOPE_API_KEY is required in .env or the process environment")
    leaf_corpus = load_corpus(args.db, leaf_only=True)
    if args.source_mode == "controlled-bundles":
        generation_units = load_controlled_evidence_bundles(args.db)
    else:
        generation_units = leaf_corpus
    eligible_units = [row for row in generation_units if is_generation_unit_eligible(row)]
    if not eligible_units:
        raise SystemExit("no self-contained generation units remain after quality filtering")
    source_limit = args.max_source_units
    if source_limit is None:
        source_limit = max(args.size * 2, 1)
    source_corpus = select_source_units_round_robin(eligible_units, source_limit)
    chunks = [
        LangchainDocument(
            page_content=str(row["text"]),
            metadata={
                "chunk_id": str(row["_id"]),
                "doc_id": str(row["metadata"]["doc_id"]),
                "title": str(row["title"]),
                "section": row["metadata"].get("section"),
                "evidence_chunk_ids": list(row.get("evidence_chunk_ids", [str(row["_id"])])),
            },
        )
        for row in source_corpus
    ]
    generator_llm = DashScope(
        model_name=os.getenv("EVAL_GENERATOR_MODEL", os.getenv("LLM_MODEL", "qwen-plus")),
        temperature=0.2,
        api_key=api_key,
    )
    embeddings = DashScopeEmbedding(
        model_name=os.getenv("EMBEDDING_MODEL", "text-embedding-v3"),
        embed_batch_size=10,
        api_key=api_key,
    )
    generation_context = (
        "创建中文药品说明书检索评测集。问题和参考答案必须全部使用简体中文。"
        "Every question must be fully and "
        "unambiguously answerable from the supplied context. The reference answer must "
        "answer every clause of the question and must not add facts absent from the context. "
        "Preserve the exact medicine, formulation, route, population, indication, combination "
        "therapy, negation, number, and unit. Never merge similarly named medicines or "
        "different formulations. Prefer focused questions over broad exhaustive requests."
        " The supplied context may be a controlled bundle of adjacent production leaf chunks."
        " A compound question is allowed only when every clause is explicitly supported by that bundle."
        " Preserve table headers and the meaning of every row and column; never answer with"
        " phrases such as first column, second column, or 'in the context'. Every numeric"
        " value must retain its subject, treatment group, comparator, unit, and denominator"
        " when present. If these are unavailable, do not generate a table-based question."
    )
    generator = TestsetGenerator.from_llama_index(
        generator_llm,
        embeddings,
        llm_context=generation_context,
    )
    generator.persona_list = [
        Persona(name="患者或照护者", role_description="希望准确理解药品说明书中的使用与风险信息。"),
        Persona(name="药师", role_description="核对药品成份、适应症、用法和安全性信息。"),
        Persona(name="医疗信息审核员", role_description="关注说明书表述、剂型、数值和证据完整性。"),
    ]
    query_distribution = None
    if args.query_mode == "single-hop":
        query_distribution = [
            (
                SingleHopSpecificQuerySynthesizer(
                    llm=generator.llm,
                    llm_context=generation_context,
                ),
                1.0,
            )
        ]
    transform_mode = args.transform_mode
    if transform_mode == "auto":
        transform_mode = "optimized" if args.query_mode == "single-hop" else "ragas-default"
    if transform_mode == "optimized" and args.query_mode != "single-hop":
        raise SystemExit("--transform-mode optimized currently supports --query-mode single-hop only")
    transforms = None
    if transform_mode == "optimized":
        # SingleHopSpecificQuerySynthesizer only requires the `entities` property.
        # Fixed personas remove the need for summary embeddings and relationship builders.
        try:
            # Ragas 0.4.x does not export NERExtractor from transforms.__init__.
            from ragas.testset.transforms.extractors import NERExtractor
        except ImportError as exc:
            raise SystemExit(
                "optimized transform import failed; the installed Ragas API is incompatible:\n"
                f"{exc}"
            ) from exc
        transforms = [NERExtractor(llm=generator.llm)]
    run_config = RunConfig(
        max_workers=max(1, args.max_workers),
        max_retries=3,
        max_wait=10,
        timeout=120,
    )
    generate = getattr(generator, "generate_with_chunks", None)
    if generate is None:
        raise SystemExit("this Ragas version does not support generate_with_chunks")
    testset = generate(
        chunks,
        testset_size=max(1, args.size),
        transforms=transforms,
        query_distribution=query_distribution,
        run_config=run_config,
        raise_exceptions=True,
    )
    frame = testset.to_pandas()

    candidates = []
    for index, (_, source) in enumerate(frame.iterrows(), start=1):
        contexts = context_list(frame_value(source, "reference_contexts", []))
        if args.source_mode == "controlled-bundles":
            evidence, alignment = match_controlled_bundle_evidence(
                contexts, source_corpus, leaf_corpus
            )
        else:
            evidence, alignment = match_prechunked_evidence(contexts, leaf_corpus)
        question = str(frame_value(source, "user_input")).strip()
        reference = str(frame_value(source, "reference")).strip()
        warnings = candidate_quality_warnings(question, reference, len(evidence))
        if alignment["unmapped_context_indexes"]:
            warnings.append(
                "unmapped_reference_contexts="
                + ",".join(str(value) for value in alignment["unmapped_context_indexes"])
            )
        if alignment["ambiguous_context_matches"]:
            warnings.append("ambiguous_exact_context_match")
        candidates.append(
            {
                "id": f"ragas-{index:04d}",
                "user_input": question,
                "reference": reference,
                "reference_contexts_generated": contexts,
                "evidence": evidence,
                "evidence_alignment": alignment,
                "synthesizer_name": str(frame_value(source, "synthesizer_name")),
                "source_mode": args.source_mode,
                "source_sampling": "document_round_robin",
                "transform_mode": transform_mode,
                "evidence_bundle_ids": alignment.get("mapped_bundle_ids", []),
                "generation_warnings": warnings,
                "review_status": "pending",
                "question_verified": False,
                "evidence_verified": False,
                "reviewer_notes": "",
            }
        )
    write_jsonl(args.output, candidates)
    export_review_csv(args.review_csv, candidates)
    warned = sum(bool(row["generation_warnings"]) for row in candidates)
    source_documents = len(
        {str(row["metadata"]["doc_id"]) for row in source_corpus}
    )
    print(
        f"generated={len(candidates)} source_mode={args.source_mode} "
        f"transform_mode={transform_mode} source_sampling=document_round_robin "
        f"source_units={len(source_corpus)} source_documents={source_documents} "
        f"warnings={warned} candidates={args.output} review={args.review_csv}"
    )


if __name__ == "__main__":
    main()
