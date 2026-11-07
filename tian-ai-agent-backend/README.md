# 天医——循证医疗信息与安全支持智能体

本项目基于 Spring AI、RAG、长期会话记忆和受控工具调用，面向健康教育、医疗资料解释、循证检索与安全分诊。系统不是医疗器械，不提供确定诊断、处方或替代医生和药师的临床决策。

## 核心能力

- 主对话使用 CompletableFuture 后台任务和 SSE 实时展示分析/工具执行摘要，最终回答由模型流式生成；支持阶段折叠、中断和恢复，详见 [Agent 实时对话](AGENT_STREAMING.md)。

任务、工具审计和用户画像的数据库配置与接口见 [MySQL 持久化说明](MYSQL_PERSISTENCE.md)。
默认用户 `zhangsan / 123456`、多会话与消息记录见 [用户会话说明](USER_CONVERSATIONS.md)。
工具审计通过RabbitMQ持久化投递、异步写入MySQL，支持最多3次重试、双端确认和死信接收，见 [RabbitMQ 工具审计](RABBITMQ_TOOL_AUDIT.md)。

- 医疗问答与 SSE 多轮对话
- 调用模型前的确定性急症红旗识别
- 指南、药品标签与医学资料的结构化知识库
- 按标题、章节和表格结构解析文档
- Milvus Dense 与原生 BM25 独立召回、RRF 融合与 MMR 去重，支持仅关键词检索；配置与补建见 [Milvus BM25](MILVUS_BM25.md)
- LLM Wiki 按实体→概念→综合分析异步预编译 Markdown，写入 docs/wiki 分类目录与索引、日志，支持原文快照、版本审核和失效传播；使用方式见 [LLM Wiki](LLMWIKI.md)
- Redis RAG 语义缓存：按知识库版本隔离，并通过意图、关键约束、词面和向量双重阈值控制复用
- 带来源编号的证据回答，以及无相关、证据不足、来源冲突的分级处置
- 发布机构、版本、日期、适用人群、有效状态和审核状态管理
- 关键词规则、上下文改写与 LLM 组成的分层意图路由
- ReAct 与 Planner–Executor–Reviewer 动态编排任务 DAG，并行执行无依赖步骤
- 医疗 Agent 可调用 Java 文档 MCP，并进行受控联网搜索与网页证据核验
- 高风险结论医生或药师人工审核标记
- Redis/File 近期记忆、MySQL 结构化摘要与 Milvus 语义长期记忆
- 异步任务、Tool Gateway 并发/超时约束和工具审计

## 模块

- `tian-ai-agent-admin`：医疗对话、Agent、安全分诊、工作流、知识库和任务接口
- `tian-doc-parse-index-mcp-server`：Java 文档解析与分层索引 MCP 服务
- `tian-doc-parse-index-mcp-server`：调用 MinerU API，完成 Java 侧分层切片、Embedding、Milvus 检索与来源引用

## 启动

1. Java 21 与 Maven 3.9+
2. 设置 `DASHSCOPE_API_KEY`
3. 使用实时医学搜索时设置 `SEARCH_API_KEY`
4. 启动 Java 文档 MCP（默认 `8081`）；配置 MinerU Token 时使用多模态解析，否则回退 Tika
5. Admin 与文档 MCP 都在同一台开发机运行时，知识库上传默认使用 `KB_STORAGE_MODE=local`，文档 MCP 直接读取 Admin 保存的本地原文
6. 部署到不同主机或不共享文件系统时，显式设置 `KB_STORAGE_MODE=oss`，并配置 `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_BUCKET` 和 `OSS_ENDPOINT`

```powershell
mvn -pl tian-ai-agent-admin spring-boot:run
```

主服务地址为 `http://localhost:8123/api`，接口文档为 `http://localhost:8123/api/swagger-ui.html`。

### Admin 多模型路由

在 `tian-ai-agent-admin/src/main/resources/application.yml` 已有的 `agent` 下配置 `model-routing`。密钥通过环境变量提供。下面启用通义千问和 DeepSeek，并将 OpenAI 设为可选备用。

```yaml
agent:
  model-routing:
    connect-timeout: 5s
    request-timeout: 30s
    cooldown: 30s
    providers:
      - id: qwen
        protocol: dashscope
        base-url: https://dashscope.aliyuncs.com
        api-key: ${DASHSCOPE_API_KEY}
        model: qwen-plus
        priority: 0
      - id: deepseek
        protocol: openai-compatible
        base-url: https://api.deepseek.com
        completions-path: /chat/completions
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-flash
        reasoning-effort: none
        priority: 0
      - id: openai
        enabled: false
        protocol: openai-compatible
        base-url: https://api.openai.com
        api-key: ${OPENAI_API_KEY:}
        model: ${OPENAI_MODEL:}
        priority: 10
```

- `priority` 数字越小越先尝试。同一优先级按配置顺序轮询，当前组没有可用候选时尝试下一组。需要固定主备顺序时，分别设置不同的优先级。
- 网络异常、超时、限流、上游 5xx 和厂商凭证或端点不可用会触发切换。失败路由在冷却期内暂时跳过，冷却后随正常请求重新尝试。普通请求错误（例如 HTTP 400）直接返回。
- 默认模型可接收工具定义和流式请求。若模型缺少对应能力，设置 `supports-tools: false` 或 `supports-streaming: false`，路由会跳过它。OpenAI 兼容接口默认路径为 `/v1/chat/completions`，可按厂商文档覆盖。DeepSeek 示例设置 `reasoning-effort: none`，使多轮工具调用使用标准 Chat Completions 消息格式。
- 普通 SSE 问答在首段有效输出前可以切换厂商。已经输出内容后，异常会结束本次模型流。同步工具调用在重试范围之外执行，后续模型请求携带已有工具结果。
- 清空 `providers` 可沿用现有 DashScope 对话配置。这组配置覆盖 Admin 问答、ReAct、工作流和辅助生成。文档 MCP 服务内部的问答和 Wiki 生成使用 `doc-mcp.chat`，向量模型也有独立配置。

本地 JVM 启动前在同一个终端设置厂商密钥。使用 Compose 时在 `.env` 中填写启用厂商的密钥，修改内置 YAML 后执行 `docker compose up -d --build admin`。需要在运行配置中覆盖路由列表时，可向 Admin 容器提供同结构的 `SPRING_APPLICATION_JSON`，然后重建容器。

## Docker Compose 本地环境

Compose 会启动 Admin、Java 文档 MCP、Redis、MySQL，以及 Milvus Standalone 所需的 etcd 和 MinIO。开发环境使用共享只读卷把 Admin 上传的原文交给文档 MCP，不要求配置 OSS；部署环境仍可通过 `KB_STORAGE_MODE=oss` 切换回 OSS。

```powershell
Copy-Item .env.example .env
# 编辑 .env，至少填写 DASHSCOPE_API_KEY、MYSQL_PASSWORD、MYSQL_ROOT_PASSWORD
docker compose config
docker compose up -d --build
docker compose ps
```

服务入口：

- Admin：`http://localhost:8123/api`
- 文档 MCP 健康检查：`http://localhost:8081/health`
- Milvus WebUI：`http://localhost:9091/webui/`
- MinIO Console：`http://localhost:9001`

停止服务使用 `docker compose down`。如需同时删除本地数据库、索引和文档卷，确认数据不再需要后再执行 `docker compose down -v`。

## 医疗接口

| 接口 | 用途 |
|---|---|
| `POST /api/ai/medical/chat` | 医疗信息同步对话 |
| `POST /api/ai/medical/chat/stream` | 医疗信息 SSE 对话，敏感内容不进入 URL |
| `POST /api/ai/medical/evidence-report` | 结构化医疗证据报告 |
| `POST /api/ai/medical/rag-chat` | 内置医疗知识库增强对话 |
| `POST /api/ai/medical/tool-chat` | 带受控工具的医疗对话 |
| `POST /api/ai/medical-agent/chat` | 多步医疗 Agent SSE，敏感内容不进入 URL |
| `POST /api/ai/medical/dispatch` | 意图路由后自动选择直接 ReAct 或异步 Workflow |
| `POST /api/ai/workflows` | 提交安全分诊与证据检索工作流 |
| `POST /api/knowledge-base/upload` | 上传已审核医疗资料 |
| `POST /api/knowledge-base/query-with-sources` | 带来源的知识库问答 |

请求示例：

```json
{
  "message": "请解释高血压指南通常关注哪些风险因素，并说明这不是个体诊断。",
  "chatId": "medical_demo_001"
}
```

医疗文档上传除 `file` 外，还必须提交 `issuer`、`version`、`publishedAt`、`audience`、`documentType`、`effectiveStatus=ACTIVE` 和 `reviewStatus=APPROVED`。未审核或非现行文档不会进入当前检索库。

主应用不在启动阶段构建内存向量库；MinerU 解析结果由 Java 文档 MCP 完成 `4096/1024/256` 分层切片、Embedding、Milvus/关键词召回和 MMR 重排。

长期记忆默认关闭。启用时设置 `AGENT_LONG_TERM_MEMORY_ENABLED=true` 及 `AGENT_MEMORY_MYSQL_URL`、`AGENT_MEMORY_MYSQL_USERNAME`、`AGENT_MEMORY_MYSQL_PASSWORD`；近期完整消息仍由 File/Redis 滑动窗口管理，摘要与确认事实保存到 MySQL，语义向量保存到独立 Milvus Collection。长期记忆只用于理解上下文，不能作为医疗证据。

ReAct 对话输入默认只加载最近最多 12 条历史消息、约 12000 字符（包含工具参数与结果），按完整用户回合选取，避免拆散工具调用/返回；可通过 `AGENT_MEMORY_CONTEXT_MAX_MESSAGES`、`AGENT_MEMORY_CONTEXT_MAX_CHARS` 调整。超出预算的回合不注入，Redis/File 原始记录仍按原来的 200 条窗口保留。启用长期记忆后，达到 12 条消息首次异步生成摘要，之后每 3 次符合条件的对话完成回调更新一次，可用 `AGENT_MEMORY_COMPACT_THRESHOLD`、`AGENT_MEMORY_COMPACT_EVERY` 调整。更新时合并上一版摘要和近期消息，摘要最多 4000 字符，确认事实最多 30 条、每条 200 字符；重复的 MySQL/Milvus 摘要不重复注入。摘要尚未完成或长期记忆关闭时，窗口外历史可能暂不可见。这里采用字符预算，并非完整请求的精确 Token 限制；本轮新工具结果不在此次历史窗口裁剪范围内。

ReAct 新工具结果另有 Token 预算：单个结果默认最多 8192、每次 `act()` 的一批结果合计最多 24576（仅结果文本，不含消息协议开销）。该默认值针对多篇文献摘要、5~8个RAG片段及父级上下文，减少常规检索被频繁截断；分页读取未指定预算时同样使用8192上限，可主动请求更小的页。使用 Spring AI 的 CL100K 编码器本地估算，不能视为 Qwen 原生精确 Token 数，也不限制整场对话的累计 Token。超限原文写入 Java 工作目录下的 `tmp/tool-results/{resultId}.json`，模型仅收到带 `stored/resultId/preview` 的有预算 JSON；`preview` 是截断文本而不是模型生成的摘要。Agent 可调用 `readToolResult(resultId, offset, maxTokens)` 分页读取，后续偏移使用返回的 `nextOffset`，读取结果仍受预算约束。同一批工具依赖解析使用完整结果，完成后才裁剪写回模型历史，不改变 JSON Pointer 依赖语义。结果按当前会话隔离；无会话的工作流步骤按单次 Agent 实例隔离，不能跨实例共享读取。此隔离沿用现有 chatId 边界，不额外提供用户身份鉴权。

可用环境变量：`TOOL_RESULT_MAX_TOKENS`（至少256）、`TOOL_RESULT_BATCH_MAX_TOKENS`（不小于单结果预算）、`TOOL_RESULT_DIRECTORY`、`TOOL_RESULT_TTL_HOURS`（默认24）、`TOOL_RESULT_MAX_DISK_BYTES`（默认256MiB）。TTL 到期后拒绝读取，并在下一次大结果写入时清理过期文件；超过磁盘配额或写盘失败时返回 `stored=false` 和有限预览，要求缩小原查询，不回退为完整原文注入。该目录可能包含工具返回的原始医疗资料，请使用受限目录；Docker 如需跨容器重建保留结果，应为该目录配置持久卷。无需数据库建表或启用长期记忆。

不要把 OSS AccessKey 写入 `application.yml` 或提交到版本库。开发环境应使用系统环境变量或 IntelliJ IDEA Run Configuration 的 Environment variables。

## 安全边界

- 急症红旗优先建议拨打 120 或就近急诊，不等待模型生成。
- 处方、停药换药和个体剂量必须由医生或药师审核。
- 药物问题必须考虑过敏史、合并用药和特殊人群信息。
- 证据不足时拒绝结论，来源版本冲突时明确提示。
- 医疗事实必须可追溯，网页摘要不能作为高风险结论的唯一证据。
- 健康信息按敏感数据处理，最小化收集并禁止不必要的身份标识。
- 网页内容作为不可信数据处理；网页读取工具禁止访问本机和内网地址。
