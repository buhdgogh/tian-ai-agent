# Tian Agent 工作台

Tian 是一个用于智能问答、任务编排和工具调用的 AI Agent 工作台。Agent 可以直接对话，也可以检索选定的知识库并带来源回答。知识库支持按主题导入资料和浏览 Wiki 图谱，内置的 `medical` 空间用于管理医疗资料。桌面端还支持在 Docker 工作区内执行命令和读写文件。

## 工作台

| 页面 | 功能 |
| --- | --- |
| **知识库导入** | 创建通用或医疗空间，批量选择文件，查看异步解析和索引进度。同一空间内按文件 SHA-256 去重，删除空间时需输入当前账号名确认。 |
| **Agent 问答** | 可选一个完整知识库空间。意图路由选择 ReAct 或 Planner–Executor–Reviewer 工作流，页面通过 SSE 展示回复、执行路径和工具调用。输入框右下角可为后续请求指定优先模型。 |
| **Wiki 知识图谱** | 按空间读取完整解析 Markdown，分段提取实体、概念和综合页，生成带来源和引用的 Markdown。支持在图谱上查看相邻节点和页面正文。跨文档综合阶段遇到模型不可用时，可保留已提取的页面和引用。 |
| **运行日志** | 按账号查看模型尝试与切换、工具调用、执行阶段、缓存命中与未命中、耗时、Token 和失败原因。 |
| **模型设置** | 按账号配置 OpenAI 兼容或 Anthropic 接口，可设置 Base URL、模型名、API Key 和优先级。已有 DashScope 原生配置可继续管理。 |
| **MCP 设置** | 添加远程 Streamable HTTP 服务，发现工具、勾选授权项并保存。支持可选的 Bearer Token。 |

文档经过解析、清洗和分层切片后入库。清洗会处理可识别的页眉页脚、页码、水印和重复文本块。配置 MinerU 后可利用版面解析能力，自动模式可回退到本地 Tika。检索在选定空间内融合向量相似度与关键词覆盖率，再通过 MMR 降低结果重复。解析和索引配置见 [文档处理与 MCP](tian-ai-agent-backend/docs/guides/document-mcp.md)。

## 项目结构

```text
Tian-AI-Agent-main/
├── tian-ai-agent-frontend/        Web 工作台（Vue 3、Vite 4、TinyRobot、ECharts）
├── tian-ai-agent-client/          独立桌面端（Tauri 2、Rust、Vue 3、TypeScript、Pinia）
│   ├── src/                      桌面页面、路由和 Tauri 调用封装
│   └── src-tauri/                Docker 沙箱、Java 客户端和桌面打包配置
├── tian-ai-agent-eval/            Agent 与 RAG 评估
└── tian-ai-agent-backend/         Java 21、Spring Boot 3.5、Spring AI 1.0
    ├── tian-ai-agent-api/         HTTP 和 MCP 请求、响应契约
    ├── tian-ai-agent-app/         Spring Boot 启动类、Bean 装配与运行配置
    ├── tian-ai-agent-domain/      Agent、会话、知识库、医疗、任务业务域
    ├── tian-ai-agent-infrastructure/  数据库 Mapper、模型和外部服务适配
    ├── tian-ai-agent-trigger/     REST、SSE 和 MCP 入站接口
    ├── tian-ai-agent-types/       跨模块配置、类型与异常
    └── docs/                     MySQL 初始化脚本、Docker Compose 和使用说明
```

桌面端通过 Tauri command 调用 Rust。Rust 通过本地 HTTP 使用 Java 的 Agent、知识库、Wiki 和模型治理能力，通过 Bollard 单独管理 Docker 工作区。Spring Boot 独立启动，桌面端可在 Java 停止时继续使用 Docker 工作区。

领域模块按业务划分，每个业务域按需组织 `adapter`、`model` 和 `service`。MySQL 和 SQLite 使用 MyBatis-Plus，分别绑定各自的数据源。

| 存储 | 数据 |
| --- | --- |
| MySQL | 完整会话和消息、账号画像、模型配置、个人 MCP 配置、运行摘要 `agent_run` 和执行明细 `agent_request_log`。 |
| SQLite | 知识库空间、解析任务、文档和 Chunk 元数据。默认文件是后端的 data/knowledge.sqlite，本地向量也保存在该文件。 |
| Milvus | 可选的知识库和长期记忆存储。叶子 Chunk 和长期记忆分别写入独立集合，默认使用 SQLite。 |
| Redis | 上传限流、RAG 语义缓存，以及可导入的旧版会话历史。 |
| 文件系统或 OSS | 原始上传文件。解析后的 Markdown 和编译后的 Wiki 版本保存在配置的本地目录。 |

## 开发环境

| 部分 | 环境要求 |
| --- | --- |
| Java 后端 | JDK 21、Maven。后端使用 Spring Boot 3.5.13 和 Spring AI 1.0.0。 |
| Web 前端 | Node.js 20 或更新版本、npm；Vue 3 和 Vite 4。 |
| 桌面前端 | Node.js 20.19+ 或 22.12+、npm；Vue 3、TypeScript 和 Vite 7。 |
| Windows 桌面开发和打包 | Tauri 2、Rust stable MSVC、Visual Studio C++ 桌面开发工作负载、Windows SDK 和 Microsoft Edge WebView2 Runtime。 |
| 本地数据服务 | Docker Compose 启动 MySQL、Redis、Milvus、etcd 和 MinIO，SQLite 由后端在本地打开。 |

后端构建前运行 `mvn -v`，确认 Maven 使用 Java 21。如果当前终端使用其他 JDK，可在 PowerShell 中为本次会话切换。下面的路径请替换为实际安装位置。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -v
```

使用 rustup 安装 Rust 后，在 Windows x64 上运行 `rustup default stable-msvc`，并用 `rustc -vV` 确认 `host` 为 `x86_64-pc-windows-msvc`。Tauri CLI 随桌面端 npm 依赖安装。 [Tauri Windows 前置要求](https://v2.tauri.app/start/prerequisites/#windows)

## 本地启动

使用 Web 或桌面端的 Java 功能时，先启动数据服务与后端。只使用桌面 Docker 工作区时，可以直接启动桌面端。

### 1. 准备 MySQL 和 Docker 依赖

Compose 首次初始化 MySQL 数据卷时创建 `tian_ai_agent` 库并设置 `root` 密码。后端启动后执行 [建表 SQL](tian-ai-agent-backend/docs/mysql/tian_ai_agent.sql)。已有项目库时，先把源库导入新容器并核对数据，再启动后端。 [MySQL 迁移说明](tian-ai-agent-backend/docs/mysql/README.md)

在项目根目录打开 PowerShell，进入后端目录并准备 `.env`。

```powershell
cd tian-ai-agent-backend
if (!(Test-Path .env)) { Copy-Item docs/docker/.env.example .env }
```

在后端根目录的 `.env` 通过 `MYSQL_PASSWORD` 设置 MySQL root 密码，按需配置 `MINERU_TOKEN` 和 `SEARCH_API_KEY`。后端默认连接 `127.0.0.1:13306`，可通过 `MYSQL_PORT` 修改宿主机端口。

```powershell
docker compose --env-file .env -f docs/docker/compose.yml up -d
docker compose --env-file .env -f docs/docker/compose.yml ps
```

### 2. 启动后端

已有旧知识库的工作区可在首次启动新版前按[SQLite 迁移说明](tian-ai-agent-backend/docs/sqlite/README.md)迁移数据。

在 [application.yml](tian-ai-agent-backend/tian-ai-agent-app/src/main/resources/application.yml) 的 `agent.access` 下配置访问令牌。管理员使用 `admin-token`，普通用户在 `users` 中配置 `id` 和 `token`，外部知识库 MCP 客户端可使用专用的 `mcp-token`。各令牌使用不同的值，长度至少 24 个可打印 ASCII 字符。

在后端根目录打包并启动应用。

```powershell
mvn -pl tian-ai-agent-app -am package -DskipTests
java -jar tian-ai-agent-app/target/tian-ai-agent-backend.jar
```

也可以在 IDEA 中运行 `TianAiAgentAdminApplication`，工作目录选后端根目录。启动参数加上 `--spring.profiles.active=local` 可启用长期记忆。知识库向量和长期记忆默认存入 SQLite，将 `doc-mcp.vector-store` 配置为 `milvus` 可统一切换到 Milvus。

- 后端 API 根路径为 `http://localhost:8123/api`。
- 健康检查为 `GET /api/health`。
- Swagger UI 路径为 `/api/swagger-ui.html`。

### 3. 启动 Web 端

从项目根目录另开 PowerShell：

```powershell
cd tian-ai-agent-frontend
npm ci
npm run dev
```

打开 [Web 工作台](http://localhost:3000)，在右上角「设置访问令牌」填写步骤 2 中配置的令牌。Vite 将同源 `/api` 请求代理到 `http://127.0.0.1:8123`。如需直接连接其他后端，可在 Web 项目的 `.env.local` 设置 `VITE_API_BASE_URL`。 [Web Vite 配置](tian-ai-agent-frontend/vite.config.js)

### 4. 启动 Windows 桌面端

从项目根目录另开 PowerShell：

```powershell
cd tian-ai-agent-client
npm ci
$env:TIAN_JAVA_PORT = '8123'
npm run tauri dev
```

桌面开发服务使用 `127.0.0.1:1420`，可与 Web 开发服务同时运行。桌面端默认探测 `http://127.0.0.1:8080/api/health`；本仓库的 Java 服务使用 `8123`，因此上例显式设置端口。

也可以在桌面端「配置中心 → 访问配置」填写本机 Java 端口和访问令牌。应用启动时可读取 `TIAN_JAVA_PORT` 和 `TIAN_JAVA_TOKEN`；页面保存的覆盖值仅在当前进程中生效。通过「Java 服务状态」可复检连接。 [桌面端说明](tian-ai-agent-client/README.md)

### Docker 工作区

1. 启动 Docker Desktop 并切到 Linux 容器模式。在桌面端「工作区」选择本地子目录，再点击「创建沙箱」。首次使用会拉取 `alpine:3.22`。
2. 目录以读写方式挂载到容器内的 `/workspace`。可在文件树中浏览文件，在 Agent 会话中用 `/ls` 列表、`/read 路径` 读取、`/write 路径` 换行写入，或用 `/exec pwd` 执行命令。容器内写入会同步到所选宿主机目录。
3. 使用完毕后点击「停止并清理」。Java 不可用时仍可执行上述操作，Java 恢复后可在 Agent 页点击「重新连接 Java」。在「运行日志 → 桌面诊断」查看 Docker 和 Java 调用的结果及耗时。

## 构建 Web 和桌面端

分别在对应目录安装依赖后执行：

| 目标 | 目录 | 命令 | 产物 |
| --- | --- | --- | --- |
| Web 静态文件 | `tian-ai-agent-frontend` | `npm run build` | `dist/`。部署时由 Web 服务器转发 `/api`，可参考 [Nginx 示例](tian-ai-agent-frontend/nginx.conf)。 |
| Windows 独立可执行文件 | `tian-ai-agent-client` | `npm run tauri build -- --no-bundle` | `src-tauri/target/release/tian-ai-agent-desktop.exe`。 |

独立 EXE 运行需要 WebView2。使用工作区功能的机器需要 Docker Desktop；使用 Java 功能的机器需要在本机运行 Spring Boot 服务，并配置对应的工作台令牌。EXE 不包含 Spring Boot 或 Docker。

## 配置模型和 MCP

### 模型

在「模型设置」选择 **OpenAI 兼容**或 **Anthropic**，填写对应的 Base URL、模型名和厂商 API Key。OpenAI 兼容支持 OpenAI、DeepSeek、Qwen 等预设，Anthropic 默认地址为 `https://api.anthropic.com`。自定义地址的来源需要加入 `agent.models.allowed-user-origins`。

模型配置按账号保存，API Key 加密存入 MySQL。Agent 输入框右下角的「自动选择」按优先级从小到大尝试，同优先级轮询。手动选择会把该模型提前到本轮的首选位置，遇到可安全重试的故障时按原顺序切换。实际尝试和切换记录可在「运行日志」查看。OpenCode 免费模型按显式选择调用，失败时会返回对应错误。

「模型设置」按聊天、向量嵌入和免费模型分组。创建知识库时，从当前账号已启用的嵌入模型中选择一个。OpenCode 免费模型共用一份个人 API Key，后端需要安装官方 OpenCode CLI，并通过 PATH 或 `OPENCODE_EXECUTABLE` 指定可执行文件。保存 Key 后可在 Agent 问答中选择免费模型。 [OpenCode 后端配置](tian-ai-agent-backend/README.md#opencode-平台默认模型)

### MCP

- **连接第三方工具**。先将信任的来源加入 `agent.mcp.allowed-origins` 并重启后端，再在「MCP 设置」填写完整 Streamable HTTP 端点。比如白名单填 `https://tools.example.com`，端点填 `https://tools.example.com/mcp`。发现工具、勾选并保存后，当前账号的通用 Agent 和工作流可以调用它们，执行仍经过工具网关。
- **对外提供知识库工具**。启用 `doc-mcp.enabled` 时，外部客户端可连接 `POST /api/mcp`，调用空间列表、混合检索、带来源问答、Chunk 上下文和空间状态。请求使用管理员或专用 MCP 访问令牌。

工作台访问令牌用于识别本系统账号。模型 API Key 和远程 MCP Bearer Token 分别用于向对应外部服务鉴权。备份数据库时，也要保留后端数据目录下的 `model-settings/storage.key`。

## 常用接口

| 接口 | 用途 |
| --- | --- |
| `POST /api/ai/dispatch/stream` | Agent 问答。请求包含 `message`、`chatId`，可传 `spaceId` 和 `modelProviderId`。 |
| `GET /api/ai/sessions` | 查询当前账号的会话列表。工作台传入 `type=agent`、`workspace=true` 和当前空间。 |
| `GET /api/knowledge-base/spaces`、`POST /api/knowledge-base/upload` | 查看空间和上传文件。上传使用 multipart 表单字段 `file`、`spaceId`。 |
| `POST /api/knowledge-base/wiki-compile`、`GET /api/knowledge-base/wiki-graph` | 按 `spaceId` 提交 Wiki 编译任务或加载图谱。 |
| `GET /api/tasks/{taskId}`、`GET /api/agent-runs/{id}` | 查询任务进度或查看运行审计。 |
| `GET /api/model-providers`、`GET /api/mcp-servers` | 查询当前账号的模型和 MCP 配置。 |

业务接口使用 `Authorization: Bearer <token>`。文档操作需要管理员权限。更多细节见 [后端说明](tian-ai-agent-backend/README.md)、[工作流指南](tian-ai-agent-backend/docs/guides/agentworkflow.md) 和 [Docker Compose](tian-ai-agent-backend/docs/docker/compose.yml)。
