# Milvus 原生关键词检索

Milvus 模式现在使用两路独立召回：Dense 向量检索和 Milvus 内置 BM25 全文检索。
两路结果按 Chunk ID 去重，以等权 RRF 融合，再进行单文档候选限制与 MMR 多样性选择。
关键词可以召回 Dense 候选中不存在的片段，不再只对向量候选计算关键词覆盖率。

## 配置与已有文档

直接编辑 `tian-doc-parse-index-mcp-server/src/main/resources/application.yml`：

```yaml
doc-mcp:
  vector-store: milvus
  hybrid-rrf-k: 60
  bm25-score-scale: 1.0
  milvus:
    uri: http://127.0.0.1:19530
    token: root:Milvus
    database-name: default
    collection-name: knowledge_chunks
    keyword-collection-name: knowledge_chunks_bm25_v1
    memory-collection-name: agent_memories
    batch-size: 500
```

项目使用 Milvus 3.0.1、Java SDK 3.0.9，并已在真实 Milvus 3.0.1 实例验证。
Dense Collection 和语义记忆 Collection 保持原结构；新增 BM25 Collection 保存：

- `node_id`：Chunk 主键；`doc_id`：文档 ID。
- `search_text`：标题加片段正文，启用 Analyzer。
- `bm25_sparse`：Milvus BM25 Function 自动生成的稀疏向量。

索引使用 `SPARSE_INVERTED_INDEX`，度量为 `BM25`。Analyzer 为 `jieba + lowercase + removepunct`，
保留中文词语和 HbA1c 等英文缩写，不使用会过滤英文的纯中文过滤器。
调用方只提交正文，不自行计算或上传 BM25 向量。

启动时按主键分页读取 H2 中的现有叶子片段，为其补建关键词索引，不重新解析文件或计算 Embedding。
补建是主键幂等 upsert，中途失败会阻止启动；下次启动可重新补建。
因此必须继续使用原有 H2 知识库目录；仅有 Milvus 数据而丢失 H2 正文/元数据时无法完成补建。
现有关键词 Collection schema 不兼容时会明确报错，不会删除旧 Collection；可以配置新名称重建。
`search_text` 使用兼容现有存储模式的 VarChar，标题与正文合计最多 65535 UTF-8 字节，超限明确报错而不截断证据。

文档覆盖更新会 upsert 两路索引并删除失效 Chunk ID；文档删除会删除两路索引和 H2 记录。
H2 与两个 Milvus Collection 不属于同一个分布式事务，写入失败会返回错误，需重试原操作。
检索最终按 H2 有效 Chunk 校验候选，避免将中断更新留下的孤立索引行作为证据返回。
Admin 的 RAG 缓存版本已加入检索链路版本，避免升级后继续使用旧链路缓存答案。

## 查询接口

应用 HTTP 接口支持独立关键词检索：

```http
POST /api/knowledge-base/retrieve
Content-Type: application/json

{"question":"HbA1c 高血压","topK":5,"retrievalMode":"keyword"}
```

MCP 接口沿用 `retrieve_knowledge`，增加可选 `retrieval_mode`：

```json
{
  "jsonrpc":"2.0",
  "id":1,
  "method":"tools/call",
  "params":{
    "name":"retrieve_knowledge",
    "arguments":{"question":"HbA1c 高血压","top_k":5,"retrieval_mode":"keyword"}
  }
}
```

- `hybrid`（默认）：Dense + BM25 → RRF → MMR；现有 Agent 和问答自动使用此链路。
- `keyword`：仅 BM25 → MMR，不调用 Embedding 或 Dense 检索。
- `dense`：仅 Dense → MMR，便于消融比较。

返回值包括 `chunkId`、`documentId`、正文、父片段 ID、来源与元数据，以及：

- `bm25Score`：Milvus 返回的原始 BM25 分数。
- `keywordScore`：`BM25 / (BM25 + bm25-score-scale)`，范围 0～1。
- `denseScore`：原始余弦相关性截断到非负范围。
- `fusionScore`：归一化 RRF，仅用于排序和 MMR；不是证据置信概率。
- `score`：Dense 与归一化 BM25 的较大值，供既有证据阈值评估使用，也是启发式相关性信号而非医疗置信概率。

RRF 和相关性分数分开使用，不会因为某条无匹配结果“排第一”就赋予高证据分数。
更换检索算法后，证据阈值与 `bm25-score-scale` 需要在实际语料评测集上重新校准；旧 MRR/Hit 指标不能自动视为新链路结果。
本地 `vector-store: local` 保留旧向量/覆盖率实现，只供测试或明确的本地模式使用，显式 `keyword` 查询会报错，不冒充 BM25。

## 验证

```powershell
mvn -f tian-doc-parse-index-mcp-server/pom.xml test
mvn -f tian-doc-parse-index-mcp-server/pom.xml -Dmilvus.integration=true -Dmilvus.test.uri=http://127.0.0.1:19530 test
```

真实 Milvus 集成测试仅创建随机 `codex_bm25_it_` 前缀的 Collection 并在结束时删除它们。
验证已有片段补建、中文匹配、英文缩写大小写、标题匹配、空召回、双路融合、覆盖更新、删除与重复补建。
