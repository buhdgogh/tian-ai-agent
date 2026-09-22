"""Validate a completed human-review CSV and materialize the gold dataset."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evals.dataset import DEFAULT_DB, load_corpus, load_review_csv, write_jsonl


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "evals" / "datasets" / "candidate_review.csv")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--output", type=Path, default=ROOT / "evals" / "datasets" / "gold.jsonl")
    args = parser.parse_args()
    approved = load_review_csv(args.input, load_corpus(args.db, leaf_only=True))
    write_jsonl(args.output, approved)
    print(f"approved={len(approved)} output={args.output}")


if __name__ == "__main__":
    main()
