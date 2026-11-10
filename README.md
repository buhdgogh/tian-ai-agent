# Tian 知识工作台

Tian 是一个通用知识库和 AI Agent 工作台。用户可以按主题创建知识库空间，导入资料、查看 Wiki 图谱，再选择一个完整空间进行带来源的问答。省略空间时，Agent 直接对话。内置的 `medical` 空间用于管理医疗资料。

## 工作台

| 页面 | 功能 |
| --- | --- |
| **知识库导入** | 创建通用或医疗空间，批量选择文件，查看异步解析和索引进度。同一空间内按文件 SHA-256 去重，删除空间时需输入当前账号名确认。 |
| **Agent 问答** | 可选一个完整知识库空间。意图路由选择 ReAct 或 Planner–Executor–Reviewer 工作流，页面通过 SSE 展示回复、执行路径和工具调用。输入框右下角可为后续请求指定优先模型。 |
| **Wiki 知识图谱** | 按空间读取完整解析 Markdown，分段提取实体、概念和综合页，生成带来源和引用的 Markdown。支持在图谱上查看相邻节点和页面正文。跨文档综合阶段遇到模型不可用时，可保留已提取的页面和引用。 |
| **运行日志** | 按账号查看模型尝试与切换、工具调用、执行阶段、耗时、Token 和归类后的失败原因。 |
| **模型设置** | 按账号配置 OpenAI 兼容或 Anthropic 接口，可设置 Base URL、模型名、API Key 和优先级。已有 DashScope 原生配置可继续管理。 |
| **MCP 设置** | 添加远程 Streamable HTTP 服务，发现工具、勾选授权项并保存。支持可选的 Bearer Token。 |

文档经过解析、清洗和分层切片后入库。清洗会处理可识别的页眉页脚、页码、水印和重复文本块。配置 MinerU 后可利用版面解析能力，自动模式可回退到本地 Tika。检索在选定空间内融合向量相似度与关键词覆盖率，再通过 MMR 降低结果重复。解析和索引配置见 [文档处理与 MCP](tian-ai-agent-backend/docs/guides/document-mcp.md)。

## 项目结构

```text
Tian-AI-Agent-main/
├── tian-ai-agent-frontend/        Vue 3、Vite、TinyRobot、ECharts
│   └── src-tauri/                Tauri 2 桌面应用和打包配置
└── tian-ai-agent-backend/         Java 21、Spring Boot 3.5、Spring AI 1.0
    ├── tian-ai-agent-api/         HTTP 和 MCP 请求、响应契约
    ├── tian-ai-agent-app/         Spring Boot 启动类、Bean 装配与运行配置
    ├── tian-ai-agent-domain/      Agent、会话、知识库、医疗、任务业务域
    ├── tian-ai-agent-infrastructure/  MyBatis-Plus Mapper、模型和外部服务适配
    ├── tian-ai-agent-trigger/     REST、SSE 和 MCP 入站接口
    ├── tian-ai-agent-types/       跨模块配置、类型与异常
    └── docs/                     MySQL 初始化脚本、Docker Compose 和使用说明
```

领域模块按业务划分，每个业务域按需组织 `adapter`、`model` 和 `service`。MySQL 和 SQLite 使用 MyBatis-Plus，分别绑定各自的数据源。

| 存储 | 数据 |
| --- | --- |
| MySQL | 完整会话和消息、账号画像、模型配置、个人 MCP 配置、运行摘要 `agent_run` 和执行明细 `agent_request_log`。 |
| SQLite | 知识库空间、解析任务、文档和 Chunk 元数据。默认文件是后端的 data/knowledge.sqlite，本地向量也保存在该文件。 |
| Milvus | 可配置为知识库向量后端。启用长期记忆后，压缩摘要及其向量写入独立集合，按账号和知识库范围召回。 |
| Redis | 上传限流、RAG 语义缓存，以及可导入的旧版会话历史。 |
| 文件系统或 OSS | 原始上传文件。解析后的 Markdown 和编译后的 Wiki 版本保存在配置的本地目录。 |

## 开发环境

| 部分 | 环境要求 |
| --- | --- |
| Java 后端 | JDK 21、Maven。后端使用 Spring Boot 3.5.13 和 Spring AI 1.0.0。 |
| Web 和桌面前端 | Node.js 20 或更新版本、npm。使用 Vue 3 和 Vite 4，依赖版本由 `package-lock.json` 锁定。当前主机使用 Node.js 22.19.0 和 npm 10.9.3。 |
| Windows 桌面开发和打包 | Tauri 2、Rust stable MSVC 工具链（项目声明 Rust 1.90 起）、Visual Studio C++ 桌面开发工作负载、Windows SDK 和 Microsoft Edge WebView2 Runtime。 |
| 本地数据服务 | Docker Compose 启动 MySQL、Redis、Milvus、etcd 和 MinIO，SQLite 由后端在本地打开。 |

后端构建前运行 `mvn -v`，确认 Maven 使用 Java 21。如果当前终端使用其他 JDK，可在 PowerShell 中为本次会话切换。下面的路径请替换为实际安装位置。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -v
```

使用 rustup 安装 Rust 后，在 Windows x64 上运行 `rustup default stable-msvc`，并用 `rustc -vV` 确认 `host` 为 `x86_64-pc-windows-msvc`。Tauri CLI 随前端 npm 依赖安装。 [Tauri Windows 前置要求](https://v2.tauri.app/start/prerequisites/#windows)

## 本地启动

Web 和桌面端共用同一个 Java 后端。先启动数据服务和后端，再选择一个前端开发命令。

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

也可以在 IDEA 中运行 `TianAiAgentAdminApplication`，工作目录选后端根目录。启动参数加上 `--spring.profiles.active=local` 可启用 Milvus 长期记忆。文档检索使用 Milvus 时，另将 `doc-mcp.vector-store` 配置为 `milvus`。

- 后端 API 根路径为 `http://localhost:8123/api`。
- 健康检查为 `GET /api/health`。
- Swagger UI 路径为 `/api/swagger-ui.html`。

### 3. 安装前端依赖

从项目根目录另开 PowerShell，进入前端目录。

```powershell
cd tian-ai-agent-frontend
npm ci
```

### 4. 启动 Web 端

```powershell
npm run dev
```

打开 [本地工作台](http://localhost:3000)。Vite 将同源 `/api` 请求代理到 `http://127.0.0.1:8123`。如果要直接连接另一个后端，在前端的 `.env.local` 中设置 `VITE_API_BASE_URL`。 [Vite 配置](tian-ai-agent-frontend/vite.config.js)

### 5. 启动 Windows 桌面端

```powershell
npm run tauri -- dev
```

Tauri 会在 `127.0.0.1:3000` 启动 Vite，并打开桌面窗口。如果已运行 `npm run dev`，先用 `Ctrl+C` 停止该实例。需要同时查看浏览器和桌面窗口时，运行上面的 Tauri 命令，再在浏览器打开 [同一个开发地址](http://127.0.0.1:3000)。

Web 端在右上角「设置访问令牌」中填写步骤 2 配置的令牌。桌面端首次启动时填写后端 API 地址和访问令牌，通过验证后进入工作台。两端都可以在「模型设置」配置个人模型。

## 构建 Web 和桌面端

在 `tian-ai-agent-frontend` 中执行对应的构建命令。

| 目标 | 命令 | 产物 |
| --- | --- | --- |
| Web 静态文件 | `npm run build` | `dist/`，部署时由 Web 服务器转发 `/api`，项目提供 [Nginx 示例](tian-ai-agent-frontend/nginx.conf)。 |
| Windows NSIS 安装包 | `npm run tauri -- build --bundles nsis` | 桌面静态文件位于 `dist-desktop/`，安装包位于 `src-tauri/target/release/bundle/nsis/`。 |

安装包可分发给多个用户。后端管理员应部署用户可访问的 Java 服务，并为每个用户分配独立的工作台令牌。用户首次打开桌面端时输入后端地址（例如 `https://agent.example.com/api`）和工作台令牌，验证通过后在「模型设置」填写个人模型名称和 API Key。后端地址会保留在本机，访问令牌随窗口会话保存。可在右上角「访问配置」切换后端。远程服务需允许桌面 WebView 的跨域请求。

如需为安装包预填后端地址，可以在打包前通过 `.env.desktop.local` 设置 `VITE_DESKTOP_API_BASE_URL`，用户仍可在客户端修改。

## 配置模型和 MCP

### 模型

在「模型设置」选择 **OpenAI 兼容**或 **Anthropic**，填写对应的 Base URL、模型名和厂商 API Key。OpenAI 兼容支持 OpenAI、DeepSeek、Qwen 等预设，Anthropic 默认地址为 `https://api.anthropic.com`。自定义地址的来源需要加入 `agent.models.allowed-user-origins`。

模型配置按账号保存，API Key 加密存入 MySQL。Agent 输入框右下角的「自动选择」按优先级从小到大尝试，同优先级轮询。手动选择会把该模型提前到本轮的首选位置，遇到可安全重试的故障时按原顺序切换。实际尝试和切换记录可在「运行日志」查看。

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
