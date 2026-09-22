from mcp_server.api import error, ok


def test_ok_envelope() -> None:
    assert ok({"value": 1}) == {"ok": True, "data": {"value": 1}, "error": None}


def test_error_envelope() -> None:
    response = error("bad_input", "invalid", field="question")
    assert response["ok"] is False
    assert response["error"]["code"] == "bad_input"
    assert response["error"]["details"]["field"] == "question"

