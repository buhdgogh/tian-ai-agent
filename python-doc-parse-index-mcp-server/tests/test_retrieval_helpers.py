from mcp_server.tools.llamaIndex_tools import (
    CHUNK_SIZES,
    _attach_provenance_metadata,
    _annotate_node_pages,
    _annotate_node_structure,
    _citation_integrity,
    _deterministic_answer_plan,
    _is_meaningful_text,
    _leaf_node_ids,
    _merge_context_texts,
    _medical_query_profile,
    _needs_medicine_clarification,
    _normalized_document_metadata,
    _only_leaf_nodes,
    _sanitize_node_metadata_for_redis,
    _requires_llm_answer_plan,
    _requires_model_answer_validation,
    _search_bm25_leaf,
    query_knowledge,
)
from llama_index.core import Document
from llama_index.core.schema import MetadataMode
from llama_index.core.schema import NodeRelationship, NodeWithScore, RelatedNodeInfo, TextNode
from llama_index.core.storage.docstore import SimpleDocumentStore


def test_metadata_falls_back_to_markdown_title() -> None:
    metadata = _normalized_document_metadata(
        "doc-1", "# 地球科学智能\n\n这是足够长的正文。", {"source_uri": "x.pdf"}
    )
    assert metadata["title"] == "地球科学智能"
    assert metadata["doc_id"] == "doc-1"
    assert metadata["source_uri"] == "x.pdf"


def test_optional_null_metadata_is_omitted() -> None:
    metadata = _normalized_document_metadata(
        "doc-1",
        "# 标题\n\n正文",
        {"source_uri": None, "content_sha256": None, "page_number": None},
    )
    assert metadata == {"doc_id": "doc-1", "title": "标题"}


def test_task_payload_is_not_meaningful_knowledge() -> None:
    payload = '{"task_id":"1","status":"running","items":[]}'
    assert _is_meaningful_text(payload) is False


def test_page_metadata_is_derived_from_mineru_content_list() -> None:
    node = Document(text="前文 断层空间关系决定三维模型的拓扑结构 后文")
    _annotate_node_pages(
        [node],
        [{"page_idx": 4, "text": "断层空间关系决定三维模型的拓扑结构"}],
    )
    assert node.metadata["page_number"] == 5
    assert node.metadata["page_start"] == 5
    assert node.metadata["page_end"] == 5


def test_long_provenance_is_stored_but_excluded_from_model_text() -> None:
    node = Document(text="用于向量检索的正文")
    metadata = {
        "doc_id": "doc-1",
        "title": "很长的标题" * 100,
        "source_uri": "https://example.com/" + "path/" * 100,
        "content_sha256": "a" * 64,
    }

    _attach_provenance_metadata([node], metadata)

    assert node.metadata["source_uri"] == metadata["source_uri"]
    assert node.get_content(metadata_mode=MetadataMode.EMBED) == node.text
    assert node.get_content(metadata_mode=MetadataMode.LLM) == node.text


def test_redis_metadata_sanitizer_removes_null_values() -> None:
    node = Document(
        text="正文",
        metadata={"title": "标题", "page_number": None, "source_uri": None},
    )

    _sanitize_node_metadata_for_redis([node])

    assert node.metadata == {"title": "标题"}


class _FakeNode:
    def __init__(self, text: str, start: int, end: int):
        self.text = text
        self.start_char_idx = start
        self.end_char_idx = end
        self.metadata = {}

    def get_content(self):
        return self.text


def test_medical_hierarchy_uses_requested_chunk_sizes() -> None:
    assert CHUNK_SIZES == [4096, 1024, 256]


def test_markdown_structure_is_attached_to_nodes() -> None:
    markdown = "# 药品说明书\n正文\n## 禁忌\n严重过敏者禁用。"
    node = _FakeNode("严重过敏者禁用。", markdown.index("严重过敏"), len(markdown))

    _annotate_node_structure([node], markdown)

    assert node.metadata["section"] == "禁忌"
    assert node.metadata["section_path"] == "药品说明书 / 禁忌"
    assert node.metadata["char_start"] == markdown.index("严重过敏")


def test_context_text_merge_removes_overlap_in_original_order() -> None:
    first = _FakeNode("A" * 30 + "B" * 30, 0, 60)
    second = _FakeNode("B" * 30 + "C" * 30, 30, 90)

    merged = _merge_context_texts([second, first], 500)

    assert merged == "A" * 30 + "B" * 30 + "C" * 30


def test_retrieval_unit_filter_keeps_only_hierarchy_leaves() -> None:
    leaf = TextNode(
        id_="leaf-1",
        text="叶子证据",
        relationships={
            NodeRelationship.PARENT: RelatedNodeInfo(node_id="parent-1")
        },
    )
    parent = TextNode(
        id_="parent-1",
        text="父节点上下文",
        relationships={
            NodeRelationship.CHILD: [RelatedNodeInfo(node_id="leaf-1")]
        },
    )
    docstore = SimpleDocumentStore()
    docstore.add_documents([parent, leaf])

    leaf_ids = _leaf_node_ids(docstore)
    filtered = _only_leaf_nodes(
        [NodeWithScore(node=parent, score=0.9), NodeWithScore(node=leaf, score=0.8)],
        leaf_ids,
    )

    assert leaf_ids == {"leaf-1"}
    assert [item.node.node_id for item in filtered] == ["leaf-1"]


def test_bm25_leaf_search_discards_parent_rows(monkeypatch) -> None:
    rows = [
        {"node_id": "parent-1", "text": "父节点"},
        {"node_id": "leaf-1", "text": "叶子一"},
        {"node_id": "leaf-2", "text": "叶子二"},
    ]

    def fake_search(query, limit, min_term_coverage):
        assert query == "适应症"
        assert limit == 200
        return rows

    monkeypatch.setattr(
        "mcp_server.tools.llamaIndex_tools.knowledge_store.search_bm25",
        fake_search,
    )

    result = _search_bm25_leaf("适应症", 1, {"leaf-1", "leaf-2"})

    assert [row["node_id"] for row in result] == ["leaf-1"]


def test_citation_integrity_detects_invalid_and_uncited_sentences() -> None:
    result = _citation_integrity(
        "该药用于特定适应症。[1] 这一结论没有有效来源支持。错误引用。[9]", 2
    )

    assert result["invalid_citations"] == [9]
    assert result["citation_coverage"] < 1


def test_medical_query_profile_extracts_entity_and_section_intent() -> None:
    profile = _medical_query_profile("托珠单抗注射液有哪些禁忌和严重不良反应？")

    assert "托珠单抗注射液" in profile["entities"]
    assert "禁忌" in profile["sections"]
    assert "不良反应" in profile["sections"]


def test_generic_leaflet_request_does_not_invent_a_medicine_entity() -> None:
    question = "总结药品说明书中的禁忌、不良反应和就医提示"
    profile = _medical_query_profile(question)

    assert profile["entities"] == []
    assert "就医提示" in profile["sections"]
    assert _needs_medicine_clarification(question) is True


def test_multiple_explicit_medicines_are_extracted_separately() -> None:
    question = "比较安尔眠胶囊和安乐胶囊的禁忌"
    profile = _medical_query_profile(question)

    assert profile["entities"] == ["安尔眠胶囊", "安乐胶囊"]
    assert _needs_medicine_clarification(question) is False


def test_explicit_leaflet_name_without_dosage_form_does_not_request_clarification() -> None:
    question = "总结阿司匹林说明书中的禁忌和不良反应"

    assert "阿司匹林" in _medical_query_profile(question)["entities"]
    assert _needs_medicine_clarification(question) is False


def test_generic_leaflet_query_returns_clarification_without_retrieval() -> None:
    response = query_knowledge("总结药品说明书中的禁忌、不良反应和就医提示")

    assert response["ok"] is True
    assert response["data"]["retrieval"]["mode"] == "clarification_required"
    assert response["data"]["sources"] == []
    assert "具体药品名称" in response["data"]["answer"]


def test_single_medicine_question_uses_deterministic_plan() -> None:
    question = "安尔眠胶囊有哪些禁忌？"
    plan = _deterministic_answer_plan(question, [{"title": "说明书"}])

    assert _requires_llm_answer_plan(question) is False
    assert plan["planner"] == "deterministic"
    assert plan["answer_type"] == "risk"
    assert plan["focus"] == ["安尔眠胶囊"]


def test_comparison_keeps_llm_planning_and_risk_questions_keep_validation() -> None:
    assert _requires_llm_answer_plan("比较安尔眠胶囊和安乐胶囊的禁忌") is True
    assert _requires_model_answer_validation("安尔眠胶囊有哪些禁忌？") is True
    assert _requires_model_answer_validation("安尔眠胶囊的生产企业是什么？") is False
