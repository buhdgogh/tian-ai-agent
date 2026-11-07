# MySQL 任务、工具审计与用户画像

Admin 默认 `AGENT_PERSISTENCE_BACKEND=mysql`，数据库是任务和审计查询的权威来源。
任务/画像直接写 MySQL；工具审计先投递 RabbitMQ，再由消费者异步写 MySQL。
审计发送失败会明确报错；消费写库失败按最多3次重试处理，耗尽后保存在死信队列，不会悄悄改存内存。
消息链路配置和补偿说明见 [RabbitMQ 工具审计](RABBITMQ_TOOL_AUDIT.md)。会话近期消息仍使用原来的 Redis/File；
长期摘要仍由独立的 `AGENT_LONG_TERM_MEMORY_ENABLED` 控制。

## 配置和启动

先创建 MySQL 数据库与专用账号，授予该库的 CREATE、INDEX、SELECT、INSERT、UPDATE、DELETE 权限。
Admin 启动时执行 `src/main/resources/db/agent-schema.sql` 创建缺失表，不删除已有表或数据。
表使用 InnoDB、utf8mb4 和区分大小写的 utf8mb4_bin，用户 ID 不会因大小写被错误合并。

本地 JVM 启动前设置环境变量（Spring Boot 不自动加载项目根目录的 `.env`）：

```powershell
$env:AGENT_PERSISTENCE_BACKEND = 'mysql'
$env:AGENT_MYSQL_URL = 'jdbc:mysql://localhost:3306/tian_agent?connectTimeout=5000&socketTimeout=10000&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$env:AGENT_MYSQL_USERNAME = 'tian_agent'
$env:AGENT_MYSQL_PASSWORD = '<数据库密码>'
```

未配置新变量时兼容 `AGENT_MEMORY_MYSQL_URL/USERNAME/PASSWORD`。不要把真实密码提交到仓库。
Compose 的 Admin 服务已经接入同一套配置，使用 `.env` 中的 MYSQL_DATABASE、MYSQL_USER、MYSQL_PASSWORD。
工具审计的 RabbitMQ 连接及重试参数直接在 `application.yml` 中配置；Compose 使用 `application-docker.yml`
及 `deploy/rabbitmq/rabbitmq.conf`，无需 RabbitMQ 环境变量。
无需开启长期记忆即可保存任务、工具审计和用户画像。

开发兼容模式可显式设置 `AGENT_PERSISTENCE_BACKEND=file`：任务保存 JSON，工具审计仅保留内存窗口，
用户画像 API 不注册。该模式不满足持久化审计/画像的生产需求。

## 表与查询

| 表 | 保存内容 |
|---|---|
| agent_task | ownerId、任务类型/状态/进度/阶段、输入、最终结果、错误、最近100条事件和起止时间 |
| agent_tool_audit | 工具名、耗时、状态、错误、参数哈希，以及 taskId/ownerId/chatId/stepId/iteration/toolCallId |
| agent_user_profile | userId、昵称、语言、偏好、用户明确确认的事实、创建/更新时间 |
| agent_user | 默认用户、密码哈希和登录时间 |
| agent_chat_session / agent_chat_message | 用户不同会话及完整问答历史，见 [用户会话说明](USER_CONVERSATIONS.md) |

任务现有接口不变，内部改为查询 MySQL：

```http
GET /api/tasks/{taskId}
GET /api/tasks?ownerId=user_001&type=AGENT_WORKFLOW
DELETE /api/tasks/{taskId}
```

取消只影响运行/排队任务，已经完成或失败的任务保持原状态。
启动时把中断的 RUNNING/PENDING 任务持久化标记为 FAILED，不自动重放外部副作用。
当前调度器仍要求单 Admin 实例；MySQL 持久化并不等于分布式调度，不要同时启动多个调度实例。

审计支持数据库过滤与分页，不再只保留最近500条：

```http
GET /api/tool-gateway/audits?taskId=<任务ID>&limit=100&offset=0
GET /api/tool-gateway/audits?ownerId=user_001&status=FAILED&limit=100&offset=0
GET /api/tool-gateway/audits?chatId=chat_001
```

`limit` 上限500。状态为 SUCCESS / FAILED / BLOCKED / REJECTED。
RabbitMQ 投递与消费之间存在延迟，刚完成的工具调用不一定立即出现在 MySQL 查询结果中。
工具自身返回 `ok=false`、`isError=true` 或已识别错误文本也会记为 FAILED。
审计记录参数的哈希，不保存完整参数、API token 或完整工具结果。

任务上下文在工作流、并行工具线程池和 Agent SSE 后台线程间显式传播，并在调用结束后清理。
工作流记录轮次和步骤 ID，ReAct 记录模型生成的工具调用 ID。
普通对话没有异步任务 ID 时 `taskId` 为 null，可用 `chatId` 查询；
`/api/ai/medical/tool-chat` 使用 Spring AI 内置执行时工具调用 ID 可能为空。
直接 MCP 操作的工具名带 `mcp:` 前缀；其下游与外层 Agent 工具可能各有一条审计，这表示两个调用层级。
MCP 业务操作内部的会话重连重试合并为一次逻辑审计，不记录每次 HTTP 尝试。

文档批处理可能合并多个任务；每个文档原任务记录 `document:parseAndIndex`，其耗时包含批处理等待、
提交和轮询。批处理线程中的物理 `mcp:parse_documents_batch/get_parse_task` 记录可能没有单一 taskId，
不会错误地把所有文档分配给第一个任务。进程被强制终止时尚未返回的工具调用没有终态审计。

## 用户画像

画像按稳定的 userId 保存，独立于 chatId。多个会话使用相同 userId 可归属于同一用户。
这是存储与管理接口，不会自动从对话抽取病史，也不会自动向模型注入画像。

```http
PUT /api/users/user_001/profile
Content-Type: application/json

{
  "displayName": "小天",
  "preferredLanguage": "zh-CN",
  "preferences": {
    "answerStyle": "简洁",
    "comparisonFormat": "表格"
  },
  "confirmedFacts": ["用户明确表示偏好中文回答"]
}
```

```http
GET /api/users/user_001/profile
DELETE /api/users/user_001/profile
```

PUT 为完整替换；省略 preferences/confirmedFacts 表示清空，创建时间保留，更新时间刷新。
GET 不存在返回404，非法画像返回400，DELETE 幂等并返回204。
userId 允许字母、数字、下划线、连字符，长度1～128；偏好最多30项，事实最多50项。
每条事实最长500字符。只提交用户明确提供并同意保存的事实。
删除画像不删除任务记录或会话记忆，删除会话也不删除用户画像。

聊天请求新增可选 userId，旧请求仍兼容：

```json
{"message":"请比较这两份资料", "chatId":"chat_001", "userId":"user_001"}
```

自动分派工作流优先使用 userId 作为任务 ownerId；旧客户端省略时保留 chatId 作为 ownerId 的兼容行为。
直接提交 `/api/ai/workflows` 时请把 ownerId 设置为同一个稳定 userId。
当前用户/会话入口已接入默认用户与HTTP Session，默认自动登录zhangsan；聊天userId/工作流ownerId必须与当前用户一致。
本次不是全站权限体系，其他管理入口仍应按部署要求配置访问控制。数据库持久化不会自动提供字段加密。

## 迁移已有 JSON 任务

首次启用 MySQL 时可设置 `AGENT_IMPORT_LEGACY_TASKS=true`，启动后读取当前运行目录的 `tmp/tasks/*.json`。
按任务主键只导入不存在的记录，重复运行不会覆盖数据库中的新状态，也不删除原文件。
完成后关闭该开关。历史内存工具审计无法恢复。

## 验证

新增测试用 H2 MySQL 兼容模式执行真实 JDBC 建表/读写，覆盖进度、结果、错误、重启恢复、
幂等导入、取消竞争、审计过滤/分页、并行调用上下文、业务失败识别和画像 CRUD/API。
这不等于已连接生产 MySQL；部署前应使用目标 MySQL 实例完成连接和权限验证。
