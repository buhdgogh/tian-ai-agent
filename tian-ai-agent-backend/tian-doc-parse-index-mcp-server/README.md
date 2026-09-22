# tian-doc-parse-index-mcp-server

独立的 Java 21 / Spring Boot 文档解析、分层索引和向量检索 MCP 服务。该目录不属于现有父 Maven 工程的 module，启动和构建均不会依赖或修改 `tian-ai-agent-admin`。

## 能力

- MCP Streamable HTTP 风格 JSON-RPC 端点：`POST /mcp`
- Java 直接调用 MinerU v4 云端解析：HTTP/OSS URL直接提交，本地文件通过预签名URL上传；未配置 Token 时使用 Apache Tika
- 单文档和批文档异步任务、任务状态持久化、服务重启中断标记
- SHA-256 + 解析参数缓存
- `4096 / 1024 / 256` 多层级 Chunk，父子关系持久化
- DashScope 批量 Embedding；本地开发可使用确定性哈希向量
- H2 事务化保存文档、任务和层级 Chunk 元数据
- 可切换 H2 本地向量或 Milvus 向量存储，支持按文档覆盖更新和删除
- 稠密向量 + 关键词覆盖率混合召回、单文档候选限制和 MMR 去重
- 基于 Qwen 的证据约束问答；未配置 API Key 时返回可追溯原文
- 按相关性、证据充分度与来源冲突进行证据分级，低证据请求拒答或提示人工复核
- 面向会话所有者隔离的长期记忆向量写入与语义召回
- 文档增量覆盖、删除、列表与知识库状态查询

## MCP 工具

| 工具 | 说明 |
|---|---|
| `parse_document` | 异步解析单文档，可自动索引 |
| `parse_documents_batch` | 异步提交多个文档 |
| `get_parse_task` | 查询解析与索引状态 |
| `index_document` | 直接索引 Markdown |
| `retrieve_knowledge` | 返回混合召回证据 |
| `get_chunk_context` | 按Chunk ID返回三级父子上下文 |
| `query_knowledge` | 生成带来源的答案 |
| `delete_document` | 删除文档及索引 |
| `list_documents` | 列出文档 |
| `knowledge_base_status` | 返回文档、Chunk、向量数量 |
| `upsert_long_term_memory` | 写入或更新会话所有者隔离的长期记忆向量 |
| `search_long_term_memory` | 对指定所有者的长期记忆进行语义召回 |
| `delete_long_term_memory` | 删除指定会话所有者的全部长期记忆向量 |

所有业务工具保持 Python 版统一响应：

```json
{"ok": true, "data": {}, "error": null}
```

## 启动

仅使用本地 Tika 和本地哈希向量即可启动：

```powershell
mvn spring-boot:run
```

启用 MinerU 和 DashScope：

```powershell
$env:MINERU_TOKEN="your-mineru-token"
$env:DASHSCOPE_API_KEY="your-dashscope-key"
$env:EMBEDDING_PROVIDER="dashscope"
mvn spring-boot:run
```

解析工具的 `mode` 支持：

- `auto`：配置了 `MINERU_TOKEN` 时使用 MinerU，否则使用 Tika（默认）
- `mineru`：强制使用 MinerU；未配置 Token 或远端失败时任务失败
- `tika`：强制使用本地 Tika，适合普通电子版 PDF/Office 文档

MinerU解析完成后只把Markdown交还Java，后续层级切块、Embedding、H2/Milvus写入、混合召回和MMR均在Java侧完成。

启用 Milvus 向量存储：

```powershell
$env:VECTOR_STORE="milvus"
$env:MILVUS_URI="http://127.0.0.1:19530"
$env:MILVUS_TOKEN="root:Milvus"
$env:MILVUS_DATABASE="default"
$env:MILVUS_COLLECTION="knowledge_chunks"
$env:MILVUS_MEMORY_COLLECTION="agent_memories"
$env:EMBEDDING_PROVIDER="dashscope"
$env:DASHSCOPE_API_KEY="your-dashscope-key"
mvn spring-boot:run
```

Milvus 模式将叶子 Chunk 向量与长期记忆分别保存到独立 Collection；文档、解析任务和层级关系仍由 H2 管理。
同一个 `doc_id` 再次入库时会先 upsert 新叶子 Chunk，再删除已经失效的旧 Chunk；
`delete_document` 会同步删除 H2 元数据和 Milvus 向量。首次在本地模式与 Milvus 模式之间切换后，
需要重新提交既有文档以完成向量迁移。Embedding 维度变化时应使用新的 Collection 名称或重建原 Collection。

默认端口为 `8081`，MCP 地址为 `http://127.0.0.1:8081/mcp`。

## 调用示例

初始化：

```bash
curl -X POST http://127.0.0.1:8081/mcp -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"demo","version":"1.0"}}}'
```

直接索引 Markdown：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "index_document",
    "arguments": {
      "doc_id": "doc-001",
      "text": "# 测试文档\n\n需要索引的正文",
      "title": "测试文档",
      "source_uri": "local://doc-001"
    }
  }
}
```

## 说明

- Java 版没有依赖 LlamaIndex；层级切块、向量计算、余弦检索、混合打分和 MMR 均为本模块实现。
- 默认 H2 向量检索会在 JVM 中计算余弦相似度，适合单机与测试环境；配置 `VECTOR_STORE=milvus` 后由 Milvus 完成向量候选召回，Java 侧继续执行关键词融合、单文档候选限制和 MMR 去重。
- MinerU REST 字段集中在 `MineruClient`。如果所使用的 MinerU 平台版本调整了 API 字段，只需修改这一适配器。
- HTTP 文档下载会拒绝回环、内网、链路本地和组播地址，避免把 MCP 工具变成 SSRF 入口。
