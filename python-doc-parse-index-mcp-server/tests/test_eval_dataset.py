import csv
import json
from pathlib import Path

import pytest

from evals.dataset import (
    export_review_csv,
    load_review_csv,
    match_controlled_bundle_evidence,
    match_evidence,
    match_prechunked_evidence,
)
from evals.generate_questions import (
    candidate_quality_warnings,
    is_generation_unit_eligible,
    select_source_units_round_robin,
)


def sample_corpus() -> list[dict]:
    return [
        {
            "_id": "chunk-1",
            "title": "构造地质图建模",
            "text": "方法通过地质界线、产状和地层厚度构建虚拟钻孔。",
            "metadata": {"doc_id": "doc-1"},
        },
        {
            "_id": "chunk-2",
            "title": "其他文档",
            "text": "这段内容与问题无关。",
            "metadata": {"doc_id": "doc-2"},
        },
    ]


def test_source_units_are_interleaved_across_documents() -> None:
    rows = [
        {"_id": "a1", "text": "a" * 100, "metadata": {"doc_id": "a", "char_start": 0}},
        {"_id": "a2", "text": "a" * 100, "metadata": {"doc_id": "a", "char_start": 100}},
        {"_id": "b1", "text": "b" * 100, "metadata": {"doc_id": "b", "char_start": 0}},
        {"_id": "b2", "text": "b" * 100, "metadata": {"doc_id": "b", "char_start": 100}},
    ]

    selected = select_source_units_round_robin(rows, 4)

    assert [row["_id"] for row in selected] == ["a1", "b1", "a2", "b2"]


def test_incomplete_table_fragment_is_not_a_generation_unit() -> None:
    assert not is_generation_unit_eligible({"text": "<tr><td>贫血</td><td>23%</td></tr>" * 4})
    assert is_generation_unit_eligible(
        {"text": "<table><tr><td>事件</td><td>发生率</td></tr></table>" * 3}
    )


def test_quality_warnings_detect_language_table_and_unit_risks() -> None:
    warnings = candidate_quality_warnings(
        "What is the incidence rate?", "It is 5 in the first column.", 6
    )

    assert "non_chinese_question" in warnings
    assert "non_chinese_reference" in warnings
    assert "ambiguous_table_reference" in warnings
    assert "possible_missing_unit_in_reference" in warnings
    assert "review_candidate_evidence_count=6" in warnings


def test_generated_evidence_maps_to_stable_chunk_id() -> None:
    matched = match_evidence(["通过地质界线、产状和地层厚度构建虚拟钻孔"], sample_corpus())
    assert matched[0]["chunk_id"] == "chunk-1"
    assert matched[0]["match_score"] > 0.9


def test_prechunked_context_maps_only_by_exact_leaf_text() -> None:
    evidence, diagnostics = match_prechunked_evidence(
        ["<1-hop>\n\n方法通过地质界线、产状和地层厚度构建虚拟钻孔。"],
        sample_corpus(),
    )

    assert [row["chunk_id"] for row in evidence] == ["chunk-1"]
    assert evidence[0]["match_method"] == "exact_prechunked_leaf"
    assert diagnostics["mapped_contexts"] == 1


def test_prechunked_context_does_not_fuzzy_match_other_content() -> None:
    evidence, diagnostics = match_prechunked_evidence(
        ["方法使用地层厚度构建一个模型。"], sample_corpus()
    )

    assert evidence == []
    assert diagnostics["unmapped_context_indexes"] == [1]


def test_controlled_bundle_maps_context_to_all_its_leaf_ids() -> None:
    corpus = sample_corpus()
    bundles = [
        {
            "_id": "bundle:parent-1",
            "text": "药品由成份甲组成。用于治疗疾病乙。",
            "evidence_chunk_ids": ["chunk-1", "chunk-2"],
        }
    ]
    evidence, diagnostics = match_controlled_bundle_evidence(
        ["药品由成份甲组成。用于治疗疾病乙。"], bundles, corpus
    )

    assert [row["chunk_id"] for row in evidence] == ["chunk-1", "chunk-2"]
    assert all(row["match_method"] == "controlled_bundle" for row in evidence)
    assert diagnostics["mapped_bundle_ids"] == ["bundle:parent-1"]


def test_only_double_verified_rows_enter_gold(tmp_path: Path) -> None:
    path = tmp_path / "review.csv"
    candidates = [
        {
            "id": "q1",
            "user_input": "如何构建虚拟钻孔？",
            "reference": "结合地质界线、产状和地层厚度。",
            "evidence": [
                {
                    "chunk_id": "chunk-1",
                    "title": "构造地质图建模",
                    "text": sample_corpus()[0]["text"],
                }
            ],
            "review_status": "pending",
        }
    ]
    export_review_csv(path, candidates)
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
        fields = list(rows[0])
    rows[0].update(
        review_status="approved", question_verified="true", evidence_verified="true"
    )
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    approved = load_review_csv(path, sample_corpus())
    assert approved[0]["reference_context_ids"] == ["chunk-1"]


def test_approval_rejects_unknown_evidence(tmp_path: Path) -> None:
    path = tmp_path / "review.csv"
    path.write_text(
        "id,user_input,reference,evidence_chunk_ids,evidence_preview,source_titles,"
        "synthesizer_name,review_status,question_verified,evidence_verified,reviewer_notes\n"
        'q1,问题,答案,"[""missing""]",,,single,approved,true,true,\n',
        encoding="utf-8-sig",
    )
    with pytest.raises(ValueError, match="unknown evidence chunks"):
        load_review_csv(path, sample_corpus())
