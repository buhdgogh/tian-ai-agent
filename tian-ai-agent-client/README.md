# Tian Agent 工作台 桌面端

桌面端使用 Tauri 2、Rust、Vue 3、TypeScript、Vite、Pinia 和 Vue Router。Vue 通过 Tauri command 请求 Rust 执行操作。Rust 使用 Bollard 管理本机 Docker，使用 HTTP 调用独立运行的 Spring Boot 服务。

## 环境和启动

- Node.js 20.19+ 或 22.12+、npm、Rust stable。Windows 上需安装 MSVC C++ Build Tools 和 WebView2 Runtime。[Tauri 环境要求](https://v2.tauri.app/start/prerequisites/)
- Docker Desktop 需切到 Linux 容器模式。Windows 使用本机 Docker 命名管道。Unix 使用 `/var/run/docker.sock`，请用有 Docker 权限的普通账号运行。
- Java Spring Boot 由用户或系统独立启动。桌面端默认检查 `http://127.0.0.1:8080/api/health`，要求 HTTP 200 且正文为 `ok`。本仓库的 Spring Boot 默认端口是 `8123`。

在 `tian-ai-agent-client` 根目录执行：

```powershell
npm ci
$env:TIAN_JAVA_PORT = '8123'    # 按实际 Java 服务端口填写
npm run tauri dev
```

在「配置中心 → 访问配置」填写 Spring Boot 的端口和访问令牌。应用也可从启动进程的 `TIAN_JAVA_PORT` 和 `TIAN_JAVA_TOKEN` 读取默认值。表单更新立即生效，仅保存在当前进程。模型 API Key、MCP Token 和工具凭据由现有 Java 服务管理。Java 服务状态页可手动重试探测。

`npm run build` 检查前端类型并生成生产资源。以下命令可生成独立桌面可执行文件。Windows 产物位于 `src-tauri/target/release/tian-ai-agent-desktop.exe`：

```powershell
npm run tauri build -- --no-bundle
```

## 桌面数据存储

默认使用 SQLx 和 SQLite。数据库位于 Tauri 的应用数据目录 `com.tianaiagent.desktop/workbench.sqlite3`，Windows 上通常为 `%APPDATA%\com.tianaiagent.desktop\workbench.sqlite3`。重启后恢复会话、历史消息、执行回执和已授权的本地工作区。运行中的容器按需重建。

使用 MySQL 时，先创建供桌面端使用的数据库，再在启动桌面应用的 PowerShell 中设置连接地址。账号需要该库的建表及读写权限，密码中的 URL 保留字符需要百分号编码。

```powershell
$env:TIAN_DESKTOP_MYSQL_URL = 'mysql://USER:PASSWORD@127.0.0.1:13306/tian_agent_desktop'
npm run tauri dev
```

也可在同一个 PowerShell 中启动发布版 EXE。配置了 MySQL 后，会话和消息写入 MySQL；连接失败时会提示重试。切换会话数据库不会迁移已有数据。本机 SQLite 始终保存目录授权和 Java 访问配置，防止远程数据库中的路径被当作本机访问授权。Windows 上 Java 访问配置由当前用户的 DPAPI 保护，其他平台将其保存在当前用户私有的应用数据目录。会话按本机安装和 Java 访问身份区分，切回原连接配置时可看到对应历史。

### 与 Web 共享会话

Agent 问答显示通用范围的共享历史，知识库问答显示知识库范围的共享历史。使用与 Web 相同的 Java 服务和账号，点击「同步历史」，再选择「共享」会话打开或续聊。普通问答由 Java 保存到原会话，Web 重新打开即可看到；桌面 SQLx 缓存会话列表和已打开的消息，Java 离线时仍可查看已缓存内容。共享会话的删除会同步到 Java。

Docker 文件操作、命令、回执、工作区路径和目录授权保存在桌面端。同一共享会话叠加的本机执行结果仅在本机显示。

## 工作区和 Agent

可以直接新建会话并提交任务。未选工作区时，文件操作会按需启动沙箱，文件保存在应用数据目录 `agent-files/<访问配置标识>/<会话 ID>`，回复会显示完整路径。每个未绑定会话有自己的默认文件目录。

1. 在「Agent 问答」打开一个会话，从输入框旁选择本地目录，需要访问当前目录的任务会自动启动沙箱，首次会下载带 Python 3.13 的默认镜像。也可以提前点击「启动沙箱」。
2. 目录以读写方式挂载到容器的 `/workspace`。可展开文件树、执行容器命令并查看退出码。容器内写入会同步到本机。
3. 会话选定目录后即固定绑定。点击「添加工作区」可选择目录并创建首个会话。左侧按工作区分组，点击分组旁的「＋」可在同一目录下新建独立会话。「新建对话」创建普通会话。同一工作区内的会话分别启动自己的沙箱，共用挂载的本机文件。
4. 可在当前会话停止自己的沙箱。删除会话会清理该会话的容器，正常退出会清理本实例的所有沙箱。侧栏「工作区」仍提供独立的手动终端和文件树。

| 输入 | 操作 |
| --- | --- |
| `/ls` 或 `/ls docs` | 列出工作区根目录或子目录 |
| `/read docs/notes.txt` | 读取普通文件 |
| 第一行 `/write docs/notes.txt`，下一行开始输入正文 | 写入或覆盖指定文件，上级目录需已存在 |
| `/exec pwd; ls -a` | 在容器内执行 Shell 命令 |

也可使用「执行命令：」「读取文件：」「列出目录：」「写入文件：」作为明确前缀。普通问题由 Java Agent 回答，可用服务端模型路由、知识库和工作流。手动命令在 Rust 与 Docker 内执行。普通会话写入当前配置的桌面数据库，可在重启后继续。

容器禁用网络，根文件系统只读，并限制内存、CPU 和进程数。容器 Shell 以约 20 秒为执行时限，每个输出流最多显示 64 KiB；显式读写接口限制在 `/workspace` 内，单次写入最多 32 KiB 文本。容器和宿主机对挂载目录共享读写权限，请选择打算授权的目录。

在「Agent 问答」选中工作区后，Agent 会判断请求是否需要访问该目录。工作区任务会按需要调用勘查、修改、测试或复核 Agent。可以用自然语言指定角色与顺序，例如「先修改再测试，跳过复核」或「只调用测试 Agent」。普通问答走常规对话。应用将工具限定在当前已授权的沙箱。勘查和复核只读，修改可以创建文件或精确替换原文，测试命令在容器中执行。工作区仍以读写方式挂载，变更会同步到原目录。用于模型判断的文件片段、相对路径和测试输出会通过本机 Java 发送给配置的模型。

默认沙箱可执行 Python 和 Shell 测试。测试项目需要 Node、JDK 或额外依赖时，可在启动桌面端前用 TIAN_SANDBOX_IMAGE 指定预装所需依赖且包含 POSIX Shell、tail 和 timeout 的镜像，再重新创建沙箱。容器内网络仍然关闭。多 Agent 的单次测试命令最多运行 90 秒。编辑工具支持不超过 64 KiB 的普通 UTF-8 文本。

## 离线、日志和故障排查

| 现象 | 检查方法 |
| --- | --- |
| Java 不可用或超时 | Docker 工作区仍可操作。启动 Java 后点击「重新连接 Java」或重试问答，再打开知识库验证恢复。 |
| Java 返回 401 或 403 | 检查访问令牌及账号权限。知识库新建、导入、删除和 Wiki 编译等管理操作需要管理员账号。MCP 设置还需要服务端启用相应持久化。 |
| Docker 不可用 | 启动 Docker Desktop 的 Linux 引擎，确认本机账号能运行 `docker info`，再点击「检测 Docker」。 |
| 首次创建容器失败 | 确认 Docker 可以拉取 `python:3.13-alpine`，并且 Docker Desktop 可访问工作区所在磁盘。 |
| 强制关闭后有残留容器 | 用 `docker ps -a --filter label=com.tianaiagent.sandbox=true` 列出沙箱。核对 ID 后清理对应容器。 |
| 开发窗口未打开 | 确认 MSVC、WebView2 及 Vite 的 `127.0.0.1:1420` 端口可用。 |

在「运行日志 → 桌面诊断」查看本地 Java 与 Docker 操作的结果和耗时，在「Java 运行」中查看服务端记录并打开「查看 Trace 调用链」，查阅每个角色的模型、工具与 Token。多 Agent 沙箱日志附有同一 Trace ID。桌面日志位于 Tauri 应用日志目录下的 `desktop-operations.jsonl`，达到 1 MiB 时轮换，并保留上一份。日志记录操作、结果、时间、耗时与 HTTP 状态，不写入访问令牌、命令正文、会话消息、文件内容。本轮换工作区的授权审计记录目标路径及容器 ID。

客户端的 Java 操作使用 Rust 白名单，只能请求本机地址和预定义接口。Tauri 的 `main` capability 仅向主窗口开放对应的健康检查、配置、会话、诊断和工作区命令。系统目录选择由 Rust 发起，前端无需通用文件系统、Shell、HTTP 或 Dialog 插件权限。桌面文档导入单文件上限为 12 MiB。

## 验收与后续扩展

```powershell
npm run build
cargo test --manifest-path src-tauri/Cargo.toml -- --include-ignored
```

Docker 集成测试会创建专用临时目录和容器，验证嵌套目录、路径限制、Shell 输出、文件读写和清理。Java 协议测试使用临时本地 HTTP 服务，验证不可达与恢复、访问令牌以及流事件。用真实部署验收时，在 Java 停止期间通过会话执行 `/write check.txt`（换行填写内容）、`/read check.txt` 和 `/exec pwd`，再启动 Java 并重试知识库问答。

可在后续扩展多工作区与并行隔离、Standard / Auto / YOLO 权限模式、可配置的容器镜像与资源配额，以及会话数据库加密。
