from mcp_server.tools.llamaIndex_tools import _is_approved_medical_source


def test_only_active_approved_medical_sources_are_eligible():
    source = {
        "issuer": "权威发布机构",
        "version": "2026版",
        "published_at": "2026-08-01",
        "audience": "ADULT",
        "effective_status": "ACTIVE",
        "review_status": "APPROVED",
    }
    assert _is_approved_medical_source(source)
    assert not _is_approved_medical_source({**source, "effective_status": "WITHDRAWN"})
    assert not _is_approved_medical_source({**source, "review_status": "PENDING"})
    assert not _is_approved_medical_source({**source, "version": ""})
