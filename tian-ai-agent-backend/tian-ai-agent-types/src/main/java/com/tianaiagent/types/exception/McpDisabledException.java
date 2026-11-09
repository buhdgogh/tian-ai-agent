package com.tianaiagent.types.exception;

public class McpDisabledException extends IllegalStateException {
    public McpDisabledException() { super("文档 MCP 已关闭，请启用 DOC_MCP_ENABLED 后重试"); }
}
