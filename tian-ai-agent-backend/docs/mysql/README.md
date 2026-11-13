# MySQL 部署配置

Spring Boot 在 Windows 本机运行，MySQL 由 [Docker Compose](../docker/compose.yml) 管理。后端默认使用 `root` 连接 `127.0.0.1:13306` 上的 `tian_ai_agent`。在后端根目录 `.env` 设置 `MYSQL_PASSWORD`，再执行 `docker compose --env-file .env -f docs/docker/compose.yml up -d --wait mysql`。可通过 `MYSQL_PORT` 和 `MYSQL_DATABASE` 覆盖默认值。MySQL 数据保存在 `mysql_workspace_data` 卷中。

## 启用工作台 MySQL 持久化

[`tian_ai_agent.sql`](tian_ai_agent.sql) 是完整的 MySQL 初始化脚本，包含会话、消息、个人模型配置、运行审计、用户画像和旧版摘要兼容表。可在选中目标库后手动执行整份脚本。

容器首次初始化数据卷时会创建目标库并设置 `root` 密码。已有数据卷的密码需在 MySQL 内修改，再同步 `.env`。后端启动时会执行建表脚本，并尝试导入可读取的旧会话历史。MySQL 连接或鉴权失败时，启动会报错。IDEA 中的 `local` 活动配置额外启用长期记忆，默认写入 SQLite。

后端在宿主机运行时，`MYSQL_ROOT_HOST` 使用 Docker 网关的具体 IP。可以用 `docker network inspect tian-ai-agent_default` 查看网关。已有数据卷需在 MySQL 中为该来源配置 `root` 登录权限。

迁移已有实例时，停止向源库写入，用 `mysqldump` 导出 `tian_ai_agent`，恢复到容器中的空库，核对表和行数后再启动后端。迁移时保留后端数据目录中的 `model-settings/storage.key`，以继续读取已加密的配置。

工作台的 MySQL 连接独立于文档知识库的 `spring.datasource`。

## 会话和消息历史

将本地配置中的 JDBC URL 指向实际数据库。初始化脚本可重复执行。

- `agent_conversation` 保存会话标题、所有者、会话类型、检索范围和更新时间。`memory_space` 对应当前的 `medical-agent`、`medical-advisor` 和 `medical-rag`。`storage_id` 沿用 `ConversationScope` 生成的带作用域会话 ID。`scope_id` 存知识库 ID，无知识库的工作台会话用 `general`，旧接口的无空间会话用空字符串。
- `agent_conversation_message` 按自增 ID 保留会话内的消息顺序。`role` 和 `content` 保存消息角色和正文，`tool_calls` 和 `tool_responses` 保留工具调用 ID、参数和返回值，便于还原 ReAct 上下文。删除会话行会级联删除该会话的消息。
- 后端用访问令牌和已校验的会话范围生成存储 ID。首条用户消息会创建会话，ReAct 用户消息先于模型生成提交，工具记录和回答随执行追加。每次追加与会话时间更新处于同一事务。历史接口读取完整历史，模型上下文按 `agent.memory.max-messages` 读取最近消息。

开启 `import-legacy-history` 时，`agent.memory.backend` 指定旧历史来自 Redis 还是本地文件。后端启动会导入仍可读取的旧会话和消息，已入库的会话会跳过。切换存储时请让新后端连接原 Redis 或原文件数据目录。删除会话会清理旧存储中的对应历史，并由 MySQL 外键清理消息。只有 MySQL 数据的新环境可关闭 `import-legacy-history`。

## 用户模型配置

`agent_model_settings` 按访问令牌解析出的 `owner_id` 记录配置归属。`agent_model_provider` 保存提供商标识、协议、Base URL、模型名、启用状态、优先级和同优先级的配置顺序。API Key 以 AES-GCM 密文写入 `api_key_ciphertext`。读取页面时仅返回密钥是否已设置。

首次启用时，已有的 `model-settings/providers.enc` 会按账号导入。MySQL 会保留清空过配置的账号记录，避免重启后再次导入旧配置。迁移或备份时，一并保留数据目录下的 `model-settings/storage.key`。运行中的路由仍在本实例维护，因此这套配置适用于单实例部署。

## 个人 MCP 服务

`agent_mcp_server` 按账号保存远程 Streamable HTTP 端点、启用状态和获准调用的工具定义。可选的 Bearer Token 使用存储密钥加密，页面只显示是否已保存。

先将可信 MCP 服务的来源加入 `agent.mcp.allowed-origins` 并重启后端。例如来源填写 `https://tools.example.com`，页面端点填写 `https://tools.example.com/mcp`。在「MCP 设置」导入服务，保存时会发现并注册工具。也可先预览并勾选所需工具。启用的工具会加入该账号的通用 Agent 问答和工作流，并通过工具网关审计。删除配置会撤销对应的工具。

## 运行审计

`agent_run` 保存账号、运行状态和汇总用量，`agent_request_log` 按运行 ID 和事件序号保存阶段、工具和模型调用记录。通过 `kind = 'MODEL'` 可筛选模型调用，包括厂商、模型、耗时、Token 和失败原因码。一次运行结束后，摘要和事件在同一事务内写入。

旧库中存在 `agent_run_event` 且新表尚未创建时，启动脚本会原地改名。启用 MySQL 后，启动时也会按运行 ID 导入数据目录下 `agent-runs` 中可读取的旧 JSON，已入库的运行会跳过。运行日志页面通过原有接口按账号查询。

## 用户画像与跨会话记忆

`agent_account_profile` 按账号保存已明确表达的非敏感背景和偏好。画像由完成的问答异步提取，删除单个会话会保留账号画像。

设置 `agent.memory.long-term.enabled: true` 后，压缩摘要写入 `doc-mcp.milvus.memory-collection-name` 指定的集合。召回按账号、会话类型和知识库范围过滤。文档索引可以继续使用 SQLite，本功能使用独立的 Milvus 记忆集合。需要配置 Milvus 的 URI、数据库及适用的凭证。

`agent_long_term_memory` 用于读取同一 MySQL 库中的旧摘要。旧会话继续对话并压缩时，可将旧摘要合并进 Milvus；新摘要由 Milvus 保存和召回。
