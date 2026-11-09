# 医疗 Agent 前端迁移报告

这是前端迁移时的历史记录。现行后端为单进程部署，文档 MCP 地址与开关见 [内嵌文档 MCP](../guides/document-mcp.md)。

> 最后更新：2026-09-08  
> 后端状态：医疗重构完成；职业规划与简历服务已永久删除  
> 前端目标：只保留医疗对话、循证检索、医疗知识库、任务中心和人工审核状态

## 1. 迁移结果

后端已从大学生职业规划场景迁移为“天医——循证医疗信息与安全支持智能体”。职业规划、学业、岗位、求职和简历入口应从前端删除。

医疗系统仅用于健康教育、医疗资料解释、循证检索和安全分诊支持，不能展示为在线诊断、处方服务或医生替代品。

## 2. 已永久删除的旧功能

- `/api/ai/advisor/**`
- `/api/ai/career-agent/**`
- 独立简历服务 `/api/resumes/**`（原 8082 服务已删除）
- Python `parse_resume_document`、`get_resume_parse_result` 工具
- 简历导入、编辑、审核、岗位定制和导出功能
- 旧职业规划会话数据

前端不应兼容、探测或回退到这些地址，也不应继续启动或代理 8082。出现 `/api/resumes` 请求即表示前端仍有旧代码未清理。

## 3. 当前后端接口

| 接口 | 用途 |
|---|---|
| `POST /api/ai/medical/chat` | 医疗信息同步对话 |
| `POST /api/ai/medical/chat/stream` | 医疗信息 SSE 对话 |
| `POST /api/ai/medical/evidence-report` | 结构化证据报告 |
| `POST /api/ai/medical/rag-chat` | 医疗知识库增强问答 |
| `POST /api/ai/medical/tool-chat` | 带联网工具的医疗问答 |
| `POST /api/ai/medical-agent/chat` | 多步医疗 Agent SSE，可调用已审核知识库与联网工具 |
| `POST /api/ai/workflows` | 安全分诊与证据检索工作流 |
| `GET /api/ai/sessions` | 获取会话列表 |
| `GET /api/ai/sessions/{chatId}/messages` | 获取会话消息 |
| `DELETE /api/ai/sessions/{chatId}` | 删除会话 |
| `POST /api/knowledge-base/upload` | 上传并异步解析医疗文档 |
| `GET /api/knowledge-base/files` | 获取文档与索引状态 |
| `DELETE /api/knowledge-base/files/{id}` | 删除原文及索引 |
| `POST /api/knowledge-base/query` | 仅返回回答文本；新前端不建议使用 |
| `POST /api/knowledge-base/query-with-sources` | 带来源知识库问答 |
| `POST /api/knowledge-base/retrieve` | 只召回证据，不生成回答 |
| `GET /api/tasks/{taskId}` | 查询异步任务 |
| `GET /api/tasks` | 按用户或类型查询任务 |
| `DELETE /api/tasks/{taskId}` | 取消任务 |
| `GET /api/health` | 服务健康检查 |

通用请求：

```json
{
  "message": "请解释这份指南中的一般管理原则，并标明证据和不确定性",
  "chatId": "medical_001"
}
```

会话接口仍为 `/api/ai/sessions`，`type` 使用 `medical` 或 `agent`，默认 `medical`。

```ts
interface ConversationSummary {
  id: string
  title: string
}
```

`chatId` 必须匹配 `[A-Za-z0-9_-]{1,128}`。前端可使用 UUID 去掉连字符后的值，或保留 UUID 中的连字符。

### 3.1 POST SSE 接入

两个流式接口都通过 POST JSON 传递健康问题，不能使用只支持 GET 的原生 `EventSource`。建议统一封装：

```ts
export async function streamMedicalChat(
  path: '/api/ai/medical/chat/stream' | '/api/ai/medical-agent/chat',
  body: { message: string; chatId: string },
  onChunk: (text: string) => void,
  signal?: AbortSignal,
) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
  if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() ?? ''
    for (const event of events) {
      const data = event.split('\n')
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart())
        .join('\n')
      if (data && data !== '[DONE]') onChunk(data)
    }
  }
}
```

普通流式对话以 HTTP 流结束作为完成标志；多步 Agent 还可能发送 `[DONE]`。前端需要同时兼容两种完成方式，并使用 `AbortController` 支持停止生成。

## 4. 急症响应

同步、SSE、RAG、工具对话和医疗 Agent 在调用模型前都会进行确定性红旗检查。检测到严重胸痛、呼吸困难、意识障碍、疑似卒中、严重过敏、大量出血、药物过量或自伤风险时，会直接返回急救提示。

前端检测到文本包含“拨打 120”或结构化报告 `riskLevel=EMERGENCY` 时，应使用不可关闭的高优先级红色提示，不得继续展示普通 AI 加载动画，也不能用免责声明遮挡急救行动。

## 5. 结构化证据报告

`POST /api/ai/medical/evidence-report` 返回：

```ts
interface MedicalEvidenceReport {
  title: string
  riskLevel: 'ROUTINE' | 'HIGH' | 'EMERGENCY' | string
  summary: string
  redFlags: string[]
  evidence: string[]
  uncertainties: string[]
  nextSteps: string[]
  requiresProfessionalReview: boolean
}
```

界面应分别展示证据、不确定性、红旗信号和下一步。`requiresProfessionalReview=true` 时显示“需要医生或药师审核”，不能显示“AI 已确认安全”。

`POST /api/ai/medical/rag-chat` 返回可追溯证据对象：

```ts
interface MedicalRagResponse {
  question: string
  answer: string
  plan?: {
    answer_type: 'fact' | 'comparison' | 'procedure' | 'risk' | 'summary' | string
    sections: string[]
    focus: string[]
    conflict_checks?: string[]
  }
  validation?: {
    status: 'passed' | 'revised' | 'needs_review' | 'deterministic_only'
    model_valid?: boolean | null
    unsupported_claims: string[]
    missing_citations: string[]
    conflicts: string[]
    citation_integrity: {
      referenced_citations: number[]
      invalid_citations: number[]
      substantive_sentence_count: number
      cited_sentence_count: number
      citation_coverage: number
    }
  }
  riskLevel?: 'EMERGENCY' | string
  sources: Array<{
    citation: number
    documentId: string
    chunkId?: string
    title?: string
    section?: string
    sectionPath?: string
    pageNumber?: number
    pageStart?: number
    pageEnd?: number
    issuer: string
    version: string
    publishedAt: string
    audience: string
    effectiveStatus: 'ACTIVE'
    reviewStatus: 'APPROVED'
    documentType?: string
    sourceUri?: string
    contentSha256?: string
    score?: number
    quote: string
    truncated: boolean
    contextKind?: 'parent' | 'neighbor_window'
    contextNodeIds?: string[]
    anchorNodeIds?: string[]
  }>
  retrieval?: {
    mode: 'rerank' | 'hybrid_fallback' | 'none' | string
    queries: string[]
    query_profile?: { entities: string[]; sections: string[] }
    dense_candidates: number
    bm25_candidates: number
    reranked_anchors?: number
    evidence_contexts?: number
    rejected_by_threshold: boolean
  }
}
```

`sources=[]` 表示没有足够的已审核证据，或请求被急症分诊提前截断。正文中的 `[1]`、`[2]` 与 `sources[].citation` 对应。

## 6. 医疗知识库上传

`POST /api/knowledge-base/upload` 使用 `multipart/form-data`，除 `file` 外必须提交：

| 字段 | 示例 | 说明 |
|---|---|---|
| `issuer` | `国家药品监督管理局` | 发布机构 |
| `version` | `2026版` | 文档或指南版本 |
| `publishedAt` | `2026-08-01` | 必须为 `YYYY-MM-DD` |
| `audience` | `ADULT` | 适用人群 |
| `documentType` | `DRUG_LABEL` | 指南、标签、研究或教育资料 |
| `effectiveStatus` | `ACTIVE` | 当前仅允许 `ACTIVE` |
| `reviewStatus` | `APPROVED` | 当前仅允许 `APPROVED` |

未审核或非现行文档不能进入当前检索库。文件列表响应会包含 `medicalMetadata`。新前端必须优先使用 `query-with-sources`，不要使用无法展示证据来源的 `/knowledge-base/query`。

建议文档类型枚举：`GUIDELINE`、`DRUG_LABEL`、`SAFETY_NOTICE`、`SYSTEMATIC_REVIEW`、`CLINICAL_STUDY`、`MEDICAL_EDUCATION`。

上传成功返回 HTTP `202 Accepted`：

```ts
interface KnowledgeUploadAccepted {
  id: string
  name: string
  size: number
  uploadTime: string
  status: 'QUEUED'
  taskId: string
}
```

上传后的处理状态由 `GET /api/tasks/{taskId}` 或 `GET /api/knowledge-base/files` 轮询，不要把 HTTP 202 当作索引已经完成。文档状态至少应兼容 `QUEUED`、`UPLOADING`、`PARSING_INDEXING`、`INDEXED`、`FAILED`。

只检索请求：

```json
{
  "question": "该指南对特殊人群有哪些说明？",
  "topK": 8
}
```

`topK` 后端会限制为 1～20，建议界面默认使用 8。

## 7. 医疗工作流

工作流不再返回固定三阶段模板。系统先执行确定性急症检查，再由 LLM Planner 根据目标动态生成 1～5 个步骤；Executor 逐步执行，LLM Reviewer 可要求修订或触发一次重规划。前端应按 `plan.steps` 动态渲染，不要依赖固定角色名称或固定步骤数量。

`review` 响应结构：

```ts
interface ReviewResult {
  passed: boolean
  requiresHumanReview: boolean
  reviewerRole: 'DOCTOR_OR_PHARMACIST' | 'NONE' | string
  message: string
  issues: string[]
  revisionInstruction: string
  replanRequired: boolean
}
```

`WorkflowResult` 还包含动态 `plan`、逐步执行记录 `executions`、整合结果 `executionResult` 和实际规划轮次 `iteration`。处方、药物、剂量、诊断、妊娠、哺乳、儿童、老年和肝肾功能主题会被确定性规则强制要求人工审核。`passed` 表示自动质量审查是否通过，与 `requiresHumanReview` 相互独立。

提交请求与任务状态：

```ts
interface WorkflowRequest {
  objective: string
  chatId: string
  ownerId: string
  reviewEnabled: boolean
}

type TaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

interface TaskRecord {
  id: string
  type: 'AGENT_WORKFLOW' | 'DOCUMENT_INDEX' | 'RAG_EVALUATION'
  status: TaskStatus
  ownerId: string
  progress: number
  stage: string
  input: Record<string, unknown>
  result?: unknown
  error?: string
  events: string[]
  createdAt: string
  startedAt?: string
  finishedAt?: string
}
```

建议任务轮询间隔为 1～2 秒，在终态停止轮询。取消任务调用 `DELETE /api/tasks/{taskId}`。

## 8. 隐私与界面约束

- 输入框附近提示不要提交姓名、身份证号、完整住址、病历号等无关标识。
- 不要在日志、埋点、错误监控和 URL 查询参数中记录完整健康问题。
- 后端 AI 调用日志仅记录开始/完成状态，不记录提示词、回答正文或健康信息。
- SSE 使用 POST 请求体传递医疗问题，禁止把健康信息拼入 URL 查询参数。
- 会话删除应在前端提供明确入口。
- 来源卡片展示发布机构、版本、发布日期、适用人群和有效状态。
- 证据不足时展示“无法根据当前证据回答”，不要自动补全模型常识。
- 来源冲突时并列展示，不要只保留一个版本。

### 8.1 错误状态

- 参数、日期、状态或 `chatId` 不合法时请求会失败；当前后端尚未统一异常响应，可能表现为 `400` 或 `5xx`，前端不能只处理其中一种状态码。
- `429`：知识库上传限流；读取 `Retry-After` 响应头并显示倒计时。
- `5xx`：Java、模型、OSS 或 Python MCP 暂时不可用；不要把技术异常解释为医学结论。
- SSE 非正常中断：保留已生成内容并标记“回答未完成”，不能显示为完整医疗建议。
- `sources=[]`：显示“当前没有足够的已审核医疗证据”，不要隐藏为空白页。

## 9. Vite 与前端代码清理

开发环境只需代理 Java 8123：

```ts
// vite.config.ts
server: {
  proxy: {
    '/api': {
      target: 'http://127.0.0.1:8123',
      changeOrigin: true,
    },
  },
}
```

不要在浏览器中直接调用 Python 8081；Java 后端负责 MCP 协议、元数据校验和统一调用入口。删除所有 `8082`、`/api/resumes`、`ownerId=student_001`、`career-agent`、`advisor` 旧请求和环境变量。

建议在前端源码搜索并逐项清零：

```text
8082
/api/resumes
career-agent
/ai/advisor
student_001
resume
career
职业规划
简历
```

## 10. 前端验收清单

- [ ] 品牌替换为“天医——循证医疗信息与安全支持智能体”
- [ ] 删除职业规划、学业、岗位、求职和简历页面
- [ ] API 从 `/ai/advisor` 和 `/ai/career-agent` 迁移到医疗接口
- [ ] 会话类型改为 `medical/agent`
- [ ] 支持急症高优先级提示
- [ ] 支持结构化证据、不确定性和红旗展示
- [ ] 高风险结论显示医生或药师审核状态
- [ ] 知识库上传表单包含完整医疗元数据
- [ ] 来源卡片显示机构、版本、日期、人群和状态
- [ ] 健康信息不进入 URL、埋点和普通错误日志
- [ ] 所有页面持续显示“不能替代医生、药师或急救服务”边界
- [ ] Vite 不再代理或访问 8082
- [ ] POST SSE 使用 `fetch + ReadableStream`，支持主动中止
- [ ] 文档上传收到 202 后继续轮询任务状态
- [ ] 400、429、5xx、流中断和空证据均有独立界面状态

## 11. 服务配置

- Java 主应用：`http://127.0.0.1:8123/api`
- Python 文档 MCP：`http://127.0.0.1:8081/mcp`，仅后端调用
- 文档传输：默认 `KB_STORAGE_MODE=oss`。Java 上传 OSS 并校验对象存在后，将预签名 URL 交给 Python 下载、校验 SHA-256 并解析；`local` 仅用于显式本地调试
- `DASHSCOPE_API_KEY`：模型调用
- `SEARCH_API_KEY`：实时医疗搜索
- `AGENT_MEMORY_BACKEND=redis`：多实例会话记忆

医疗应用不再依赖 `8082` 简历服务。
