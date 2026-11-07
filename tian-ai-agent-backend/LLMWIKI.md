# LLM Wiki 使用与运行说明

本项目保留 RAG 的原文检索能力，在问答之前增加持久化的资料理解层。资料只需要在上传、更新或人工触发时编译；问题到来时，读取已经归纳好的 Wiki，再结合原文证据生成回答。

现在来源页与主题页均由 LLM 生成完整 **Markdown**，包含 YAML frontmatter、标题、正文引用及 `[[页面ID|显示名称]]`。Java 使用 CommonMark AST 解析标题、标签和链接；代码块、行内代码不参与链接和标签提取。Markdown 原文保存在不可变 revision 中，H2 中的标题、标签和出链表是可重建索引。网页直接渲染 Markdown，并对生成的 HTML 进行清理。

例如：

```markdown
---
tags: [随访, 医疗]
---
# 随访管理

## 知识综述
应记录资料明确要求的症状和变化。[1]

## 冲突与适用边界
仅依据当前资料，不能推断未说明的人群适用性。[1]

## 关联知识
[[source-对应ID|来源指南]]
```

双向链接保存的是出链：A 引用 B 后，B 的反向链接从引用表计算，不要求模型再改写 B。草稿图与已发布图分别按各自 revision 计算，发布新版本后旧出链自动退出当前图。标题同名时要求使用稳定页面 ID，避免错误关联。

流程：

```text
上传资料 → MinerU/Tika → Markdown → 分块 / Milvus Dense + BM25
                                  ↓ 同一个文档数据库事务
                         原文版本快照 + 持久化编译任务
                                  ↓ 异步、分批、完整读取
                    LLM 提取知识点 → 校验片段 ID / 原文摘录
                                  ↓
                  entities 实体页 → concepts 概念页 → syntheses 综合分析页
                                  ↓
                             草稿 → 人工审核发布

用户问题 → RAG 召回原文 → 定位相关的已发布 Wiki → 带原文引用的回答
```

## 开始使用

1. 在 `tian-doc-parse-index-mcp-server/src/main/resources/application.yml` 中，直接配置 `doc-mcp.chat.api-key`、`endpoint`、`model`。这里使用文档 MCP 自己的模型配置，不自动读取 admin 的模型密钥，也不从环境变量读取这些设置。
2. 启动 Milvus、文档 MCP 和 admin，并更新前端。登录后进入“知识库”，点击右上角 **LLM Wiki**。
3. 上传已审核的资料。原文索引完成后，Wiki 编译在后台进行；已有资料会在文档 MCP 启动后登记并排队，无需重解析 PDF 或重新生成 Embedding。
4. 查看“编译任务”的进度、状态、错误码、尝试次数和模型报告的输入 / 输出 Token 数。
5. 打开知识页面，核对归纳内容、适用条件和原文引用，可查看当时的完整 Markdown 快照；旧资料补录的快照可能只有完整的原始分块。
6. 点击“审核并发布此版本”。已有 `/knowledge-base/query-with-sources` 和智能体的 `queryApprovedMedicalKnowledge` 自动使用有效 Wiki，不需要切换问答入口。

默认没有配置模型密钥，任务会显示 `BLOCKED / CHAT_NOT_CONFIGURED`，不会把程序模板当成 LLM 的理解结果。配置并重启后，在界面重新提交即可。

## 配置

```yaml
doc-mcp:
  wiki:
    enabled: true
    auto-compile: true
    auto-publish: false
    max-attempts: 3
    batch-chars: 12000
    max-calls-per-job: 80
    max-facts-per-topic: 120
    max-synthesis-chars: 32000
    poll-interval-ms: 3000
    max-output-tokens: 4096
    request-timeout-seconds: 90
    lease-seconds: 300
```

`auto-publish: false` 是默认策略：资料审核与生成内容审核是两件事。需要自动发布时可以显式改为 `true`，但结构校验不能证明模型归纳在语义上完全正确。

`max-attempts` 是**包含首次在内**的最多三次执行，瞬时网络错误、429、5xx、引用或 JSON 校验失败会按退避时间重试；配置缺失阻塞，权限类错误、输出截断和预算超限直接停止。手动重新提交会创建新任务。

编译不会静默截取长文档的前半部分。每批必须报告所有读取的片段 ID，且知识点的摘录必须逐字存在于指定片段。超出调用数、主题知识点数或上下文预算时整次编译失败，不发布不完整结果。Token 数按供应商实际返回值累计；供应商未返回用量或在返回 JSON 前失败的请求可能没有用量记录，因此该数值不应直接视为账单金额。

## 页面、版本与来源

- **实体页 ENTITY / entities**：提取药物、组织、人物、设备等具体对象的身份、属性和事实，可累积多份资料的证据。
- **概念页 CONCEPT / concepts**：解释疾病、机制、方法、原则等概念，并关联同一来源涉及的实体和综合分析。
- **综合页 SYNTHESIS / syntheses**：在实体、概念页面编译后，结合当前来源的知识点与已编译页面的组织结构进行综合归纳、比较和边界分析。
- **编译顺序**：模型提取知识点时必须标明 ENTITY 或 CONCEPT，Java 校验类别并先编译实体、再编译概念、最后编译综合页。页面 frontmatter 的 category 必须与任务类别一致，否则整次编译不提交。没有相关实体或概念时不会为了填目录编造内容。
- **历史版本**：页面每次编译生成新 revision，内容不可变。新草稿不会自动替换仍有效的已发布版本；审核时也可以选择仍有效的历史版本重新发布。
- **来源快照**：保留上传时的 Markdown、分块、元数据和内容版本。资料更新或从检索库删除后，历史来源仍保留用于审计；这里的删除不等于合规意义上的彻底擦除。
- **增量更新**：内容版本由服务端对实际分块、标题、来源和元数据计算，不信任调用者传来的摘要哈希。相同内容重复入库不重复触发自动编译。
- **失效传播**：修改或删除资料时，同一事务标记关联 Wiki 版本为 `STALE`、作废旧任务并排队新任务；相关主题会由仍存在的来源重新编译，旧来源知识点不会混入新版本。

主题合并目前基于模型返回的规范名称进行大小写 / 空白归一，不具备完整医学本体和同义词实体消歧。页面中的“冲突”是 LLM 的归纳提示，确定性 lint 只检查来源、摘录和页面链接，不能替代领域审核。

## 问答与缓存

原文检索接口 `/knowledge-base/retrieve` 继续只召回资料，不调用回答模型。默认问答根据召回文档定位相关已发布页面，最多读取三页、限制 Wiki 上下文，并将页面引用补充为真实原文来源。页面的引用不会伪装成高分检索结果，也不能绕过原有证据不足检查。

响应增加：

```json
{
  "answer_mode": "wiki-enhanced",
  "wiki_pages": [{"page_id": "topic-...", "revision_id": "...", "title": "..."}],
  "sources": [{"index": 1, "chunkId": "...", "documentId": "...", "text": "原文"}]
}
```

没有可用页面时 `answer_mode` 为 `rag`。资料更新、删除、页面发布都会改变 Wiki 版本，admin 在使用精确或语义回答缓存前查询这个版本。文档 MCP 不可用时不会继续把旧缓存当作新知识回答。

## Agent 多跳读取限制

应用 Agent 的 `searchPublishedWikiPages` 和 `readPublishedWikiPage` 通过服务端守卫执行。是否需要读取下一页由 Agent 根据问题和证据缺口判断，程序不会自动遍历链接，也没有新增语义相关性阈值或无收益停止规则。

admin 的 `application.yml` 使用直接配置：

```yaml
agent:
  wiki:
    max-depth: 2
    max-pages: 6
```

搜索结果及知识库问答返回的 Wiki 页面 ID 可以作为深度0的起始页。沿已读页面的有效出链或入链读取时，工具需要传 `fromPageId`；服务端验证实际链接关系并计算深度，最多允许两条链接边。模型不能提交深度值，也不能通过把关联页称作起始页来清零深度。后续搜索不会将已发现页面的深度重置。

每轮执行最多远程读取6个不同的有效发布页面，起始页也计入。已读取页面按稳定页面ID去重，保留读取版本；同一 Agent 重复读取返回 `already_visited`，不重复请求或返回正文。并行子任务、工具线程和 Replan 共享同一根任务预算；不同子 Agent 首次需要同一页时可以收到 `cached` 的已读内容，不再次调用 MCP、不额外计数，避免去重导致独立子任务拿不到证据。新一轮提问重新建立预算，同一个聊天会话不会永久耗尽配额。

`max_depth`、`max_pages`、`invalid_link`、`parent_required`、`not_discovered` 等拒绝原因通过结构化工具结果返回，Agent 应据此汇总现有证据或说明信息不足。达到总页数上限后不再扩展 Wiki 搜索；已读内容仍可由其他子 Agent 从缓存复用。并发读取使用同一状态的原子检查，不能同时越过页数上限。

这里限制的是**应用 Agent 主动调用 Wiki 阅读工具**的路径。现有问答服务的自动 Wiki 注入仍使用其独立的最多3页、14000字符限制，不进行图递归；网页管理和内网原始 MCP 接口保持原有行为。外部 AI 客户端直接调用原始 MCP 时，需要自行接入等价的执行级守卫，不能把管理接口当作已经受此 Agent 预算保护的接口。

## 管理接口

接口均位于 `/api/knowledge-base/wiki`，需要已有登录 Session。审核人来自服务端登录身份，不能通过请求体冒充。当前与原知识库一样是共享知识空间，登录用户都可管理，未新增复杂 RBAC 或租户 ACL。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/status` | 配置、页面数、知识版本、任务状态统计 |
| POST | `/compile` | `{"docId":"资料ID"}`，返回 202 和 `job_id` |
| GET | `/jobs`、`/jobs/{jobId}` | 最近 100 个任务 / 任务详情 |
| GET | `/pages` | 页面目录，当前最多 1000 页 |
| GET | `/pages/{pageId}` | 最近 100 个历史版本，包含内容和引用 |
| GET | `/pages/{pageId}/content?publishedOnly=false` | Markdown、标题、标签、出链与反向链接；管理页面默认可查看草稿 |
| GET | `/pages/{pageId}/markdown?publishedOnly=false` | `text/markdown` 格式原文，可另存为 .md 文件 |
| GET | `/search?query=随访&tag=医疗&publishedOnly=true` | Markdown正文关键词和标签筛选，limit 默认20、最多100；此搜索为关系库文本匹配，不是Milvus BM25 |
| GET | `/revisions/{revisionId}` | 指定版本 |
| GET | `/sources/{docId}/{sourceVersion}` | 不可变来源快照 |
| POST | `/pages/{pageId}/publish` | `{"revisionId":"版本ID"}`，校验来源后发布 |
| POST | `/lint` | 来源时效、摘录完整性、关联页面断链检查 |
| GET | `/events` | 最近 100 条编译、失效、发布操作记录 |

同名 `wiki_*` MCP 工具供 admin 和可信 AI 客户端调用。`wiki_read_note(page_id, published_only=true)` 返回 Markdown 和链接元数据；`wiki_search(query, tag, limit, published_only=true)` 搜索有效发布内容。医疗智能体注册了只读的 `readPublishedWikiPage` / `searchPublishedWikiPages` 包装工具，固定只允许有效发布版本，不能自行审核发布。文档 MCP 沿用现有内网信任边界，部署时应只允许可信后端访问；管理 HTTP 层的登录不能代替内网 MCP 的访问控制。

旧版结构化页面无需删除，读取时转换为 Markdown，并在启动时补建链接与标签索引。重新编译资料后生成由 LLM 撰写的新 Markdown 版本。

编译的 Markdown 自动按类别写入后端项目 `docs/wiki/entities/`、`docs/wiki/concepts/`、`docs/wiki/syntheses/`，历史版本保存在 `docs/wiki/.history/<页面ID>/<版本ID>.md`，页面目录 `index.md` 分类列出页面并标明审核与失效状态，`log.md` 从数据库操作事件按事件 ID 幂等追加，重试不会重复记日志。通过 `doc-mcp.wiki.output-dir: ./docs/wiki` 可修改输出位置；相对路径在开发环境按后端项目根目录解析，容器中按工作目录 `/app` 解析，Compose 已挂载到宿主机 `./docs/wiki`。文件名继续使用稳定页面 ID，双向链接不会因分类目录改变而失效。

旧版 SOURCE 页面兼容映射到 syntheses，TOPIC 页面兼容映射到 concepts。启动补齐时迁移对应的旧平铺页面；重新编译资料后使用模型分类。历史页面 ID 与引用不变，保留全部版本。

数据库 revision 仍为权威存储。编译事务同时保存持久化文件同步任务，事务提交后使用临时文件加原子替换写入 Markdown，写入失败保留队列并每10秒重试。启动时会从数据库补齐已有页面和历史版本，无需重新调用模型。数据库与文件系统不是一个事务；文件同步异常不会将已成功提交的编译结果回滚，`wiki_status.file_exports_pending` 可查看待同步数量。手动修改生成文件会被后续同步覆盖，数据库回滚的编译结果不会生成页面文件。

## 持久化与事务边界

Wiki 位于现有文档 MCP 的文件 H2 数据库，与 `documents/chunks` 使用相同事务管理器。新增表包括 `wiki_sources`、`wiki_source_snapshots`、`wiki_jobs`、`wiki_worker`、`wiki_facts`、`wiki_pages`、`wiki_revisions`、`wiki_refs`、`wiki_events`。**没有将 Wiki 数据落入 admin 的 MySQL，也没有将其放进 RabbitMQ 工具审计队列。** 这样来源失效、快照、任务创建可以与文档记录原子提交；模型网络调用在事务外进行，全部页面校验成功后一次提交。

任务使用数据库租约，崩溃后租约到期可重新执行；每次执行以任务 ID 和尝试次数校验提交资格。失去租约的旧 worker 即使继续返回结果，也不能覆盖新一轮执行。全库一次只编译一个来源，避免两个 worker 同时覆盖同一跨来源主题。

Milvus 与文档数据库仍不构成分布式事务。Wiki 回答只接受当前文档数据库中有效的原文片段，避免外部残留索引成为依据。

这是在当前单实例、共享知识库架构上实现的可运行版本。大规模多租户部署还需要统一关系数据库迁移、租户级 ACL、审核角色分离、队列横向扩展、归档保留策略、同义词 / 本体对齐及独立的 Wiki 问答质量评测；不要直接以现有 RAG 的 MRR/Hit 指标声称 Wiki 的质量提升。

## 验证

新增测试覆盖完整编译、跨资料归纳、引用伪造拒绝、覆盖校验、草稿审核、版本失效、历史原文保留、数据库回滚、三次执行上限、缺配置阻塞、租约恢复与旧 worker 隔离、预算限制、模型 HTTP 合约、审核身份和回答缓存版本切换。测试使用真实 H2 与模拟模型响应，不消耗真实模型 API，也不声称已验证真实模型的语义质量。

设计参考：[Karpathy LLM Wiki 原始模式](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f)。
