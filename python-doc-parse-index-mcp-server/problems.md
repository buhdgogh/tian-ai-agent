# MCP Server / RAG 开发问题与解决记录

本文记录当前知识库 MCP Server、RAG 检索问答及评测体系开发中实际遇到的问题、原因、解决方案和验证方法。文中不记录真实 API Key、Workspace ID 等敏感信息。

## 一、问题概览

| 编号 | 问题 | 当前状态 |
| --- | --- | --- |
| 1 | Ragas 依赖已安装但脚本仍提示缺失 | 已解决 |
| 2 | 不清楚评测数据实际来自哪些文件和存储 | 已厘清 |
| 3 | Ragas 候选问题少于请求数量，证据存在重复 | 已增加人工审核流程 |
| 4 | 原评测适配器未连接真实 RAG，无法统一比较检索方案 | 已解决 |
| 5 | DashScope reranker 出现 SSL EOF | 端点配置已修正，保留降级机制 |
| 6 | 回答校验模型输出非法 JSON | 已做安全降级，仍可增强重试与结构化输出 |
| 7 | 通用问题被错误理解为指定药品问题 | 已解决 |
| 8 | 简单问题的完整问答链路耗时过长 | 已完成自适应快速路径 |
| 9 | Redis 向量索引与本地文档存储可能不一致 | 已增加事务、锁和补偿机制 |
| 10 | 中文 BM25 分词及无效节点影响检索质量 | 已解决 |
| 11 | 示例环境文件可能泄露真实凭证 | 已清理，真实凭证仍应轮换 |
| 12 | Ragas 默认切分与生产 RAG 的层级切分不一致，金标 chunk ID 失真 | 已改为使用生产 DocStore 节点 |
| 13 | 单个叶子 chunk 信息不足，生成的问题和参考答案过窄或不完整 | 已改为受控证据包 |
| 14 | 自动映射出的候选 evidence 被误当作正式 gold evidence | 已明确最小充分证据人工审核门禁 |
| 15 | 把 Ragas 合成质量误认为生产 RAG 检索质量 | 已拆分数据构造、检索评测和回答评测 |
| 16 | 长文档垄断 Ragas 候选问题 | 已改为按文档 round-robin 采样 |
| 17 | 单跳候选仍执行全量图 transforms，生成过慢 | 已增加轻量单跳模式和输入上限 |
| 18 | 英文输出、残缺表格和数值单位错误 | 已增加生成前过滤、提示约束和风险标记 |
| 19 | 已安装 Ragas 仍提示缺少 `ragas.testset.transforms` | 已修正 0.4.x 导入路径并改为按需导入 |
| 20 | BEIR 使用叶子 qrels，但检索提前返回父节点导致正确证据被按 ID 误判 | 已统一为叶子召回/融合/重排，评分后再扩展父节点 |

## 二、评测环境与依赖

### 1. `pip install -e ".[eval]"` 的作用

该命令包含两部分：

- `-e`：以 editable 模式安装当前项目。代码修改后通常不需要重新安装。
- `.[eval]`：安装当前项目以及 `pyproject.toml` 中 `eval` 可选依赖组，例如 Ragas、LangChain、OpenAI SDK 和 pandas。

在 Conda 环境中应优先使用当前解释器执行 pip，避免把包安装到其他 Python：

```powershell
python -m pip install -U --upgrade-strategy eager -e ".[eval]"
python -m pip check
```

### 2. 已安装依赖但仍提示 `missing evaluation dependencies`

现象：

```text
missing evaluation dependencies; run: pip install -e ".[eval]"
```

实际原因不是完全没有安装 Ragas，而是当时安装的 `ragas 0.4.3` 与 `langchain-community 0.4.2`、LangChain 1.x 组合不兼容，导入时缺少 `langchain_community.chat_models.vertexai`。旧脚本捕获了所有导入异常，只显示统一的“缺少依赖”，掩盖了真正错误。

解决方案：

- 在 `pyproject.toml` 的 `eval` 依赖组中约束兼容版本：
  - `langchain>=0.3,<1`
  - `langchain-community>=0.3,<0.4`
  - `langchain-core>=0.3,<1`
  - `langchain-openai>=0.3,<1`
- 使用 `python -m pip` 保证安装到正在运行脚本的同一个环境。
- 修改 `generate_questions.py` 和 `run_eval.py`，在导入失败时显示具体模块异常和修复命令。

验证：重新生成候选问题时，Ragas 的 extractor、scenario 和 sample 流程可以正常完成。

注意：`pip check` 可能还会显示 autogen、protobuf、onnx、opencv 等原环境遗留冲突。这些冲突应单独处理，但并不是本次 Ragas 导入失败的直接原因。

## 三、Ragas 评测数据生成

### 3. 评测数据来源不能只看原始 Markdown

第一版候选生成读取 `mcp_server/parsed/{doc_id}.md` 全文，让 Ragas 自己切分；随后再把
`reference_contexts` 模糊匹配到 SQLite chunk。这种设计可以生成问题，却不能保证评测
证据与生产检索的真实粒度一致。

当前版本以 `mcp_server/storage/knowledge.db` 为准：

1. `state` 中持久化的 LlamaIndex DocStore 提供真实父子关系。
2. `chunks` 表提供节点正文、`doc_id`、章节、字符位置和稳定 `node_id`。
3. 叶子节点是 Redis Dense 检索的实际候选粒度。
4. 叶子节点的直接父节点对应回答阶段可能扩展使用的较大上下文。
5. `mcp_server/parsed` 中的 Markdown 仍是原始内容和人工核查依据，但不再由新版候选脚本
   重新交给 Ragas 切分。

Redis 不参与候选问题生成，但参与 Dense、Hybrid、Reranker 和端到端评测。这样避免用
“Ragas 自己构造的 chunk”代替“系统实际检索的 chunk”。

### 4. Ragas 提供的是生成与评测框架，不是生产检索器

`generate_with_chunks()` 是 Ragas 0.4.3 中 `TestsetGenerator` 的方法。它接收已经切好的
字符串或 LangChain `Document`，将其直接作为 `NodeType.CHUNK`，执行适用于预切分数据的
摘要、过滤、主题、NER、Embedding 和关系构建，然后生成 persona、scenario、问题、
参考答案和参考上下文。它不会调用本项目的 Redis、BM25、AutoMergingRetriever 或
Qwen reranker，也不会再次使用 Ragas 的文档 splitter。

默认返回 `Testset`；转换为 DataFrame 后主要包含：

- `user_input`：生成的问题；
- `reference`：生成的参考答案；
- `reference_contexts`：生成时选中的上下文；
- `synthesizer_name`：所用 synthesizer。

`evidence_chunk_ids`、审核状态、BEIR qrels 都是项目层补充的，不是 Ragas 自动生成的正式
金标。请求 50 条却只得到 29 条也不必然代表程序失败；过滤、场景分配或单条生成失败都
可能使实际样本数少于目标数。

主要输出：

- `evals/datasets/candidates.jsonl`：机器生成的完整候选记录，包括问题、参考答案、上下文、证据 chunk ID、匹配分数和审核状态。
- `evals/datasets/candidate_review.csv`：适合人工打开和填写的审核表。

#### 从候选生成到正式评测的完整流程

| 阶段 | 命令或人工操作 | 输入 | 主要处理 | 输出 | 是否属于正式评判 |
| --- | --- | --- | --- | --- | --- |
| 1. 构造候选题 | `python evals/generate_questions.py --size 30` | SQLite/DocStore 中的生产父子节点与叶子 chunk | 构造受控证据包；按 `doc_id` 分组并 round-robin 采样；交给 Ragas 生成问题、reference 和生成上下文；精确回连生产叶子 ID；添加质量风险提示 | `candidates.jsonl`、`candidate_review.csv` | 否。这里只是在准备待审核试题，没有运行生产检索，也不能把 Ragas 输出直接当作 gold |
| 2. 人工审核问题 | 编辑 `candidate_review.csv` | `user_input`、`reference`、`evidence_preview`、原始说明书 | 判断问题是否自然、无歧义且可由证据完全回答；检查 reference 是否覆盖问题所有子句；允许基于原文修改问题和 reference | 更新后的问题、reference、`question_verified` | 是金标制作环节，但不是在给生产 RAG 打分 |
| 3. 人工审核证据 | 编辑 `candidate_review.csv` | 候选 `evidence_chunk_ids`、SQLite chunk、解析后 Markdown | 删除无关药品、剂型或章节的 chunk；补充遗漏证据；保留能够完整回答问题的最小充分叶子证据集 | 更新后的 `evidence_chunk_ids`、`evidence_verified` | 是金标制作环节，但不能根据某一路检索结果反向修改证据 |
| 4. 作出审核决定 | 编辑 `candidate_review.csv` | 已核验的问题、reference 和证据 | 接受时填写 `review_status=approved` 且两个 verified 均为 `true`；不合格设为 `rejected`；未完成保留 `pending` | 完成人工审核的 CSV | 只有三项门禁全部通过的记录才有资格进入正式数据集 |
| 5. 校验并冻结金标 | `python evals/review_questions.py` | 人工编辑后的 `candidate_review.csv`、当前生产叶子 corpus | 只读取 approved 行；确定性检查双 verified、非空问题/reference、合法且非空的 chunk ID、ID 是否存在、问题 ID 是否重复 | `evals/datasets/gold.jsonl` | 是。该脚本负责校验人工决定，不会调用 LLM 自动判定语义正确性 |
| 6. 导出 BEIR | `python evals/export_beir.py` | `gold.jsonl`、生产叶子 corpus | 将问题写入 queries，将叶子 chunk 写入 corpus，将“问题 → 正确证据 ID”写入 qrels | `corpus.jsonl`、`queries.jsonl`、`qrels/test.tsv`、`manifest.json` | 是，形成统一检索评测输入 |
| 7. 检索评测 | `python evals/run_retrieval_eval.py --top-k 10` | 同一份 BEIR queries、corpus 和 qrels | 分别运行 BM25、Dense、Hybrid、Reranker，比较实际返回 ID 与人工 gold ID | Recall@K、MRR、MAP、nDCG、延迟和错误记录 | 是，回答“证据是否召回、排序是否合理” |
| 8. 端到端评测 | `python evals/run_eval.py --top-k 5` | gold 问题/reference/evidence 与真实 RAG 输出 | 调用生产检索、重排和回答链路，再用人工金标及 Ragas judge 辅助评价上下文和答案 | Context Precision/Recall、Faithfulness、Answer Accuracy、ARES TSV 等 | 是，回答“召回之后是否生成了正确、完整且忠于证据的答案” |

`candidate_review.csv` 中三个人工审核字段的职责如下：

| 字段 | 设为 `true` 或 `approved` 的条件 | 不通过时如何处理 |
| --- | --- | --- |
| `question_verified` | 问题清晰、独立、没有错误指代；reference 完整回答了问题全部子句，药品、剂型、给药途径、数字和单位均与原文一致 | 修改 `user_input`/`reference` 后重新核验，或将该题设为 `rejected` |
| `evidence_verified` | `evidence_chunk_ids` 都是真实生产叶子 ID，且共同构成最小充分证据集；没有无关药品/剂型，也没有遗漏回答某个子问题所需的 chunk | 删除无关 ID、补充遗漏 ID并重新核对；不能因为系统没有召回某个必要证据就删除它 |
| `review_status` | 问题/reference 和 evidence 都已经完成审核时才设为 `approved` | 明确不合格设为 `rejected` 并填写原因；尚未核完保留 `pending` |

门禁关系为：

```text
review_status=approved
AND question_verified=true
AND evidence_verified=true
→ 才写入 gold.jsonl
```

`review_questions.py` 的“评判”是结构和状态校验，不是语义审核器。问题是否合理、reference
是否正确、证据是否充分仍由人工负责；正式系统能力是在金标冻结之后，由检索评测和端到端
评测衡量。这样可以避免把“把标准答案修正确了”误认为“RAG 系统已经答对了”。

### 5. 从全文模糊匹配到受控证据包

旧设计为：

```text
完整 Markdown → Ragas 自行切分 → question/reference/context
→ 与全库父子 chunk 做字符重叠匹配 → evidence_chunk_ids
```

这会产生三个典型错误：Ragas 与生产系统切分边界不同；大父节点更容易覆盖生成上下文；
同名药、相似剂型或重复说明书片段可能被误匹配。由此得到的高 Recall 甚至 100% Recall
可能只是宽松 gold evidence 造成的指标虚高，而不是系统检索能力强。

仅改成一个叶子节点出题也不理想：生产叶子节点中位文本较短，成份、适应症、禁忌等
信息经常分布在相邻节点，模型容易生成过窄问题，或者生成复合问题但 reference 只覆盖
一部分。

当前设计为：

```text
生产 DocStore
→ 按直接父节点组织多个真实叶子节点
→ 建立 bundle_id → [leaf_chunk_id...] 映射
→ 用父节点文本作为受控生成上下文
→ Ragas generate_with_chunks()
→ reference_contexts 精确回连 bundle
→ 展开为候选叶子 evidence
→ 人工裁剪为最小充分证据集
```

当前数据库可形成 1165 个受控证据包，其中 1031 个包含多个叶子节点；每包叶子数中位数
为 5，正文长度中位数约 873 字符。它与生产链路“小 chunk 检索、父上下文回答”一致，
又能保存叶子级检索金标。

受控证据包只能保证来源关系可追踪，不能保证包内每个叶子都与最终问题有关。因此仍保留
“Ragas 生成候选 → 人工审核问题、答案和证据”的门禁：

- 检查问题是否自然、明确且能够由知识库回答。
- 检查参考答案是否完全受证据支持。
- 从候选包中删除没有直接支撑问题或 reference 的叶子 chunk。
- 必要时修改问题、答案和证据 ID。
- 仅当 `review_status=approved`、`question_verified=true`、`evidence_verified=true` 时才允许导出 BEIR。

因此，候选集是待审核素材，不是可直接用于正式报告的测试集。

## 四、统一检索与端到端评测

### 6. 原评测代码没有连接真实 RAG 服务

现象：原 `evals/rag_adapter.py` 调用了不存在的 `your_rag_service`，检索评测也只覆盖部分方案，无法公平比较生产链路。

解决方案：

- `rag_adapter.py` 改为调用真实的 `query_knowledge`。
- 建立 BM25、向量、混合检索和 reranker 的统一适配器。
- 导出标准 BEIR 目录：`corpus.jsonl`、`queries.jsonl` 和 `qrels/test.tsv`。
- 统一输出 Recall、MRR、MAP、nDCG 和延迟指标。
- 使用 Ragas 评估端到端回答，并导出供 ARES 使用的 TSV 数据。
- 增加审核指南，明确哪些候选问题可以进入正式评测。

需要注意：如果远程 reranker 调用失败并退化为原始混合/向量排序，该次结果不能当作真实 reranker 效果。正式报告应检查 `retrieval_mode`，并标记或排除 `hybrid_fallback` 结果。

## 五、DashScope reranker 连接问题

### 7. Reranker 端点配置错误导致 SSL EOF

日志表现：

```text
qwen3.7-text-rerank 调用失败: SSL: UNEXPECTED_EOF_WHILE_READING
降级使用原始向量分数进行排序
```

原配置把 `DASHSCOPE_RERANK_ENDPOINT` 指向了 OpenAI-compatible 基础路径：

```text
https://{workspace}.cn-beijing.aliyuncs.com/compatible-api/v1
```

但当前代码发送的是 DashScope text-rerank 请求格式，需要的是 rerank 服务完整路径，主机名还应包含 `.maas`：

```text
https://{workspace}.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
```

推荐解决方案是设置有效的 `DASHSCOPE_WORKSPACE_ID`，并把 `DASHSCOPE_RERANK_ENDPOINT` 留空，让程序自动构造正确端点：

```dotenv
DASHSCOPE_WORKSPACE_ID=<your-workspace-id>
DASHSCOPE_RERANK_ENDPOINT=
```

诊断中发现 VPN 使用了 `198.18.x.x` Fake-IP DNS，但正确的 `.maas.aliyuncs.com` 地址能够成功完成 TLS 1.3 握手。因此，本次故障的首要原因是端点格式错误，而不是 VPN 阻断。VPN、代理或企业网络仍可能造成偶发 TLS 中断，所以代码继续保留异常捕获和无 rerank 降级。

修改 `.env` 后需要重启 MCP Server，不需要重新建立知识库索引。

## 六、回答生成质量问题

### 8. 通用问题被错误回答成特定药品问题

用户问题是：

```text
总结药品说明书中的禁忌、不良反应和就医提示
```

旧系统却围绕“安尔眠胶囊”和“安乐胶囊”回答。原因链路为：

1. 问题没有指定药品，本应先澄清，但旧逻辑仍进入检索。
2. 旧药品实体正则包含过宽的裸后缀“药”，可能把“总结药”等普通文本误判为药名。
3. 通用章节词检索命中了包含多种药品的汇总文档。
4. 回答规划模型从证据标题中引入了用户没有提及的药品名。
5. 最终回答沿用了被污染的规划焦点。

解决方案：

- 使用基于片剂、胶囊、颗粒、注射液等剂型后缀的非贪婪实体规则，移除裸后缀“药”。
- 清理实体前的“请问、总结、介绍”等噪声词。
- 支持识别“阿司匹林说明书”这类不带剂型的明确表达。
- 对未指定具体药品、批准文号或明确范围的说明书章节问题直接返回澄清请求，不再调用 Redis 和远程模型。
- 丢弃在查询改写过程中凭空引入新药品实体的改写结果。
- 如果回答规划引入了用户问题中不存在的药品实体，拒绝该规划并回退到确定性规划。

修复后的预期行为是提示用户补充具体药品名称、剂型或批准文号，而不是任选检索结果中的药品作答。

### 9. 回答校验模型返回非法 JSON

日志表现：

```text
回答校验模型不可用，保留确定性引用校验结果:
Unterminated string ...
```

原因通常是模型输出被截断、字符串转义不完整或未严格遵守 JSON 格式。长上下文和过大的输出上限会增加这种概率。

当前处理：

- 捕获校验模型解析异常，避免整个回答请求失败。
- 保留确定性的引用编号和引用完整性检查。
- 将校验状态标为 `deterministic_only`，明确语义校验没有完整执行。
- 缩短上下文和输出规模，并只对高风险医疗问题调用模型校验。

该问题目前属于“安全降级后可用”，后续仍可增加 JSON Schema/结构化输出、JSON 修复和一次小上下文重试。不能把 `deterministic_only` 等同于完整的事实一致性校验。

## 七、性能问题

### 10. 简单提问响应过慢

旧链路可能串行执行：

```text
查询改写 LLM
→ 查询向量化与 Redis/BM25 检索
→ 远程 reranker
→ 回答规划 LLM
→ 回答生成 LLM
→ 回答校验 LLM
```

一次简单提问最多触发五次远程模型调用。日志显示 Redis KNN 返回 30 条结果只需要约 8 ms，主要耗时来自远程 rerank、规划、生成和校验，而不是 Redis 查询。

已实施的优化：

- `RETRIEVAL_TOP_K`：30 调整为 15。
- `RERANK_TOP_N`：8 调整为 5。
- `MAX_CONTEXT_TOTAL_CHARS`：调整为 12000。
- `EVIDENCE_PARENT_MAX_CHARS`：调整为 3000。
- `EVIDENCE_NEIGHBOR_WINDOW`：调整为 0。
- `QA_MAX_OUTPUT_TOKENS`：调整为 800。
- `ANSWER_PLANNING_MODE=adaptive`：简单单药问题采用确定性规划，复杂、多药或比较问题才调用规划模型。
- `ANSWER_VALIDATION_MODE=high_risk`：禁忌、剂量、不良反应、相互作用、特殊人群、警告和就医提示等高风险问题才调用语义校验模型。
- 查询先只使用原问题检索；召回弱时先做确定性扩展，仍不足时才调用 LLM 改写。
- 未指定药品的模糊问题在检索前直接要求澄清，实现零远程调用。
- 在返回结果中增加各阶段 `timings`，方便判断慢在 embedding、Redis/BM25、rerank、生成还是校验。

典型调用路径变为：

- 模糊通用问题：直接澄清，0 次远程调用。
- 低风险单药问题：embedding/检索 + rerank + 回答生成。
- 高风险单药问题：在上一条基础上增加回答校验。
- 多药、比较或复杂问题：按需增加回答规划。

配置修改后需要重启 MCP Server，不需要重建 Redis 或 SQLite 索引。

## 八、索引、存储与检索稳定性

### 11. Redis 与 SQLite 状态可能不一致

知识库同时使用 Redis 保存向量索引，使用 SQLite 保存文档、chunk、任务和 BM25 数据。如果写入中途失败，可能出现一侧更新而另一侧未更新的问题。

解决方案：

- 用进程内写锁和跨进程文件锁串行化索引写操作。
- SQLite 写入使用事务。
- 更新前保留旧 Redis 节点，失败时执行补偿恢复。
- 删除过程使用标记和状态检查，减少半删除状态。
- 写入后执行一致性验证，并在状态接口暴露异常。
- 将历史大 JSON docstore 迁移为 SQLite，降低整文件覆盖和损坏风险。

后续维护时，不能只检查 Redis 是否有向量，还要同时核对 SQLite 文档状态、chunk 数量和稳定 ID。

### 12. 中文 BM25 与无效节点污染

问题：默认按空格切词不适合中文；另外任务状态 JSON、空文本、纯图片占位符等内容如果进入索引，会降低召回质量。Redis 元数据还不能稳定接受所有 `None` 值。

解决方案：

- 中文 BM25 使用字符 bigram，并保留英文单词和数字 token。
- 过滤异步任务状态 JSON、空节点和无语义内容。
- 对 Redis metadata 做空值清理和类型归一化。
- 在正文切分后再附加来源信息，并把仅用于追踪的元数据排除出 embedding/LLM 输入，避免元数据占用 chunk token 预算。

## 九、安全问题

### 13. `.env.example` 出现疑似真实凭证

示例环境文件曾包含看似真实的 DashScope、MinerU 等密钥或标识。即使文件没有公开发布，也不应把真实凭证保存在示例配置中。

已执行：

- 把 `.env.example` 中的敏感值替换为空值或占位符。
- 文档和测试输出不再记录真实 API Key 或 Workspace ID。

仍需人工完成：在对应平台轮换曾经暴露过的密钥，并检查 Git 历史、日志、截图和终端记录中是否仍有旧值。仅删除当前文件内容不能使已泄露的密钥重新安全。

## 十、Ragas 评测设计重构：面试重点问题

### 问题 1：Ragas 在项目中到底承担什么职责？

Ragas 不是生产 RAG 框架或检索器。在本项目中它承担两项职责：一是利用给定上下文合成
候选 `question/reference/reference_contexts`；二是用 context precision、context recall、
faithfulness、answer accuracy 等指标辅助评价真实系统输出。生产切分、Redis 检索、BM25、
Hybrid、Auto-merging、Reranker、稳定 chunk ID 和人工金标由项目自己负责。

设计启示：第三方评测框架提供的是组件和默认逻辑，不能替代业务侧对“什么是可检索单元、
什么是正确证据”的定义。

### 问题 2：为什么不能直接把完整 Markdown 交给 Ragas 后评测生产检索？

完整文档模式适合快速合成问题，但 Ragas 的 splitter 与生产系统
`HierarchicalNodeParser(chunk_sizes=4096,1024,256)` 不同。生成上下文没有生产 chunk ID，
只能事后通过文本相似度回连。模糊回连容易选中更长父节点、相似药品和重复片段，造成 qrels
污染；此时 Recall 上升并不表示检索效果变好。

关键判断：生成问题使用什么边界不是纯预处理细节，它会直接决定 gold evidence 和最后指标
是否可信。

### 问题 3：为什么只使用生产叶子 chunk 出题仍然不够？

叶子 chunk 与 Dense 检索粒度一致，可解决 ID 对齐问题，但单个约 256-token 叶子经常只有
一句成份或半段适应症。由它生成的问题会过窄；如果 LLM 生成“用于什么并由什么组成”这样的
复合问题，单叶子又无法支撑完整 reference。

因此需要区分两个粒度：

- 检索判定粒度：叶子 chunk；
- 问题生成和回答上下文粒度：由多个相关叶子构成的受控证据包。

### 问题 4：受控证据包如何避免重新引入大 chunk 的评测偏差？

受控证据包不是把父节点 ID 直接作为 relevant。生成前先保存
`bundle_id → [leaf_chunk_id...]`，只把父节点正文交给 Ragas；生成后通过正文精确匹配回到
bundle，再展开为候选叶子 ID。正式 BEIR corpus 仍只有叶子节点，所以系统必须真正召回
小 chunk 才能得分。

当前包按生产 DocStore 的直接父子关系构造，天然限制在一个文档和一个局部上下文内。它能
避免跨文档误配，但如果单个源文档本身混合不同药品或剂型，仍需额外元数据约束和人工检查。

### 问题 5：为什么包内所有叶子不能直接作为 gold evidence？

Ragas 会基于整个包生成问题，但不返回“答案中的每个事实具体来自哪个叶子”的可靠标注。
一个五叶子包生成的问题可能只用到其中两个叶子。把五个全部写进 qrels 会扩大相关文档集合，
同时让 Recall 分母和错误分析失真。

因此自动展开的 ID 只是候选证据。审核者应保留能够完整回答问题的最小充分证据集：删除无关
叶子，但不能因为被测系统没有召回某个必要叶子而删除它。

### 问题 6：人工修改问题、reference 和 evidence 后，评测为什么不是“肯定全对”？

人工审核修正的是考试题和标准答案，不是系统输出。金标冻结后，BM25、Dense、Hybrid、
Reranker 仍可能漏召回、排序错误或引入噪声；回答模型也可能遗漏、曲解或幻觉。必须先冻结
gold，再运行被测系统，禁止根据某一路检索结果反向修改 gold，否则会发生评测数据泄漏。

### 问题 7：如何拆分检索评测和端到端回答评测？

检索评测使用同一份 `queries/qrels/corpus`，比较 BM25、Dense、Hybrid 和 Reranker 的
Recall@K、MRR、MAP、nDCG 与延迟。端到端评测再把真实召回上下文交给生成模型，评价答案
是否正确、完整、忠于证据。前者回答“找到了吗、排得好吗”，后者回答“找到后有没有答好”。
两者混在一个分数中无法定位问题发生在检索还是生成阶段。

### 问题 8：为什么 Ragas 生成结果只能叫候选集？

合成模型可能生成不自然的问题、不完整 reference、无法由上下文完全回答的问题，或者混淆
同名药品和不同剂型。Ragas 的输出质量也不代表生产 RAG 的质量，因为生成阶段根本没有调用
生产检索链路。正式数据必须经过领域审核，并建议用真实用户日志和人工设计问题补充合成数据，
覆盖无答案、歧义、跨章节、高风险和对比类场景。

### 问题 9：当前方案下一步还可以如何增强？

- 在受控证据包中给每个叶子增加临时证据标签，并要求生成器输出逐事实引用，自动缩小候选 gold。
- 为同一内容的重复或重叠叶子建立等价组，避免召回等价文本却因 node ID 不同被判错。
- 按药品、剂型、说明书章节、问题风险和跳数做分层采样，避免测试集被长文档主导。
- 增加生成后自动校验：问题各子句覆盖、reference entailment、药品/剂型一致性及证据充分性。
- 将真实用户问题作为核心回归集，Ragas 合成数据主要用于补覆盖和压力测试。

### 问题 10：为什么生成 30 个候选时几乎都来自同一篇长文档？

原实现把证据包按数据库返回顺序截断后交给 Ragas。生产节点通常按文档连续存储，因此
列表前几十个包可能全部来自同一篇长说明书；合成器再从这些节点生成指定数量的问题，最终
出现 29/30 个问题属于同一文档。这个现象是输入采样偏差，不代表该文档更重要，也不能靠
随机打乱稳定解决，因为随机结果难复现且仍无法保证文档覆盖。

解决方案是先按 `doc_id` 分组，组内按 `char_start` 和稳定节点 ID 排序，再逐轮从每个文档
各取一个证据包，直到达到输入上限。这样既可复现，也能限制长文档的支配效应。当前数据库
本地检查显示：默认生成 30 题时选择 60 个输入包，覆盖 33 篇文档，每篇最多 2 个包。
这只是改善“生成输入覆盖”；最终候选仍应按药品、剂型、章节和题型统计，必要时做分层配额。

### 问题 11：为什么一次候选生成需要几十分钟，如何降低无效成本？

旧流程把近千个节点全部执行 Summary、Embedding、Themes、NER 和关系构造，但最终只生成
几十道单跳题。大部分远程调用不会进入最终样本，且 LangSmith tracing 还可能产生 429
日志和额外的数据外发风险。

当前单跳默认采用轻量 transforms：固定三个中文 persona，只执行
`SingleHopSpecificQuerySynthesizer` 必需的 NER；送入 Ragas 的证据包默认限制为目标题量的
两倍，并允许通过 `--max-workers` 控制并发。只有 mixed 多跳模式才自动使用 Ragas 默认
图 transforms。默认同时关闭 LangSmith tracing，需显式设置
`EVAL_ENABLE_LANGSMITH_TRACING=true` 才启用。该优化降低的是候选构造成本，不改变生产
BM25、Dense、Hybrid 或 Reranker 的评测链路。

### 问题 12：如何降低合成题中的英文、残缺表格和数值单位错误？

仅依赖提示词不能保证生成质量。当前流程同时采用三层控制：生成前过滤过短证据包和明显缺少
完整 `<table>...</table>` 边界的 HTML 表格片段；生成时强制简体中文，并要求保留药品、
剂型、给药途径、治疗组、比较对象、数值和单位；生成后以确定性规则标记非中文输出、含糊
列引用、疑似丢失百分号及超过 4 个候选叶子的宽证据包。规则只负责把高风险记录送入重点
人工审核，不能替代事实蕴含判断，也不能自动把候选设置为 approved。

### 问题 13：为什么父节点包含正确答案，BEIR 仍然判定没有召回？

原评测的 qrels 使用生产叶子 `chunk_id`，但 Dense 在召回阶段使用
`AutoMergingRetriever`，BM25 的 FTS 又包含全部层级节点。因此系统可能返回包含正确叶子
的父节点，但严格 ID 比较仍把它判错；同时 BM25、Dense、Hybrid 和 Reranker 返回粒度
不同，阶段指标也无法公平比较。

当前统一采用“检索判定粒度与回答上下文粒度分离”的设计：Redis Dense 返回原始叶子，
BM25 从全层级 FTS 候选中过滤出 DocStore 叶子，Hybrid 只融合叶子 ID，Qwen reranker
只排序叶子锚点，然后计算叶子级 Recall/MRR/MAP/nDCG。指标计算完成后，生产问答才通过
`_build_evidence_contexts()` 将入选叶子扩展到受控大小的父节点。这样 BEIR qrels 与四个
消融阶段完全同粒度，同时保留父上下文对最终回答完整性的价值。

## 十一、验证结果

当前针对核心改动已执行：

```powershell
python -m pytest -q -p no:cacheprovider
```

当前结果为 42 项测试全部通过。受控证据包的精确映射、跨文档 round-robin、生成前质量
过滤、生成后风险提示、未知 chunk 拒绝、双重审核门禁及检索辅助逻辑均包含自动化测试。
实际调用远程 LLM 重新生成候选会产生费用，因此单元测试只验证本地构造和映射，不把一次
付费生成作为常规测试前置条件。

运行环境联调时还应额外检查：

1. 重启 MCP Server 后，模糊药品问题是否立即返回 `clarification_required`。
2. 明确药品问题的 `retrieval.timings` 和回答 `timings` 中哪个阶段最慢。
3. reranker 成功时 `retrieval_mode` 是否为 `rerank`，而不是 `hybrid_fallback`。
4. 高风险回答是否完成模型校验；若为 `deterministic_only`，应视为降级结果。
5. BEIR 数据是否全部来自人工审核通过的候选问题。

## 十二、尚待继续完善

- 为 reranker 的偶发网络错误增加有限次数的指数退避重试，并设置明确的连接/读取超时。
- 为回答校验采用严格结构化输出，并在解析失败时进行一次缩短上下文的重试。
- 在统一评测报告中自动区分真实 reranker 结果和 fallback 结果，防止误报指标。
- 扩充并人工审核更多问题，覆盖不同药品、章节、单跳、多跳、无答案和冲突证据场景。
- 建立延迟基线，分别统计 P50、P95 以及 embedding、检索、rerank、生成、校验各阶段耗时。
- 对医疗问答持续执行“证据不足则拒答或澄清”的策略，不能用模型常识补齐说明书缺失内容。
- 对受控证据包增加逐事实叶子引用，使自动候选 evidence 更接近最小充分证据集。
- 清理同一文档内重复或高度重叠的生产叶子节点，并定义等价证据的计分规则。
