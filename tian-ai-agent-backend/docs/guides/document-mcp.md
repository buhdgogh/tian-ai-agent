# 内嵌文档 MCP

文档解析、分层索引和向量检索与医疗助手运行在同一 Spring Boot 进程。启用 `doc-mcp.enabled` 后，内部调用走进程内接口。OpenClaw 等外部客户端可连接 `POST /api/mcp`，并通过 `Authorization: Bearer <token>` 发送 `agent.access.mcp-token` 配置的令牌。

## 能力

- MCP Streamable HTTP 风格 JSON-RPC 端点：`POST /mcp`
- Java 直接调用 MinerU v4 云端解析：HTTP/OSS URL直接提交，本地文件通过预签名URL上传；未配置 Token 时使用 Apache Tika
- 单文档和批文档异步任务、任务状态持久化、服务重启中断标记
- SHA-256 + 解析参数缓存
- `4096 / 1024 / 256` 多层级 Chunk，父子关系持久化
- DashScope 批量 Embedding；本地开发可使用确定性哈希向量
- SQLite 事务化保存文档、任务和层级 Chunk 元数据
- 可切换 SQLite 本地向量或 Milvus 向量存储，支持按文档覆盖更新和删除
- 稠密向量 + 关键词覆盖率混合召回、单文档候选限制和 MMR 去重
- 基于 Qwen 的证据约束问答；未配置 API Key 时返回可追溯原文
- 按相关性、证据充分度与来源冲突进行证据分级，低证据请求拒答或提示人工复核
- 面向会话所有者隔离的长期记忆向量写入与语义召回
- 文档增量覆盖、删除、列表与知识库状态查询

## MCP 工具

| 工具 | 说明 |
|---|---|
| `list_knowledge_spaces` | 返回可选择的空间及其 ID |
| `retrieve_knowledge` | 返回混合召回证据 |
| `get_chunk_context` | 按Chunk ID返回三级父子上下文 |
| `query_knowledge` | 生成带来源的答案 |
| `knowledge_base_status` | 返回文档、Chunk、向量数量 |

检索、问答、片段上下文和状态查询都需要显式提供 `space_id`。管理员通过知识库 HTTP 接口管理空间和文档。内置医疗空间的入库和检索校验发布状态，通用空间无需医疗审核元数据。文档删除会在 SQLite 留下删除标记，阻止尚未结束的解析任务重新写入同一文档。

业务工具使用统一响应结构。

```json
{"ok": true, "data": {}, "error": null}
```

## 启动

从后端根目录构建。

```powershell
mvn -pl tian-ai-agent-app -am clean package -DskipTests
```

默认使用 Tika 和本地哈希向量。按主项目 README 配置模型密钥和 MCP 令牌后启动。

```powershell
java -jar tian-ai-agent-app/target/tian-ai-agent-backend.jar
```

启用 MinerU 时，在后端根目录的 `.env` 中填写 `MINERU_TOKEN`。如果部署配置同时设置了 `doc-mcp.mineru.token`，以该配置为准。使用 DashScope 生成向量时，将 `doc-mcp.embedding.provider` 设为 `dashscope`，并填写 `doc-mcp.embedding.api-key`。如需用 DashScope 生成知识库答案，另填 `doc-mcp.chat.api-key`。这些配置可以直接写入 `document-mcp.yml`，也可以在运行目录下的 `config/application.yml` 中覆盖。

解析工具的 `mode` 支持：

- `auto`：配置了 MinerU 令牌时使用 MinerU，否则使用 Tika（默认）
- `mineru`：强制使用 MinerU；未配置 Token 或远端失败时任务失败
- `tika`：强制使用本地 Tika，适合普通电子版 PDF/Office 文档

MinerU解析完成后只把Markdown交还Java，后续层级切块、Embedding、SQLite/Milvus写入、混合召回和MMR均在Java侧完成。

`doc-mcp.vector-store` 默认是 `sqlite`，旧值 `local` 仍可使用。SQLite 保存完整的 Chunk 层级、正文、元数据和叶子向量。启用 `agent.memory.long-term.enabled` 后，长期记忆的摘要、元数据和向量也写入 SQLite。

使用 Milvus 时，将 `doc-mcp.vector-store` 设为 `milvus`，并在 `doc-mcp.milvus` 下填写连接和集合配置。叶子 Chunk 的正文、元数据和向量写入知识库集合，长期记忆写入独立集合。SQLite 仍保存文档元数据、解析任务和完整 Chunk 层级，用于补充引用上下文。切换后端后，需要为已有文档重新建立目标存储的索引。历史记忆留在原存储中。

Milvus 模式将叶子 Chunk 向量与长期记忆分别保存到独立 Collection；文档、解析任务和层级关系仍由 SQLite 管理。
同一个 `doc_id` 再次入库时会先 upsert 新叶子 Chunk，再删除已经失效的旧 Chunk；
知识库文档删除会清理 SQLite 元数据和 Milvus 向量。首次在本地模式与 Milvus 模式之间切换后，
需要重新提交既有文档以完成向量迁移。Embedding 维度变化时应使用新的 Collection 名称或重建原 Collection。

默认 MCP 地址为 `http://127.0.0.1:8123/api/mcp`。将 `doc-mcp.enabled` 设为 `false` 后关闭此路由。

## 文档清洗与验证

解析后先按 Markdown 结构清洗，再写入 `parsed/<spaceId>/<docId>.md` 并执行分块和索引。Wiki 读取同一份清洗后的文件。解析缓存保存解析器输出，缓存命中时也会重新清洗。

- Tika 解析 PDF 时保留真实分页。清洗器检查各页首尾最多两行，对至少三页且覆盖六成以上页面的同侧短文本去重。正文内部也出现的候选行保留。明确的页码在页边删除，纯数字页码还需要在最外侧形成稳定的跨页序列。
- 独立的显式水印行和多页反复出现的已知水印短语会被清理。只有扁平 Markdown 时，使用独立水印和相邻重复段落等高置信规则。图像水印需由上游识别成文本后才能参与此步骤。
- 只去除相邻、空白归一后完全相同的长段落。标题、表格、公式、代码、列表、出处及版本行按结构保护，数值或措辞不同的段落分别保留。

文件列表接口的 `timings.documentMcp.cleaning` 提供清洗前后字符数、分页数量和分项删除计数。评估时可抽样标注应删除的噪声和必须保留的内容，分别统计噪声移除精确率、召回率和关键内容保留率，再用同一批问题比较清洗前后的 HitRate@K、MRR@K 和引用正确性。

## 调用示例

先初始化 MCP 会话。

```bash
curl -X POST http://127.0.0.1:8123/api/mcp \
  -H "Authorization: Bearer <你的 MCP 令牌>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"demo","version":"1.0"}}}'
```

使用相同请求头检索已发布资料。

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "retrieve_knowledge",
    "arguments": {
      "question": "高血压风险因素",
      "space_id": "medical",
      "top_k": 5
    }
  }
}
```

## 说明

- Java 版没有依赖 LlamaIndex；层级切块、向量计算、余弦检索、混合打分和 MMR 均为本模块实现。
- 默认 SQLite 向量检索会在 JVM 中计算余弦相似度，适合单机与测试环境。配置 `doc-mcp.vector-store: milvus` 后由 Milvus 完成向量候选召回，Java 侧继续执行关键词融合、单文档候选限制和 MMR 去重。
- MinerU REST 字段集中在 `MineruClient`。如果所使用的 MinerU 平台版本调整了 API 字段，只需修改这一适配器。
- HTTP 文档下载会拒绝回环、内网、链路本地和组播地址，避免把 MCP 工具变成 SSRF 入口。
- 本地文件解析只读取 `doc-mcp.local-source-root` 指定目录下的文件，默认使用数据目录下的 `knowledge-base`。
