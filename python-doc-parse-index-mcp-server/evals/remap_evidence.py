"""Legacy migration: fuzzily remap old Ragas contexts to production leaves.

This avoids another paid Ragas generation run, but it cannot repair question/reference
quality or guarantee exact provenance. New benchmarks should use generate_questions.py.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evals.dataset import (
    DEFAULT_DB,
    export_review_csv,
    load_corpus,
    match_evidence,
    read_jsonl,
    write_jsonl,
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        type=Path,
        default=ROOT / "evals" / "datasets" / "candidates.jsonl",
        help="Existing Ragas candidates containing reference_contexts_generated.",
    )
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "evals" / "datasets" / "candidates_leaf.jsonl",
    )
    parser.add_argument(
        "--review-csv",
        type=Path,
        default=ROOT / "evals" / "datasets" / "candidate_review_leaf.csv",
    )
    args = parser.parse_args()

    candidates = read_jsonl(args.input)
    leaf_corpus = load_corpus(args.db, leaf_only=True)
    remapped = []
    without_evidence = 0
    for source in candidates:
        row = dict(source)
        contexts = row.get("reference_contexts_generated") or row.get("reference_contexts") or []
        if isinstance(contexts, str):
            contexts = [contexts]
        evidence = match_evidence([str(value) for value in contexts], leaf_corpus)
        if not evidence:
            without_evidence += 1
        row["evidence"] = evidence
        # Evidence changed, so any previous approval must be reviewed again.
        row["review_status"] = "pending"
        row["question_verified"] = False
        row["evidence_verified"] = False
        row["reviewer_notes"] = ""
        remapped.append(row)

    write_jsonl(args.output, remapped)
    export_review_csv(args.review_csv, remapped)
    print(
        f"candidates={len(remapped)} leaf_chunks={len(leaf_corpus)} "
        f"without_evidence={without_evidence} output={args.output} "
        f"review={args.review_csv}"
    )


if __name__ == "__main__":
    main()
