# Agent 实时对话

当前统一医疗助手页面使用 `POST /api/ai/medical/dispatch/stream`，以 Fetch POST 读取 SSE，携带现有登录 Cookie。旧 `/medical/dispatch` 和旧分步 SSE 接口保留兼容，主页面不再通过同步请求或轮询等待正常回答。

## 执行链路

```text
HTTP 校验身份、创建/修订对话回合
  → AsyncTaskCenter：CompletableFuture.runAsync(..., aiTaskExecutor)
  → 安全分诊、意图识别
  → ReAct 或 Planner → Executor → Reviewer / Replan
  → answer_start：折叠分析与执行过程
  → 模型 .stream().content()：逐段发送 answer_delta
  → 保存完整回答和任务状态 → done
```

工具选择、执行与工作流复核仍形成内部工作结果，随后由独立的最终回答调用基于这些结果生成可读答复。这会增加一次最终答复模型调用；最终回答确实来自模型流，而不是将已生成的完整文本分段重放。工作流 Reviewer 审查的是内部草稿，最终流式组织不应被表述为逐 Token 临床审核；原有专业人员复核提示继续保留。

公开“分析与执行过程”是程序产生的阶段摘要、工具名称、成功/失败和耗时，不包含隐式思维链、原始工具参数或原始工具结果。工作流中间步骤的内部输出不会放进新流接口的完成事件与结果对象。

## SSE 事件

每条数据为 JSON，包含 `type`、单调递增的 `seq`、时间，以及对应事件字段：

| 事件 | 内容 |
|---|---|
| `task` | 持久化任务 ID，便于中断和恢复 |
| `progress` | 分析、检索、规划、复核等阶段摘要；任务进度可含 progress / stage |
| `tool_start` / `tool_end` | 工具名称、状态、执行耗时；相同 id 更新同一条执行记录 |
| `route` | REACT / WORKFLOW |
| `review` | 工作流计划与复核摘要 |
| `answer_start` | 执行阶段结束，网页自动折叠过程面板 |
| `answer_delta` | 本次模型输出增量，追加至最终回答 |
| `done` | COMPLETED / FAILED / CANCELLED、完整或部分回答、公开执行记录及可选工作流摘要 |

服务每5秒发送 SSE 注释心跳；响应设置 `Cache-Control: no-cache` 与 `X-Accel-Buffering: no`。前端支持跨网络包、UTF-8 多字节、CRLF 和心跳，重复序号不会再次追加正文。Markdown 使用现有 DOMPurify 清理后渲染。

## 中断、持久化与恢复

- 新增任务类型 `AGENT_RESPONSE`，包括简单 ReAct 和复杂工作流的统一后台任务。
- CompletableFuture 本身的 `cancel(true)` 不保证中断计算。本实现另行跟踪运行线程，取消时设置持久化状态并中断线程；等待中的任务检查取消状态，不会执行实际工作。
- 流中答案约每300毫秒保存一次，过程摘要同步保存，完成时一次保存最终全文。取消或失败会保留已经保存的部分回答；数据库仅允许 RUNNING 消息更新，旧任务迟到的数据不能覆盖已修改的提问。
- SSE 网络断开时后台任务继续运行；已有 taskId 的页面会回退查询任务状态，刷新页面也可从持久化记录恢复。恢复使用任务快照，暂不实现事件重放。
- 不会为整轮模型调用保持数据库长事务，也不会因用户中断而回滚已经发生的外部工具操作。
- 前端新回合 ID 使用不超过32字符的标识，与现有 MySQL `turn_id` 字段一致。

## 启用与验证

重新打包并重启 admin，更新前端后生效；文档 MCP 与 Wiki 配置无需改变。

验证覆盖：增量在模型完成前送达、SSE 帧顺序和关闭、工作流复核后再生成、急症分诊直接返回、CompletableFuture 运行中与排队取消、部分回答保存、旧回合写入隔离、前端逐段追加与自动折叠阶段、中断后修改提问和任务恢复。模型与工具测试使用模拟响应，不消耗真实模型 API。
