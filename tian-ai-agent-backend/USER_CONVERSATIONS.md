# 简易登录与用户会话记录

## 初始化账号与登录

`application.yml` 直接配置：

```yaml
agent:
  user:
    default-username: zhangsan
    default-password: "123456"
```

在 MySQL 模式下，Admin 启动后先创建缺失的表，再初始化用户 `zhangsan` 和同名的用户画像。
用户名同时用作当前实现的稳定 userId。密码在数据库中使用随机盐的 PBKDF2-HMAC-SHA256 哈希保存，
用户接口不返回密码或密码哈希。重复启动不会重置已有用户的密码或覆盖已有画像。

账号初始化不会自动登录。用户必须输入用户名和密码；没有有效 HTTP Session 时，当前用户、对话和工作流入口返回 401。
前端提供 `/login` 页面，登录后返回原页面，Session 失效时重新进入登录页。
登录成功会更换 HTTP Session，并更新最后登录时间：

```http
POST /api/auth/login
Content-Type: application/json

{"username":"zhangsan","password":"123456"}
```

```http
GET /api/auth/me
```

错误用户名/密码返回401。前端应保存服务器的会话Cookie；跨域请求需要按现有部署方式携带Cookie。
退出登录会立即使当前服务端 Session 失效（重复退出仍返回 204）：

```http
POST /api/auth/logout
```

采用用户名/密码加服务端 Session 的简易方案，不引入 JWT、OAuth 或繁琐的认证流程。未实现注册、找回密码或全站权限体系。

### 登录修改后的启动检查

修改源码后，需要重新编译并重启运行中的 Admin。IntelliJ IDEA 启动的旧进程不会因为 Maven 编译了另一输出目录而自动更新。
本项目 Maven 输出目录为 `tmp/admin-build`，打包后应运行该目录中的新 JAR；不要继续运行 `tian-ai-agent-admin/target` 下的旧 JAR。

- 未携带登录 Cookie 调用 `GET /api/auth/me` 应返回 401，不能自动返回张三。
- `POST /api/auth/logout` 应返回 204；即使没有登录 Cookie，也不应返回 404。
- 如果退出接口返回 404，先重新编译、重启后端，再点击“重试退出”。前端不会将接口缺失当作退出成功。

## 数据表与何时产生记录

| 表 | 记录 |
|---|---|
| agent_user | userId、用户名、密码哈希、创建时间、最后登录时间 |
| agent_chat_session | 内部sessionId、userId、前端chatId、会话类型、标题、消息数、创建/更新时间 |
| agent_chat_message | 会话ID、轮次ID、角色、正文、状态、时间和结果元数据 |
| agent_user_profile | 原有画像表，默认用户初始化时创建空画像 |

创建会话时生成会话行；第一次直接提问时也会自动创建。
每轮调用模型或工具前，先提交 USER 消息与 RUNNING 状态的 ASSISTANT 占位消息。
这样即使外部服务失败，数据库中仍能看到用户问了什么，以及本轮尚未成功完成。
成功后更新回复正文和 COMPLETED 状态；异常更新为 FAILED；流取消或后台任务取消更新为 CANCELLED。
消息按自增ID排列；每轮预先保留回复行，避免异步回复混入其他轮次。

普通聊天、结构化报告、工具聊天、RAG、Agent SSE 和工作流最终答案均已接入。
SSE 保存已生成的输出；流错误时保留已收集的部分文本和错误信息。
工作流通过 TaskCenter 的状态持久化回调更新对应消息，包含 taskId 和最终工作流结果元数据，
排队取消和队列拒绝也会结束会话中的占位回复。
重启时将残留 RUNNING 消息标记为 FAILED，不虚构成功回复。

## 会话接口

```http
POST /api/ai/sessions
Content-Type: application/json

{"chatId":"chat_001","type":"medical","title":"第一次咨询"}
```

chatId 可省略，服务端自动生成；type 可省略，默认为 medical。
类型包括 medical（普通/工具聊天/报告）、rag（知识库聊天）、agent（Agent/工作流）。
同一用户的相同 chatId 在不同类型下是独立会话。

```http
GET /api/ai/sessions?type=medical
GET /api/ai/sessions/chat_001/messages?type=medical
DELETE /api/ai/sessions/chat_001?type=medical
```

列表中的 `id` 仍为前端chatId，并增加 sessionId、userId、type、messageCount、createdAt、updatedAt。
消息返回 id、turnId、messageType（USER/ASSISTANT）、text、status、createdAt、updatedAt、metadata。
删除会话会级联删除其数据库消息，并调用原有会话记忆清理；不会删除用户或画像。

原聊天请求格式仍可使用，无需携带userId：

```json
{"message":"你好", "chatId":"chat_001"}
```

如果仍携带userId，必须与当前用户一致，否则返回403；工作流ownerId同理。
会话查询、删除按服务器解析的当前用户过滤，无法通过客户端自报userId读取他人会话。
内部模型记忆使用数据库sessionId，而不是直接使用客户端chatId，避免用户之间相同chatId串话。
工具审计中的ownerId为当前用户，chatId仍为外部chatId，便于查询关联。

## 中断后修改提问

统一聊天请求可以携带客户端生成的 `turnId`。客户端不必等待 LLM 回答完成，即可调用：

```http
POST /api/ai/sessions/chat_001/turns/turn_001/interrupt?type=agent
```

这个接口在短事务中将当前运行回复标为 CANCELLED，并尝试取消已关联的后台任务。
若回答已先完成，则返回 COMPLETED，前端刷新最终结果而不允许覆盖完成的回答。
修改并重新提交使用统一调度接口，携带新 `turnId` 和旧 `replacesTurnId`：

```json
{"chatId":"chat_001","sessionType":"agent","message":"修改后的问题","turnId":"turn_002","replacesTurnId":"turn_001"}
```

服务端锁定该用户的会话行，在同一短事务中检查“最近一轮且已取消/失败”、
把旧问题和回复标为 SUPERSEDED、保留旧内容及任务信息，然后插入新问题和 RUNNING 回复。
两个并发修改只有一个能成功，过期或重复请求返回 409；保存失败会回滚这一次短事务。
历史原始接口仍保留被替代记录用于审计，前端和模型上下文过滤被替代版本。
messageCount 统计物理消息记录，包含保留的旧版本。

LLM 和工具调用不在数据库长事务内。用户停止、编辑草稿不需要回滚整轮；
已提交的记录使用状态迁移处理。结果写入只允许更新仍为 RUNNING 的原回复行，
旧任务迟到的成功、失败或进度不能覆盖取消/被替代记录。同步 ReAct 使用数据库有效历史快照，
不让迟到执行把旧提问写回共享运行时记忆；修改后清理会话运行时缓存，已完成的有效历史仍由 MySQL 提供。
外部模型请求、已发送消息或工具副作用不能通过数据库事务回滚撤销。

## 持久历史与模型记忆（统一调度）

医疗助手现在统一调用 `POST /api/ai/medical/dispatch`，不再由用户选择问答、检索或报告模式。
请求为 `{"message":"检索证据、比较方案并整理报告","chatId":"chat_001","sessionType":"agent"}`。
`sessionType` 仅用于兼容历史会话存储类型（新建统一为 agent），不能强制选择执行流程。
后端结合已完成的会话内容补全后续问题，自动决定使用 ReAct，或提交包含检索、分析、报告等依赖步骤的 Planner 工作流。
急症风险仍在任何分类 LLM 之前检查；报告和复杂目标进入工作流，最终经质量复核交付。

工作流运行中将 `taskId` 和进度元数据写入回复行，页面可通过任务查询恢复进度。
完成后同一条回复保存最终结果、计划及复核意见，不会额外创建一轮重复用户消息。
前端合并展示历史 medical、rag、agent 会话，继续提问时保留原类型；无需迁移旧记录。

MySQL消息表保存用户可见历史，不受Redis TTL或最近200条消息窗口影响。
兼容的旧接口仍使用 Redis/File 运行时上下文。统一调度使用 MySQL 有效历史快照并按最近消息窗口裁剪，
修改时清理原会话运行时缓存；不会把数据库完整历史无界塞入模型 Prompt。
历史Redis/File会话没有自动迁移进新表；从本次改造后的新会话开始记录。
会话接口和身份处理在MySQL模式启用；显式file开发模式保持原来的会话行为。
当前启动恢复逻辑仍面向单Admin调度实例。

## 查看数据库记录

应用连接到 `agent.persistence.mysql` 指定的数据库并启动成功后可执行：

```sql
SELECT id, username, created_at, last_login_at FROM agent_user WHERE username = 'zhangsan';
SELECT id, user_id, chat_id, type, title, message_count, updated_at
FROM agent_chat_session WHERE user_id = 'zhangsan' ORDER BY updated_at DESC;
SELECT m.id, s.chat_id, s.type, m.role, m.content, m.status, m.created_at
FROM agent_chat_message m JOIN agent_chat_session s ON m.session_id = s.id
WHERE s.user_id = 'zhangsan' ORDER BY m.id;
```

初始化只创建真实默认用户，不生成虚假的聊天记录；调用会话/聊天接口后才生成对应会话和消息。
本次测试使用H2 MySQL兼容模式与模拟模型服务；实际MySQL连接需要按本机配置验证。
