# Tian 知识工作台 Promptfoo 评测

评测请求直接发送给 Spring Boot 后端，沿用工作台的鉴权和模型配置。每次调用生成独立的 `pf-<UUID>` 会话 ID，避免会话历史影响不同用例。断言在本地执行，无需为 Promptfoo 单独配置裁判模型。

| 配置 | 用例 | 检查内容 |
| --- | --- | --- |
| `promptfooconfig.yaml` | [普通对话 3 条](tests/chat.yaml) | 精确输出、中文事实问答、JSON 结构和值 |
| `promptfoo.agent.yaml` | [Agent 4 条](tests/agent.yaml) | 通用工具和个人 MCP 清单、编程问答、双意图工作流 |
| `promptfoo.rag.yaml` | [RAG 3 条](tests/rag.json) | 单文档精确查询、同义改写、跨文档回答及对应证据 |

Agent 分别统计意图集合精确匹配和工作流路由正确率。AGENT-04 会实际提交异步任务，检查返回的任务 ID。可通过 `GET /api/tasks/{id}` 查看任务最终状态。

## PowerShell 快速开始

1. 按[后端 README](../tian-ai-agent-backend/README.md)启动服务，并为评测账号配置可用的聊天模型。使用已配置的普通账号令牌或管理员令牌。
2. 在评测目录打开 PowerShell。以下命令通过 npm 执行 Promptfoo。使用当前版本时，Node.js 需为 22.22.0 或以上。已有可用的 `promptfoo` CLI 时，可直接使用它。

```powershell
Set-Location 'E:\Code\Projects\Tian-AI-Agent-main\tian-ai-agent-eval'
$env:TIAN_EVAL_BASE_URL = 'http://127.0.0.1:8123/api'
Invoke-RestMethod "$env:TIAN_EVAL_BASE_URL/health"
$env:TIAN_EVAL_TOKEN = Read-Host '工作台访问令牌' -MaskInput

npx.cmd --yes promptfoo eval --no-cache --max-concurrency 1
npx.cmd --yes promptfoo eval -c promptfoo.agent.yaml --no-cache --max-concurrency 1
npx.cmd --yes promptfoo view
```

`TIAN_EVAL_BASE_URL` 默认使用上面的本机地址。远程部署时填写包含 `/api` 的地址。`TIAN_EVAL_TOKEN` 填工作台令牌本身，Provider 会添加 `Bearer` 前缀。执行真实评测会产生对应账号的会话和运行记录。

## 知识库用例

1. 在工作台创建一个通用知识库空间，上传 [归档手册](fixtures/tian-eval-fixture.txt) 和 [故障登记手册](fixtures/tian-eval-incident.txt)，等待解析与索引完成。保持文件名不变。该空间需要可用的向量模型和索引服务。
2. 从空间列表取得评测空间的 ID。可在已设置环境变量的终端运行以下命令查看空间列表。

```powershell
Invoke-RestMethod "$env:TIAN_EVAL_BASE_URL/knowledge-base/spaces" -Headers @{ Authorization = "Bearer $env:TIAN_EVAL_TOKEN" }
$env:TIAN_EVAL_SPACE_ID = Read-Host '评测空间 ID'
npx.cmd --yes promptfoo eval -c promptfoo.rag.yaml --no-cache --max-concurrency 1
```

同一批 RAG 问题可以测检索。将 `TIAN_EVAL_TOKEN` 设为管理员令牌，在评测目录执行下列命令。脚本会根据预期文件名解析出真实文档 ID，再向后端提交评测任务。任务完成后，查看 `result.stageMetrics` 中各检索模式的 `recallAtK`、`hitRateAtK` 和 `mrrAtK`。RAG-03 标注了两份相关文档，因此只召回一份时该题的 Recall@K 为 0.5。

```powershell
$task = .\run-retrieval.ps1 -TopK 5
# 任务完成后执行以下三行
$report = Invoke-RestMethod "$env:TIAN_EVAL_BASE_URL/tasks/$($task.id)" -Headers @{ Authorization = "Bearer $env:TIAN_EVAL_TOKEN" }
$report.status
$report.result.stageMetrics | ConvertTo-Json -Depth 5
```

如需导出某次运行，在对应 `eval` 命令后追加 `-o results.json`。新增用例时，编辑表中对应的测试数据文件。`--no-cache` 每次重新请求后端。RAG 自身的语义缓存由后端配置控制。

HTTP 401 或 403 时检查工作台令牌和账号权限。连接失败时检查基础地址和健康接口。RAG 返回无来源时检查空间 ID、文档索引状态和向量模型。Agent SSE 报错时可用适配器输出的 `runId` 对照运行记录。

[Promptfoo 自定义 Provider 文档](https://www.promptfoo.dev/docs/providers/custom-api/) · [断言文档](https://www.promptfoo.dev/docs/configuration/expected-outputs/) · [Node.js 版本要求](https://www.promptfoo.dev/docs/usage/node-package/)
