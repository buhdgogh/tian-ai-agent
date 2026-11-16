# Tian AI Agent 桌面端

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

## 工作区和 Agent

1. 打开「工作区」，选择本地子目录，然后点击「创建沙箱」。首次会下载 `alpine:3.22`。
2. 目录以读写方式挂载到容器的 `/workspace`。可展开文件树、执行容器命令并查看退出码。容器内写入会同步到本机。
3. 在「Agent 问答」里使用下表中的格式。目录已绑定的会话会显示工作区位置。若启动了其他目录的沙箱，原会话仍需使用它绑定的目录。
4. 使用完成后点击「停止并清理」。正常关闭应用也会尝试删除本实例的沙箱。

| 输入 | 操作 |
| --- | --- |
| `/ls` 或 `/ls docs` | 列出工作区根目录或子目录 |
| `/read docs/notes.txt` | 读取普通文件 |
| 第一行 `/write docs/notes.txt`，下一行开始输入正文 | 写入或覆盖指定文件，上级目录需已存在 |
| `/exec pwd; ls -a` | 在容器内执行 Shell 命令 |

也可使用「执行命令：」「读取文件：」「列出目录：」「写入文件：」作为明确前缀。普通问题由 Java Agent 回答，可用服务端模型路由、知识库和工作流。Rust 不会把沙箱 ID、工作区路径或本地操作结果自动发给 Java。当前桌面会话列表保存在内存中，退出应用或更换 Java 访问配置时清空。

容器禁用网络，根文件系统只读，并限制内存、CPU 和进程数。容器 Shell 以约 20 秒为执行时限，每个输出流最多显示 64 KiB；显式读写接口限制在 `/workspace` 内，单次写入最多 32 KiB 文本。容器和宿主机对挂载目录共享读写权限，请选择打算授权的目录。

## 离线、日志和故障排查

| 现象 | 检查方法 |
| --- | --- |
| Java 不可用或超时 | Docker 工作区仍可操作。启动 Java 后点击「重新连接 Java」或重试问答，再打开知识库验证恢复。 |
| Java 返回 401 或 403 | 检查访问令牌及账号权限。知识库新建、导入、删除和 Wiki 编译等管理操作需要管理员账号。MCP 设置还需要服务端启用相应持久化。 |
| Docker 不可用 | 启动 Docker Desktop 的 Linux 引擎，确认本机账号能运行 `docker info`，再点击「检测 Docker」。 |
| 首次创建容器失败 | 确认 Docker 可以拉取 `alpine:3.22`，并且 Docker Desktop 可访问工作区所在磁盘。 |
| 强制关闭后有残留容器 | 用 `docker ps -a --filter label=com.tianaiagent.sandbox=true` 列出沙箱。核对 ID 后清理对应容器。 |
| 开发窗口未打开 | 确认 MSVC、WebView2 及 Vite 的 `127.0.0.1:1420` 端口可用。 |

在「运行日志 → 桌面诊断」查看本地 Java 与 Docker 操作的结果和耗时，Java 运行记录单独从服务端读取。桌面日志位于 Tauri 应用日志目录下的 `desktop-operations.jsonl`，达到 1 MiB 时轮换，并保留上一份。日志记录操作、结果、时间、耗时与 HTTP 状态，不写入访问令牌、命令正文、会话消息、文件内容或工作区路径。

客户端的 Java 操作使用 Rust 白名单，只能请求本机地址和预定义接口。Tauri 的 `main` capability 仅向主窗口开放对应的健康检查、配置、会话、诊断和工作区命令。系统目录选择由 Rust 发起，前端无需通用文件系统、Shell、HTTP 或 Dialog 插件权限。桌面文档导入单文件上限为 12 MiB。

## 验收与后续扩展

```powershell
npm run build
cargo test --manifest-path src-tauri/Cargo.toml -- --include-ignored
```

Docker 集成测试会创建专用临时目录和容器，验证嵌套目录、路径限制、Shell 输出、文件读写和清理。Java 协议测试使用临时本地 HTTP 服务，验证不可达与恢复、访问令牌以及流事件。用真实部署验收时，在 Java 停止期间通过会话执行 `/write check.txt`（换行填写内容）、`/read check.txt` 和 `/exec pwd`，再启动 Java 并重试知识库问答。

可在后续扩展多工作区与并行隔离、Standard / Auto / YOLO 权限模式、可配置的容器镜像与资源配额，以及加密的本地会话持久化。
