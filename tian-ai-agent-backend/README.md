# Tian 知识工作台

本项目基于 Spring AI、RAG、长期会话记忆和受控工具调用。用户可按主题创建独立知识库空间，导入文档、查看引用并运行多步任务。每次检索选择一个完整空间，未选空间时直接对话。原医疗资料保留在内置的 `medical` 空间。

## 核心能力

- 通用问答与 SSE 多轮对话
- 按空间独立上传、索引和检索，支持创建通用空间和医疗空间
- 医疗空间启用确定性急症检查、文档准入和专业复核提示
- 按标题、章节和表格结构解析文档
- Milvus Dense、关键词覆盖率和 MMR 的分阶段检索与消融评测
- Redis RAG 语义缓存按空间与版本隔离，并通过意图、关键约束、词面和向量阈值控制复用
- 带来源编号的证据回答，以及无相关、证据不足、来源冲突的分级处置
- 发布机构、版本、日期、适用人群、有效状态和审核状态管理
- 关键词规则、上下文改写与 LLM 组成的分层意图路由
- ReAct 与 Planner–Executor–Reviewer 动态编排任务 DAG，并行执行无依赖步骤
- Agent 可调用选定空间的知识工具，以及受控网页搜索和原文核验。医疗空间可用医学文献与临床试验工具
- MySQL 会话历史和账号画像、Milvus 跨会话语义记忆
- 异步任务、Tool Gateway 并发/超时约束和工具审计

## 模块

后端包含六个以 `tian-ai-agent-` 开头的 Maven 模块。源码统一使用 `com.tianaiagent` 根包，各模块按职责组织。

| 模块 | 职责 |
| --- | --- |
| `api` | HTTP/MCP 对外契约，例如请求体、医疗报告和 MCP 响应封装 |
| `app` | Spring Boot 启动、Bean 装配、运行配置与可执行 JAR |
| `domain` | 按智能体、医疗、会话、知识库和任务组织出站端口、模型、业务规则与用例 |
| `infrastructure` | 领域端口的实现，包括 Redis、MySQL、SQLite、Milvus、OSS、文档解析和模型调用 |
| `trigger` | HTTP、SSE 和 MCP 入站适配 |
| `types` | 跨模块使用的配置、常量和数据类型 |

```text
domain/
├── agent/          ReAct、工具治理和工作流
├── conversation/   会话归属、摘要与语义记忆
├── knowledge/      文档解析、发布、检索与证据评估
├── medical/        医疗安全规则与医疗对话
└── task/           异步任务调度和状态
```

各业务域使用同一套子包约定。

```text
<业务域>/
├── adapter/         领域端口，按实际需要分为 port、repository
├── model/           领域模型，按实际需要分为 entity、valobj、aggregate
└── service/         业务规则和用例编排
```

`app` 依赖 `trigger` 和 `infrastructure`。入口调用领域服务，基础设施实现领域端口。HTTP 和 MCP 共用同一套知识库服务，`doc-mcp.enabled` 控制文档组件和 MCP 入口的装配。

## 启动

使用 Java 21 和 Maven。启动前配置 `agent.access` 下的访问令牌。聊天模型可在启动后由用户在工作台配置，也可以通过 YAML 提供平台默认值。配置项可写入运行目录下的 `config/application.yml`，或在本地修改 `tian-ai-agent-app/src/main/resources/application.yml` 后重新打包。

```powershell
mvn -pl tian-ai-agent-app -am clean package -DskipTests
java '-XX:ErrorFile=logs/hs_err_pid%p.log' '-XX:ReplayDataFile=logs/replay_pid%p.log' -jar tian-ai-agent-app/target/tian-ai-agent-backend.jar
```

主服务地址为 `http://localhost:8123/api`，接口文档为 `http://localhost:8123/api/swagger-ui.html`。

从后端根目录启动时，Spring Boot 将 UTF-8 运行日志写入 `logs/tian-ai-agent.log`，并按大小滚动。Maven 的 app 模块测试写入 `logs/tian-ai-agent-test.log`，测试 JVM 的崩溃报告和编译重放文件也写入根目录的 `logs`。使用 IDEA 运行时，将工作目录设为后端根目录，并把上面两个 `-XX` 选项填入 VM options 以收集 JVM 诊断文件。

网页搜索工具 `searchWeb` 使用 SearchAPI。在后端根目录的 `.env` 中填写 `SEARCH_API_KEY` 后重启，也可以通过系统环境变量 `SEARCH_API_KEY` 或 Spring 配置项 `search-api.api-key` 提供密钥。知识库检索使用独立的文档和索引配置。

业务请求需要携带 `Authorization: Bearer <token>`，包括流式请求。健康检查允许匿名访问。令牌至少为 24 个可打印 ASCII 字符，各身份使用不同令牌。

- `agent.access.admin-token` 用于文档上传、列表、删除和工具审计。升级前未按用户区分的历史会话仍可通过管理员令牌访问。
- 在 `agent.access.users` 中配置普通用户，每项包含 `id` 和 `token`。用户 ID 允许 1 至 32 位小写字母、数字和下划线，`admin` 为保留 ID。用户只能访问自己的会话和异步任务。
- `agent.access.mcp-token` 供 OpenClaw 等 MCP 客户端使用，只能调用已发布知识的只读工具。

`doc-mcp.enabled` 默认 `true`。设为 `false` 后重启，上传、检索、知识库问答和文档删除返回 `503`，管理员仍可查看文件列表，已认证用户仍可进行普通医疗对话。启用长期记忆时，删除会话需要语义存储可用，删除失败可在服务恢复后重试。

知识库默认使用 SQLite 文件 `data/knowledge.sqlite`。已有知识库文件可通过[一次性迁移说明](docs/sqlite/README.md)导入。如果需要继续使用归档中的旧 Admin 会话和文件，从后端根目录设置 `$env:TIAN_DATA_DIR = 'tmp/legacy-layout/tian-ai-agent-admin/tmp'` 后再启动。文档工具和配置见 [文档 MCP](docs/guides/document-mcp.md)。

## 本地后端与 Docker 依赖

Spring Boot 在 Windows 本机运行。[Compose](docs/docker/compose.yml) 管理 MySQL、Redis、Milvus、etcd 和 MinIO。MySQL 使用独立数据卷，已发布的数据库和存储端口绑定到本机回环地址。

先启动 Docker Desktop，在后端根目录的 `.env` 中通过 `MYSQL_PASSWORD` 配置 MySQL root 密码，再运行以下命令。可在 `.env` 中覆盖容器端口，参考 [环境变量模板](docs/docker/.env.example)。

```powershell
docker compose --env-file .env -f docs/docker/compose.yml up -d
docker compose --env-file .env -f docs/docker/compose.yml ps
```

按 [MySQL 部署配置](docs/mysql/README.md) 准备数据库后，在 IDEA 中选择 `local` 活动配置，或在后端根目录启动 JAR。

```powershell
java '-XX:ErrorFile=logs/hs_err_pid%p.log' '-XX:ReplayDataFile=logs/replay_pid%p.log' -jar tian-ai-agent-app/target/tian-ai-agent-backend.jar --spring.profiles.active=local
```

默认配置使用 `127.0.0.1:13306` 的 MySQL 持久化会话，并连接 `127.0.0.1:6379` 的 Redis。`local` 额外启用 `127.0.0.1:19530` 的 Milvus 长期记忆。MySQL 宿主机端口由 `MYSQL_PORT` 统一设置。修改 Redis 或 Milvus 端口后，请同步更新 [本地配置](tian-ai-agent-app/src/main/resources/application-local.yml)。

后端健康检查位于 `http://localhost:8123/api/health`。Milvus WebUI 位于 `http://localhost:9091/webui/`，MinIO Console 位于 `http://localhost:9001`。旧版 Compose 的 `admin` 或 `mysql` 容器如仍在运行，可单独停止并保留原数据卷。

## 知识工作台接口

| 接口 | 用途 |
|---|---|
| `POST /api/ai/chat`、`/api/ai/chat/stream` | 同步或 SSE 对话。选择空间后注入检索资料 |
| `POST /api/ai/chat/rag` | 知识库问答，返回引用片段 |
| `POST /api/ai/chat/tools` | 受控工具问答 |
| `POST /api/ai/agent/stream` | ReAct 多步执行与 SSE 调用路径 |
| `POST /api/ai/dispatch` | 按意图选择 ReAct 或异步工作流 |
| `POST /api/ai/workflows` | 创建带可选复核的工作流 |

对话请求使用 `message`、`chatId` 和可选的 `spaceId`。选定医疗空间时启用医疗分诊与证据规则。省略 `spaceId` 时不提供任何空间资料或知识库工具。

工作台读取、删除会话时在 `/api/ai/sessions` 及其子路径传入 `workspace=true`，并使用同一个 `spaceId` 和会话类型。无空间通用对话使用独立历史。以下医疗接口继续使用原有历史范围。

### 医疗兼容接口

| 接口 | 用途 |
|---|---|
| `POST /api/ai/medical/chat` | 医疗信息同步对话 |
| `POST /api/ai/medical/chat/stream` | 医疗信息 SSE 对话，敏感内容不进入 URL |
| `POST /api/ai/medical/evidence-report` | 结构化医疗证据报告 |
| `POST /api/ai/medical/rag-chat` | 选定空间后进行带来源的问答，省略空间时直接对话 |
| `POST /api/ai/medical/tool-chat` | 带受控工具的医疗对话 |
| `POST /api/ai/medical-agent/chat` | 多步医疗 Agent SSE，敏感内容不进入 URL |
| `POST /api/ai/medical/dispatch` | 意图路由后自动选择直接 ReAct 或异步 Workflow |

请求示例：

```json
{
  "message": "请解释高血压指南通常关注哪些风险因素，并说明这不是个体诊断。",
  "chatId": "medical_demo_001"
}
```

对话和工作流请求可选填 `spaceId`。省略时不会检索任何空间，也不会提供知识库工具。会话列表、历史消息和删除接口接受同一个 `spaceId`，相同的 `chatId` 在不同空间使用各自的历史。

## 配置中心

工作台的「配置中心」按账号保存 MinerU Token 和 SearchAPI Key。上传文档时，自动模式优先使用当前上传账号的 MinerU Token，缺少 Token 或 MinerU 失败时由 Tika 解析。网页搜索工具使用当前问答账号的 SearchAPI Key。配置读取接口只返回状态。

本机开发可在后端根目录的 `.env` 设置 `PERSONAL_SETTINGS_ADMIN_ENV_FALLBACK=true`。管理员未配置个人凭据时，可使用 `MINERU_TOKEN` 和 `SEARCH_API_KEY`。普通账号使用自己的配置。

## 本地工作区

在 Chrome 或 Edge 通过 HTTPS 或 localhost 打开工作台，进入 Agent 问答，点击输入框旁的「选择工作区」并授权读取项目目录。可以提问「请阅读 README 和构建配置，说明这个项目如何启动」。目录句柄留在浏览器。Agent 按需取得目录条目和 UTF-8 文本片段，片段会发给后端与当前模型供本轮回答使用。

工作区问答使用当前页面的流式连接，只提供只读目录工具。关闭或刷新页面后需要重新选目录。读取会跳过常见的凭据文件、版本库元数据和依赖目录，单个文本文件上限为 1 MiB。

## Skills 与 Prompt 模板

在工作台右上角打开「Skills / Prompt」，可为当前账号新增、编辑、启停和删除配置。Agent 问答每轮可勾选最多 5 个已启用的 Skill。Skill 正文作为本轮用户指令参与回答和工作流执行。选择 Prompt 模板会将正文插入输入框，可在发送前编辑。

配置接口为 `/api/skills` 和 `/api/prompt-templates`，支持列表、新增、更新和删除。调用 Agent 问答接口时，可在 `/api/ai/dispatch`、`/api/ai/dispatch/stream` 或 `/api/ai/agent/stream` 的请求体中传入 `skillIds`。启用工作台 MySQL 时，配置写入 `agent_preset`，否则保存在数据目录的 `agent-presets` 下。

## 模型提供商与运行审计

登录工作台后，点击右上角「模型设置」，选择提供商或自定义协议，填写 Base URL、模型名称和自己的 API Key。保存后，当前账号的后续请求使用自己的模型列表。移除所有个人模型并保存后，恢复使用平台默认配置。普通用户的身份来自各自的 `agent.access.users` 访问令牌，个人 API Key 由后端加密保存，读取配置时仅返回密钥是否已设置。

自定义网关先在后端 `agent.models.allowed-user-origins` 中登记来源（`scheme://host[:port]`），用户再填写含 API 路径的 Base URL。该列表默认包含 DeepSeek、OpenAI 和阿里云百炼的公共地址。自定义列表时需保留部署允许使用的内置来源。默认配置保存在数据目录的 `model-settings` 下。启用工作台 MySQL 持久化后，个人模型配置及会话历史使用 MySQL，模型 API Key 的解密密钥仍保存在 `model-settings/storage.key`。详见 [MySQL 部署配置](docs/mysql/README.md)。

平台默认模型可在运行目录的 `config/application.yml` 中配置 `agent.models.providers`，保存后重启后端。以下示例中的密钥需替换为自己的值，`base-url` 填到 API 前缀，程序会追加 `/chat/completions`。

```yaml
agent:
  models:
    timeout: 60s
    cooldown: 30s
    providers:
      - id: deepseek
        protocol: OPENAI_COMPATIBLE
        base-url: https://api.deepseek.com
        api-key: "填入 DeepSeek API Key"
        model: deepseek-flash
        reasoning-effort: none
        priority: 0
      - id: qwen
        protocol: OPENAI_COMPATIBLE
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        api-key: "填入 DashScope API Key"
        model: qwen-plus
        priority: 1
```

`OPENAI_COMPATIBLE` 支持多个兼容厂商，`DASHSCOPE` 使用阿里云原生接口。省略提供商列表时沿用 `spring.ai.dashscope` 聊天配置。省略 `priority` 时按列表顺序尝试，相同优先级轮询。流式接口默认请求 Token 用量，厂商不支持时可将对应提供商的 `stream-usage` 设为 `false`。

上面的 DeepSeek 示例使用非思考模式，以沿用现有工具消息格式。[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/) · [阿里云兼容地址](https://docs.modelstudio.console.alibabacloud.com/en/model-studio/base-url)

聊天模型由工作台或 `agent.models` 配置。文档和记忆向量由 `doc-mcp.embedding` 配置，切换 Embedding 模型时，需使用与该模型匹配的索引。

限流、连接失败、超时和服务端错误等会触发短暂冷却并尝试下一个模型。流式输出开始或本次模型调用已执行工具后，当前请求会保留原失败状态，后续请求仍可使用可用厂商。模型路由适用于聊天、意图识别、ReAct、规划复核和使用配置列表的知识问答。文档向量化仍由 Embedding 配置控制。

前端首页的「运行审计」展示当前身份最近的运行、模型切换、工具调用、各阶段时间线和已返回的 Token。审计快照写入数据目录的 `agent-runs`，接口为 `GET /api/agent-runs` 和 `GET /api/agent-runs/{id}`。记录只包含执行元数据和归类后的错误码，缺失的用量显示为 `—`。

## 知识库空间

前端在「知识库空间」页面创建或选择空间并上传文档，聊天页可从下拉框选择空间。管理员可以创建空间、上传和删除文档。已认证用户可列出和查询空间，空间之间的隔离用于限定检索和会话范围。

| 接口 | 空间参数 |
|---|---|
| `GET /api/knowledge-base/spaces` | 返回可选空间 |
| `POST /api/knowledge-base/wiki-compile?spaceId=...` | 管理员提交 Wiki 编译任务，复用任务接口查询进度 |
| `GET /api/knowledge-base/wiki-graph?spaceId=...` | 返回选定空间已编译的 Wiki 页面与引用 |
| `GET /api/knowledge-base/wiki-pages/{pageId}?spaceId=...` | 读取编译后的 Markdown 与来源文档 |
| `POST /api/knowledge-base/spaces` | JSON 请求体 `{"name":"项目资料","medical":false}`，创建医疗空间时传 `medical:true` |
| `POST /api/knowledge-base/upload` | 表单字段 `file` 和 `spaceId` |
| `GET /api/knowledge-base/files`、`DELETE /api/knowledge-base/files/{id}` | 查询参数 `spaceId` |
| `POST /api/knowledge-base/retrieve`、`/query`、`/query-with-sources` | JSON 请求体中的 `spaceId`、`question`，检索和带来源问答支持 `topK` |
| `POST /api/rag-evaluations` | JSON 请求体中的 `spaceId` 和评测集 |

原有医疗数据沿用内置 `medical` 空间的索引、存储位置和空间 ID。向任一医疗空间上传时，还需要提交 `issuer`、`version`、`publishedAt`、`audience`、`documentType`、`effectiveStatus=ACTIVE` 和 `reviewStatus=APPROVED`。新建的通用空间只需上传文件。

Wiki 编译读取文档解析目录中的完整 Markdown，使用已配置的模型提取实体、概念和综合页，再校验同空间引用。成功产物位于 `doc-mcp.runtime-dir/wiki/<spaceId>/<版本>/` 下的 `entities`、`concepts`、`syntheses`，每个目录内保存 Markdown，`current` 指向最新成功版本。原文变更后需要重新编译。单次最多 100 份索引文档、24 万字符和 150 个 Wiki 页面。

SQLite 按空间存储和查询文档及片段，Milvus 在选定分区内召回。内置 `medical` 沿用已有的 Milvus 默认分区，新建空间分别使用各自的分区。文件路径、解析缓存和问答缓存都按空间区分。

主应用不在启动阶段构建内存向量库；MinerU 解析结果由 Java 文档 MCP 完成 `4096/1024/256` 分层切片、Embedding、Milvus/关键词召回和 MMR 重排。

完整会话和账号画像默认写入 MySQL，已有 Redis 或文件历史可在启动时导入。设置 `agent.memory.long-term.enabled: true` 后，异步压缩的会话摘要及向量写入独立的 Milvus 集合，按账号和知识库范围跨会话召回。连接方式见 [MySQL 部署配置](docs/mysql/README.md)。

删除会话会清理该会话的 Milvus 摘要、MySQL 会话和消息，以及 Redis 或文件中的旧历史。账号画像保留。任一步报错时可重试同一个删除请求。

本地使用 OSS 时，把 AccessKey 写在运行目录的 `config/application.yml` 中，并将该文件留在版本库之外。

## 安全边界

- 急症红旗优先建议拨打 120 或就近急诊，不等待模型生成。
- 处方、停药换药和个体剂量必须由医生或药师审核。
- 药物问题必须考虑过敏史、合并用药和特殊人群信息。
- 证据不足时拒绝结论，来源版本冲突时明确提示。
- 医疗事实必须可追溯，网页摘要不能作为高风险结论的唯一证据。
- 健康信息按敏感数据处理，最小化收集并禁止不必要的身份标识。
- 网页内容作为不可信数据处理；网页读取工具禁止访问本机和内网地址。
