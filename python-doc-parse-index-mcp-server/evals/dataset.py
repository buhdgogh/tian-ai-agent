"""Shared JSONL, review, corpus, and relevance helpers."""

from __future__ import annotations

import csv
import json
import re
import sqlite3
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB = ROOT / "mcp_server" / "storage" / "knowledge.db"
REVIEW_FIELDS = (
    "id",
    "user_input",
    "reference",
    "evidence_chunk_ids",
    "evidence_preview",
    "source_titles",
    "synthesizer_name",
    "generation_warnings",
    "review_status",
    "question_verified",
    "evidence_verified",
    "reviewer_notes",
)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_leaf_node_ids(db_path: Path = DEFAULT_DB) -> set[str]:
    """Return the production vector-index leaf IDs from the persisted docstore."""
    if not db_path.exists():
        raise FileNotFoundError(f"knowledge database not found: {db_path}")
    connection = sqlite3.connect(db_path)
    try:
        row = connection.execute(
            "SELECT payload FROM state WHERE key='docstore'"
        ).fetchone()
    finally:
        connection.close()
    if row is None:
        raise ValueError("knowledge database has no persisted docstore state")

    from llama_index.core.node_parser import get_leaf_nodes
    from llama_index.core.storage.docstore import SimpleDocumentStore

    docstore = SimpleDocumentStore.from_dict(json.loads(str(row[0])))
    return {
        str(node.node_id)
        for node in get_leaf_nodes(list(docstore.docs.values()))
    }


def load_controlled_evidence_bundles(
    db_path: Path = DEFAULT_DB,
) -> list[dict[str, Any]]:
    """Build traceable generation contexts from production parent/leaf relations.

    Each bundle is the text of one immediate production parent and the IDs of
    all of its leaf children.  This mirrors the small-chunk retrieval plus
    parent-context expansion used by the application while keeping retrieval
    relevance labels at leaf granularity.
    """
    if not db_path.exists():
        raise FileNotFoundError(f"knowledge database not found: {db_path}")

    connection = sqlite3.connect(db_path)
    try:
        row = connection.execute(
            "SELECT payload FROM state WHERE key='docstore'"
        ).fetchone()
    finally:
        connection.close()
    if row is None:
        raise ValueError("knowledge database has no persisted docstore state")

    from llama_index.core.node_parser import get_leaf_nodes
    from llama_index.core.schema import NodeRelationship
    from llama_index.core.storage.docstore import SimpleDocumentStore

    docstore = SimpleDocumentStore.from_dict(json.loads(str(row[0])))
    leaves = get_leaf_nodes(list(docstore.docs.values()))
    leaf_corpus = load_corpus(db_path, leaf_only=True)
    corpus_by_id = {str(item["_id"]): item for item in leaf_corpus}
    children_by_parent: dict[str, list[Any]] = {}
    parentless: list[Any] = []
    for leaf in leaves:
        parent = leaf.relationships.get(NodeRelationship.PARENT)
        if parent is None:
            parentless.append(leaf)
            continue
        children_by_parent.setdefault(str(parent.node_id), []).append(leaf)

    bundles: list[dict[str, Any]] = []
    for parent_id, children in children_by_parent.items():
        children.sort(
            key=lambda node: (
                node.start_char_idx if node.start_char_idx is not None else 0,
                str(node.node_id),
            )
        )
        child_ids = [str(node.node_id) for node in children if str(node.node_id) in corpus_by_id]
        if not child_ids:
            continue
        parent = docstore.get_node(parent_id)
        first = corpus_by_id[child_ids[0]]
        bundles.append(
            {
                "_id": f"bundle:{parent_id}",
                "title": str(first["title"]),
                "text": str(parent.get_content()),
                "evidence_chunk_ids": child_ids,
                "metadata": {
                    "doc_id": str(first["metadata"]["doc_id"]),
                    "parent_chunk_id": parent_id,
                    "section": parent.metadata.get("section"),
                    "section_path": parent.metadata.get("section_path"),
                    "char_start": parent.metadata.get("char_start"),
                    "char_end": parent.metadata.get("char_end"),
                },
            }
        )

    # This is defensive for indexes produced without a hierarchical parser.
    for leaf in parentless:
        leaf_id = str(leaf.node_id)
        item = corpus_by_id.get(leaf_id)
        if item is None:
            continue
        bundles.append(
            {
                "_id": f"bundle:{leaf_id}",
                "title": str(item["title"]),
                "text": str(item["text"]),
                "evidence_chunk_ids": [leaf_id],
                "metadata": dict(item["metadata"]),
            }
        )

    bundles.sort(
        key=lambda item: (
            str(item["metadata"]["doc_id"]),
            item["metadata"].get("char_start")
            if isinstance(item["metadata"].get("char_start"), (int, float))
            else 0,
            str(item["_id"]),
        )
    )
    if not bundles:
        raise ValueError("no controlled evidence bundles could be built")
    return bundles


def load_corpus(
    db_path: Path = DEFAULT_DB,
    *,
    leaf_only: bool = False,
) -> list[dict[str, Any]]:
    """Load indexed chunks from SQLite, optionally restricted to vector leaves."""
    if not db_path.exists():
        raise FileNotFoundError(f"knowledge database not found: {db_path}")
    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute(
            """SELECT node_id, doc_id, COALESCE(title, '') AS title, text,
                      page_number, source_uri, content_sha256, metadata_json
               FROM chunks ORDER BY doc_id, node_id"""
        ).fetchall()
    finally:
        connection.close()
    corpus = []
    for row in rows:
        try:
            stored_metadata = json.loads(str(row["metadata_json"] or "{}"))
        except json.JSONDecodeError:
            stored_metadata = {}
        corpus.append({
            "_id": str(row["node_id"]),
            "title": str(row["title"]),
            "text": str(row["text"]),
            "metadata": {
                "doc_id": str(row["doc_id"]),
                "page_number": row["page_number"],
                "source_uri": row["source_uri"],
                "content_sha256": row["content_sha256"],
                "section": stored_metadata.get("section"),
                "section_path": stored_metadata.get("section_path"),
                "char_start": stored_metadata.get("char_start"),
                "char_end": stored_metadata.get("char_end"),
            },
        })
    if not leaf_only:
        return corpus
    leaf_ids = load_leaf_node_ids(db_path)
    leaf_corpus = [row for row in corpus if str(row["_id"]) in leaf_ids]
    if not leaf_corpus:
        raise ValueError("no leaf chunks found in the persisted docstore")
    return leaf_corpus


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", "", value).lower()


def text_shingles(value: str, width: int = 3) -> set[str]:
    normalized = normalize_text(value)
    if len(normalized) <= width:
        return {normalized} if normalized else set()
    return {normalized[index : index + width] for index in range(len(normalized) - width + 1)}


def match_evidence(
    reference_contexts: list[str],
    corpus: list[dict[str, Any]],
    *,
    minimum_overlap: float = 0.35,
    max_matches_per_context: int = 4,
) -> list[dict[str, Any]]:
    """Map Ragas-generated evidence text back to stable indexed chunk IDs."""
    matches: dict[str, dict[str, Any]] = {}
    prepared = [
        (row, normalize_text(str(row["text"])), text_shingles(str(row["text"])))
        for row in corpus
    ]
    for context in reference_contexts:
        normalized_context = normalize_text(context)
        context_shingles = text_shingles(context)
        ranked: list[tuple[float, dict[str, Any]]] = []
        for row, normalized_chunk, chunk_shingles in prepared:
            if not normalized_context or not normalized_chunk:
                continue
            if normalized_context in normalized_chunk or normalized_chunk in normalized_context:
                score = 1.0
            else:
                denominator = max(1, min(len(context_shingles), len(chunk_shingles)))
                score = len(context_shingles & chunk_shingles) / denominator
            if score >= minimum_overlap:
                ranked.append((score, row))
        for score, row in sorted(ranked, key=lambda item: item[0], reverse=True)[
            :max_matches_per_context
        ]:
            node_id = str(row["_id"])
            previous = matches.get(node_id)
            if previous is None or score > previous["match_score"]:
                matches[node_id] = {
                    "chunk_id": node_id,
                    "doc_id": row["metadata"]["doc_id"],
                    "title": row["title"],
                    "text": row["text"],
                    "match_score": round(score, 6),
                }
    return sorted(matches.values(), key=lambda row: row["match_score"], reverse=True)


_RAGAS_HOP_PREFIX = re.compile(r"^\s*<\d+-hop>\s*", flags=re.IGNORECASE)


def match_prechunked_evidence(
    reference_contexts: list[str],
    leaf_corpus: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Map Ragas pre-chunked contexts back by exact normalized text only.

    Ragas single-hop samples preserve the selected chunk text verbatim. Multi-hop
    samples only prepend ``<N-hop>``. A fuzzy fallback here would recreate the
    cross-drug and cross-formulation gold-label errors this pipeline avoids.
    """
    by_text: dict[str, list[dict[str, Any]]] = {}
    for row in leaf_corpus:
        by_text.setdefault(normalize_text(str(row["text"])), []).append(row)

    evidence: dict[str, dict[str, Any]] = {}
    unmapped: list[int] = []
    ambiguous: dict[str, list[str]] = {}
    for index, context in enumerate(reference_contexts, start=1):
        clean_context = _RAGAS_HOP_PREFIX.sub("", str(context), count=1)
        matches = by_text.get(normalize_text(clean_context), [])
        if not matches:
            unmapped.append(index)
            continue
        if len(matches) > 1:
            ambiguous[str(index)] = [str(row["_id"]) for row in matches]
        for row in matches:
            node_id = str(row["_id"])
            evidence[node_id] = {
                "chunk_id": node_id,
                "doc_id": row["metadata"]["doc_id"],
                "title": row["title"],
                "text": row["text"],
                "match_score": 1.0,
                "match_method": "exact_prechunked_leaf",
            }
    diagnostics = {
        "contexts": len(reference_contexts),
        "mapped_contexts": len(reference_contexts) - len(unmapped),
        "unmapped_context_indexes": unmapped,
        "ambiguous_context_matches": ambiguous,
    }
    return list(evidence.values()), diagnostics


def match_controlled_bundle_evidence(
    reference_contexts: list[str],
    bundles: list[dict[str, Any]],
    leaf_corpus: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Resolve generated contexts through the precomputed bundle-to-leaf map."""
    by_text: dict[str, list[dict[str, Any]]] = {}
    for bundle in bundles:
        by_text.setdefault(normalize_text(str(bundle["text"])), []).append(bundle)
    corpus_by_id = {str(row["_id"]): row for row in leaf_corpus}

    evidence: dict[str, dict[str, Any]] = {}
    bundle_ids: list[str] = []
    unmapped: list[int] = []
    ambiguous: dict[str, list[str]] = {}
    for index, context in enumerate(reference_contexts, start=1):
        clean_context = _RAGAS_HOP_PREFIX.sub("", str(context), count=1)
        matches = by_text.get(normalize_text(clean_context), [])
        if not matches:
            unmapped.append(index)
            continue
        if len(matches) > 1:
            ambiguous[str(index)] = [str(bundle["_id"]) for bundle in matches]
        for bundle in matches:
            bundle_ids.append(str(bundle["_id"]))
            for chunk_id in bundle["evidence_chunk_ids"]:
                row = corpus_by_id.get(str(chunk_id))
                if row is None:
                    continue
                evidence[str(chunk_id)] = {
                    "chunk_id": str(chunk_id),
                    "doc_id": row["metadata"]["doc_id"],
                    "title": row["title"],
                    "text": row["text"],
                    "match_score": 1.0,
                    "match_method": "controlled_bundle",
                    "bundle_id": str(bundle["_id"]),
                }
    diagnostics = {
        "contexts": len(reference_contexts),
        "mapped_contexts": len(reference_contexts) - len(unmapped),
        "mapped_bundle_ids": list(dict.fromkeys(bundle_ids)),
        "unmapped_context_indexes": unmapped,
        "ambiguous_context_matches": ambiguous,
    }
    return list(evidence.values()), diagnostics


def export_review_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=REVIEW_FIELDS)
        writer.writeheader()
        for row in rows:
            evidence = row.get("evidence", [])
            writer.writerow(
                {
                    "id": row["id"],
                    "user_input": row["user_input"],
                    "reference": row["reference"],
                    "evidence_chunk_ids": json.dumps(
                        [item["chunk_id"] for item in evidence], ensure_ascii=False
                    ),
                    "evidence_preview": "\n---\n".join(
                        f"[{item.get('chunk_id')}] {item.get('title', '')}\n"
                        f"{str(item.get('text', ''))[:800]}"
                        for item in evidence
                    ),
                    "source_titles": " | ".join(
                        dict.fromkeys(str(item.get("title", "")) for item in evidence)
                    ),
                    "synthesizer_name": row.get("synthesizer_name", ""),
                    "generation_warnings": " | ".join(row.get("generation_warnings", [])),
                    "review_status": row.get("review_status", "pending"),
                    "question_verified": str(row.get("question_verified", False)).lower(),
                    "evidence_verified": str(row.get("evidence_verified", False)).lower(),
                    "reviewer_notes": row.get("reviewer_notes", ""),
                }
            )


def parse_bool(value: Any) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "y", "是", "通过"}


def load_review_csv(path: Path, corpus: list[dict[str, Any]]) -> list[dict[str, Any]]:
    corpus_by_id = {str(row["_id"]): row for row in corpus}
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    approved = []
    for row in rows:
        if row.get("review_status", "").strip().lower() != "approved":
            continue
        if not parse_bool(row.get("question_verified")) or not parse_bool(
            row.get("evidence_verified")
        ):
            raise ValueError(f"{row.get('id')}: approved row requires both verification flags")
        try:
            chunk_ids = [str(value) for value in json.loads(row["evidence_chunk_ids"])]
        except (KeyError, TypeError, json.JSONDecodeError) as exc:
            raise ValueError(f"{row.get('id')}: invalid evidence_chunk_ids JSON") from exc
        missing = [chunk_id for chunk_id in chunk_ids if chunk_id not in corpus_by_id]
        if missing:
            raise ValueError(f"{row.get('id')}: unknown evidence chunks: {missing}")
        if not chunk_ids:
            raise ValueError(f"{row.get('id')}: approved answerable row requires evidence")
        if not row.get("user_input", "").strip() or not row.get("reference", "").strip():
            raise ValueError(f"{row.get('id')}: question and reference are required")
        evidence = [
            {
                "chunk_id": chunk_id,
                "doc_id": corpus_by_id[chunk_id]["metadata"]["doc_id"],
                "title": corpus_by_id[chunk_id]["title"],
                "text": corpus_by_id[chunk_id]["text"],
            }
            for chunk_id in chunk_ids
        ]
        approved.append(
            {
                "id": row["id"],
                "user_input": row["user_input"].strip(),
                "reference": row["reference"].strip(),
                "reference_context_ids": chunk_ids,
                "reference_contexts": [item["text"] for item in evidence],
                "evidence": evidence,
                "synthesizer_name": row.get("synthesizer_name", ""),
                "generation_warnings": row.get("generation_warnings", ""),
                "review_status": "approved",
                "question_verified": True,
                "evidence_verified": True,
                "reviewer_notes": row.get("reviewer_notes", ""),
            }
        )
    if not approved:
        raise ValueError("review file contains no fully approved rows")
    ids = [row["id"] for row in approved]
    if len(ids) != len(set(ids)):
        raise ValueError("approved question IDs must be unique")
    return approved
