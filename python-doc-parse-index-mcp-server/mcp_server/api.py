"""Stable response envelopes shared by MCP tools."""

from __future__ import annotations

from typing import Any


def ok(data: Any = None, **meta: Any) -> dict[str, Any]:
    response: dict[str, Any] = {"ok": True, "data": data, "error": None}
    if meta:
        response["meta"] = meta
    return response


def error(code: str, message: str, **details: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {"code": code, "message": message}
    if details:
        payload["details"] = details
    return {"ok": False, "data": None, "error": payload}

