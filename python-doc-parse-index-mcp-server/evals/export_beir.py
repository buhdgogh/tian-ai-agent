"""Export the approved benchmark to BEIR corpus/queries/qrels files."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evals.dataset import DEFAULT_DB, load_corpus, read_jsonl, write_jsonl


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gold", type=Path, default=ROOT / "evals" / "datasets" / "gold.jsonl")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "evals" / "beir" / "current-rag")
    args = parser.parse_args()

    gold = read_jsonl(args.gold)
    # Keep the BEIR corpus aligned with the production Redis vector index.
    corpus = load_corpus(args.db, leaf_only=True)
    valid_ids = {str(row["_id"]) for row in corpus}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(args.output_dir / "corpus.jsonl", corpus)
    write_jsonl(
        args.output_dir / "queries.jsonl",
        ({"_id": row["id"], "text": row["user_input"], "metadata": {}} for row in gold),
    )
    qrels_dir = args.output_dir / "qrels"
    qrels_dir.mkdir(parents=True, exist_ok=True)
    with (qrels_dir / "test.tsv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(["query-id", "corpus-id", "score"])
        for row in gold:
            ids = [str(value) for value in row.get("reference_context_ids", [])]
            missing = [value for value in ids if value not in valid_ids]
            if missing:
                raise ValueError(f"{row['id']}: qrels refer to missing corpus IDs: {missing}")
            for corpus_id in ids:
                writer.writerow([row["id"], corpus_id, 1])
    manifest = {
        "format": "BEIR",
        "split": "test",
        "queries": len(gold),
        "corpus": len(corpus),
        "qrels": sum(len(row.get("reference_context_ids", [])) for row in gold),
    }
    (args.output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
