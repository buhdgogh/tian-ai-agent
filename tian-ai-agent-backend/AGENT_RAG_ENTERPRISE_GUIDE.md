# 医疗 Agent 企业化与安全边界说明

系统面向健康教育、循证医疗信息检索和安全分诊支持，提供医疗助手、可控工具 Agent、急症红旗识别、知识库、异步任务、工具网关、隔离记忆、文档流水线、混合检索、引用回答和 RAG 评测。系统输出不能替代医生或药师。

## 1. 异步任务中心

任务状态保存在 `tmp/tasks`，服务重启后未完成任务会标记为失败，避免长期停留在运行状态。

- `GET /api/tasks/{taskId}`：查询任务进度和结果
- `GET /api/tasks?ownerId=&type=`：查询任务列表
- `DELETE /api/tasks/{taskId}`：取消任务

任务类型包括 `AGENT_WORKFLOW`、`DOCUMENT_INDEX` 和 `RAG_EVALUATION`。

## 2. Agent Workflow

`POST /api/ai/workflows`

```json
{
  "objective": "根据已审核指南解释某疾病的一般管理原则，并标出证据与不确定性",
  "chatId": "workflow_001",
  "ownerId": "user_001",
  "reviewEnabled": true
}
```

接口返回任务记录。系统先执行确定性急症检查，再由 LLM Planner 动态生成 1～5 个带验收条件的步骤，Executor 逐步调用医疗 Agent，LLM Reviewer 对目标覆盖、证据、引用、不确定性和安全边界进行结构化审查；审查失败时可触发结果修订或一次重规划。药物、处方、剂量、特殊人群和诊断相关任务由确定性规则强制标记 `requiresHumanReview=true`，LLM 无权取消，自动复核不能代替医生或药师审核。

## 3. Tool Gateway

网关提供参数大小限制、并发控制、执行超时、工具黑名单和调用审计。医疗 Agent 不注册任意命令执行、资源下载或文件编辑工具；网页正文作为不可信数据处理，并禁止读取本机和内网地址。

- `GET /api/tool-gateway/audits`：查看最近的工具调用审计

生产环境仍应在网关之外增加登录认证，并仅允许管理员访问审计接口。

## 4. 敏感健康会话记忆

`EnterpriseMemoryService` 分别管理医疗助手和医疗 Agent 的隔离记忆空间。默认文件存储提供会话 ID 校验、逐会话锁、原子写入和消息窗口；Redis 模式支持 TTL。生产环境必须增加身份认证、患者授权、租户隔离、脱敏、访问审计和删除机制，不能把聊天记录直接当作正式电子病历。

## 5. 异步文档处理

`POST /api/knowledge-base/upload` 保存文件后立即返回 `taskId`。后台阶段为：

`QUEUED -> UPLOADING -> PARSING -> INDEXING -> INDEXED/FAILED`

处理过程包括 OSS 上传、MinerU 解析、LlamaIndex 远程索引和本地混合索引。

## 6. 混合检索与引用

- `POST /api/knowledge-base/retrieve`：返回混合检索证据
- `POST /api/knowledge-base/query-with-sources`：生成带来源编号的回答

请求示例：

```json
{
  "question": "文档中的主要结论是什么？",
  "topK": 5
}
```

混合检索按照向量相似度和关键词覆盖率融合召回，并使用 MMR 降低重复片段。医疗文档必须携带发布机构、版本、发布日期、适用人群、文档类型、有效状态和审核状态；证据不足或版本冲突时不得输出确定性结论。

## 7. RAG 评测

`POST /api/rag-evaluations`

```json
{
  "name": "知识库回归集",
  "ownerId": "qa",
  "topK": 5,
  "generateAnswers": false,
  "cases": [
    {
      "id": "case-1",
      "question": "测试问题",
      "expectedDocumentIds": ["上传接口返回的文档ID"]
    }
  ]
}
```

报告提供 `HitRate@K`、`MRR@K`、平均首位得分；启用答案生成后还会统计引用覆盖率。评测异步运行，结果通过任务查询接口获取。

## 8. 生产部署提示

- 将 API Key、OSS 密钥全部迁移到密钥管理服务并轮换现有凭证。
- 给所有 API 增加认证、租户和 RBAC；当前扩展没有替代认证系统。
- 单机 JSON 任务存储适合当前部署形态。多实例部署时，应将任务仓库替换为 PostgreSQL/Redis，并将任务执行迁移至消息队列；会话记忆可直接通过 `AGENT_MEMORY_BACKEND=redis` 切换为 Redis。
- 混合索引使用配置的 EmbeddingModel，文档入库和查询会产生模型调用及费用。
- 生产环境必须由医疗、药学、隐私和安全负责人完成风险评估；本项目当前实现不代表医疗器械注册、监管批准或临床验证。
