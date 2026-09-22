# 天医——循证医疗信息与安全支持智能体

本项目基于 Spring AI、RAG、长期会话记忆和受控工具调用，面向健康教育、医疗资料解释、循证检索与安全分诊。系统不是医疗器械，不提供确定诊断、处方或替代医生和药师的临床决策。

## 核心能力

- 医疗问答与 SSE 多轮对话
- 调用模型前的确定性急症红旗识别
- 指南、药品标签与医学资料的结构化知识库
- 按标题、章节和表格结构解析文档
- Milvus Dense、关键词覆盖率和 MMR 的分阶段检索与消融评测
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

不要把 OSS AccessKey 写入 `application.yml` 或提交到版本库。开发环境应使用系统环境变量或 IntelliJ IDEA Run Configuration 的 Environment variables。

## 安全边界

- 急症红旗优先建议拨打 120 或就近急诊，不等待模型生成。
- 处方、停药换药和个体剂量必须由医生或药师审核。
- 药物问题必须考虑过敏史、合并用药和特殊人群信息。
- 证据不足时拒绝结论，来源版本冲突时明确提示。
- 医疗事实必须可追溯，网页摘要不能作为高风险结论的唯一证据。
- 健康信息按敏感数据处理，最小化收集并禁止不必要的身份标识。
- 网页内容作为不可信数据处理；网页读取工具禁止访问本机和内网地址。
