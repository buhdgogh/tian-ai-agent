# 历史学习笔记（原始上游项目）

> 本文件仅保留原始课程项目的学习记录，不代表当前产品功能。当前项目已经迁移为“启航——大学生职业规划智能体”，请以根目录 `README.md`、前后端源码和各模块 README 为准。

# 历史学习笔记（原始上游项目）

> 本文件仅保留原始课程项目的学习记录，不代表当前产品功能。当前项目已经迁移为“启航——大学生职业规划智能体”，请以根目录 `README.md`、前后端源码和各模块 README 为准。

# Tian-AI-Agent 项目技术文档

## 项目概述

Tian-AI-Agent 是一个 AI 智能体应用平台，包含两大核心应用：**AI 恋爱大师**（领域知识问答）和 **AI 超级智能体 YuManus**（ReAct 自主规划智能体）。项目采用前后端分离架构。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端框架** | Spring Boot 3.5 + Java 21 |
| **AI 框架** | Spring AI 1.0 |
| **大模型** | 阿里云百炼 DashScope (qwen-plus) / Ollama 本地模型 |
| **向量数据库** | SimpleVectorStore (内存) / PGVector (PostgreSQL 扩展) |
| **序列化** | Kryo（对话记忆持久化） |
| **API 文档** | Knife4j (Swagger) |
| **协议** | MCP (Model Context Protocol) |
| **工具库** | Hutool、Jsoup、iText、Kryo |
| **前端框架** | Vue 3 + Vue Router 4 |
| **构建工具** | Vite 4 |
| **HTTP 客户端** | Axios + EventSource (SSE) |
| **部署** | Docker |

---

## 项目结构

```
tian-ai-agent/
├── tian-ai-agent-frontend/            # Vue3 前端
│   └── src/
│       ├── api/index.js             # API 层（SSE + REST）
│       ├── router/index.js          # 路由配置
│       ├── views/                   # 页面组件
│       │   ├── Home.vue             # 首页（应用选择）
│       │   ├── LoveMaster.vue       # AI恋爱大师
│       │   └── SuperAgent.vue       # AI超级智能体
│       └── components/              # 通用组件
│           ├── AppSidebar.vue       # 侧边栏（会话管理）
│           ├── ChatRoom.vue         # 聊天室核心组件
│           └── AiAvatarFallback.vue # AI 头像
│
└── tian-ai-agent-backend/             # Java 后端
    ├── tian-ai-agent-admin/           # 主应用模块
    │   └── src/main/java/com/tianaiagent/
    │       ├── agent/               # AI Agent 分层架构
    │       │   ├── BaseAgent.java       # 抽象基类
    │       │   ├── ReActAgent.java      # ReAct 模式
    │       │   ├── ToolCallAgent.java   # 工具调用
    │       │   └── YuManus.java         # 超级智能体
    │       ├── service/
    │       │   ├── LoveAppService.java      # 恋爱大师服务
    │       │   └── SuperAgentService.java   # 超级智能体服务
    │       ├── controller/
    │       │   ├── AiController.java        # AI 对话接口
    │       │   └── HealthController.java    # 健康检查
    │       ├── tools/                   # 7 种内置工具
    │       ├── rag/                     # RAG 全链路
    │       ├── chatmemory/              # 对话记忆持久化
    │       ├── advisor/                 # 自定义 Advisor
    │       ├── config/                  # 全局配置
    │       └── constant/               # 常量定义
    │
    └── yu-image-search-mcp-server/  # MCP 图片搜索服务
        └── src/main/java/com/tianaiagent/tools/
            └── ImageSearchTool.java     # Pexels 图片搜索工具
```

---

## AI 恋爱大师模块：从设计到开发

### 一、需求分析与功能定位

**AI 恋爱大师**定位为"恋爱心理领域的智能顾问"，核心能力：

1. **基础对话**：以恋爱心理专家角色与用户进行多轮对话
2. **领域知识问答**：基于恋爱领域知识库（单身/恋爱/已婚三阶段）提供专业建议
3. **工具调用**：可调用搜索、文件操作等工具辅助回答
4. **结构化输出**：生成结构化的恋爱报告
5. **MCP 外部服务**：通过 MCP 协议调用外部图片搜索等服务

### 二、技术选型思考

核心问题是"**如何让大模型具备恋爱领域专业知识，并能记住多轮对话上下文？**"

| 问题 | 技术方案 | 依赖 |
|------|----------|------|
| 接入大模型 | Spring AI ChatClient 统一 API | DashScope (qwen-plus) |
| 多轮对话记忆 | ChatMemory 接口 + FileBasedChatMemory 文件持久化 | Kryo 序列化 |
| 领域知识增强 | RAG（检索增强生成） | 向量存储 + 语义检索 |
| 实时流式输出 | SSE (Server-Sent Events) | Spring WebFlux Flux |
| 工具扩展能力 | Spring AI Tool Calling | @Tool 注解 |
| 外部服务集成 | MCP 协议 | Spring AI MCP Client |
| 请求/响应观测 | 自定义 Advisor 拦截链 | CallAdvisor / StreamAdvisor |

### 三、分步实现

#### 第1步：基础对话 — ChatClient + 系统提示词

**目标**：让大模型扮演恋爱心理专家角色。

**思考**：Spring AI 的 `ChatClient` 是对 LLM 调用的高级封装，通过 `.defaultSystem()` 设置系统提示词，定义 AI 的角色行为。这是最简单的接入方式，一行 `chatClient.prompt().user(message).call().chatResponse()` 即可获取回复。

```java
// LoveAppService.java — 构造函数中构建 ChatClient
private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家..." +
        "围绕单身、恋爱、已婚三种状态提问...";

public LoveAppService(ChatModel dashscopeChatModel) {
    chatClient = ChatClient.builder(dashscopeChatModel)
            .defaultSystem(SYSTEM_PROMPT)   // 系统提示词定义角色
            .build();
}

// 同步对话
public String doChat(String message, String chatId) {
    return chatClient.prompt()
            .user(message)
            .call()
            .chatResponse()
            .getResult().getOutput().getText();
}
```

**关键设计**：`ChatClient` 在构造时绑定系统提示词，后续每次调用自动携带，无需重复传递。

---

#### 第2步：多轮对话记忆 — ChatMemory + FileBasedChatMemory

**目标**：AI 能记住之前的对话内容。

**思考**：大模型本身是无状态的，每次请求都是独立的。要实现多轮对话，需要把历史消息每次一起发给 LLM。Spring AI 提供了 `ChatMemory` 接口和 `MessageChatMemoryAdvisor`，后者自动从 ChatMemory 加载历史消息并拼入请求。

**2.1 选择文件持久化而非内存/数据库**

| 方案 | 优点 | 缺点 |
|------|------|------|
| InMemoryChatMemory | 零配置 | 重启丢失 |
| JdbcChatMemory | 持久可靠 | 需要数据库 |
| **FileBasedChatMemory** | **零依赖、持久化、开箱即用** | 不支持分布式 |

选择文件持久化的原因是：项目定位为学习/演示用途，应做到"拉下来就能跑，无需配置数据库"。

**2.2 序列化方案选择 Kryo**

Java 原生序列化有安全风险和性能问题。Kryo 是高性能二进制序列化框架，比 JSON 更紧凑，比 Java 序列化更快。但由于 Spring AI 的 `Message` 对象（UserMessage / AssistantMessage）可能不被 Kryo 直接序列化，需要引入 DTO 中间层：

```
Message 对象 → ChatMessageDTO(content, role) → Kryo 二进制 → .kryo 文件
```

```java
// FileBasedChatMemory.java — 核心逻辑
// 保存：Message → DTO → Kryo
private void saveConversation(String conversationId, List<Message> messages) {
    ArrayList<ChatMessageDTO> dtos = messages.stream()
        .map(m -> new ChatMessageDTO(m.getText(), m.getMessageType().name()))
        .collect(Collectors.toCollection(ArrayList::new));
    try (Output output = new Output(new FileOutputStream(file))) {
        kryo.writeObject(output, dtos);
    }
}

// 读取：.kryo 文件 → DTO → Message
private List<Message> getOrCreateConversation(String conversationId) {
    // 反序列化为 DTO 列表，再根据 role 还原为 Spring AI Message 对象
    if ("USER".equalsIgnoreCase(dto.getRole())) {
        messages.add(new UserMessage(dto.getContent()));
    } else if ("ASSISTANT".equalsIgnoreCase(dto.getRole())) {
        messages.add(new AssistantMessage(dto.getContent()));
    }
}
```

**2.3 集成到 ChatClient**

```java
// LoveAppService 构造函数
ChatMemory chatMemory = new FileBasedChatMemory(System.getProperty("user.dir") + "/tmp/chat-memory");

chatClient = ChatClient.builder(dashscopeChatModel)
        .defaultSystem(SYSTEM_PROMPT)
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(chatMemory).build()  // ← 自动注入历史消息
        )
        .build();

// 每次调用时传入 conversationId，Advisor 自动按 ID 加载/保存
public String doChat(String message, String chatId) {
    return chatClient.prompt()
            .user(message)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))  // 指定对话ID
            .call().chatResponse().getResult().getOutput().getText();
}
```

**数据流**：
```
请求进入 → MessageChatMemoryAdvisor.adviseCall()
  → chatMemory.get(chatId) 加载历史消息
  → 拼入当前请求
  → 调用 LLM
  → chatMemory.add(chatId, [userMessage, assistantMessage]) 保存新消息
  → 返回响应
```

---

#### 第3步：SSE 流式输出

**目标**：实现逐字输出效果，提升用户体验。

**思考**：同步模式下用户需要等待完整回复才能看到内容（可能有 10-30 秒延迟）。流式输出让 AI 生成一个字就推送一个字，前端渐进式渲染。

Spring AI 的 `ChatClient.stream().content()` 返回 `Flux<String>`（Reactor 响应式流），每个元素是 LLM 生成的一个文本块。

```java
// LoveAppService.java — SSE 流式对话
public Flux<String> doChatByStream(String message, String chatId) {
    return chatClient.prompt()
            .user(message)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
            .stream()        // ← 关键：使用 stream() 而非 call()
            .content();      // 返回 Flux<String>
}
```

**Controller 层的三种 SSE 实现方式**：

| 方式 | 返回值 | 特点 |
|------|--------|------|
| `Flux<String>` + `produces=text/event-stream` | 直接返回 Flux | 最简洁，Spring 自动转换为 SSE |
| `Flux<ServerSentEvent<String>>` | 包装事件对象 | 可控制事件 ID、类型等元数据 |
| `SseEmitter` | 手动管理 | 更灵活，可控制超时、手动推送、错误处理 |

---

#### 第4步：结构化输出

**目标**：让 AI 生成结构化的"恋爱报告"，而非自由文本。

**思考**：自由文本难以在前端格式化展示。Spring AI 支持通过 `.entity(Class)` 直接将 LLM 输出映射为 Java 对象，底层通过 JSON Schema 约束 LLM 输出格式。

```java
// 定义输出结构
record LoveReport(String title, List<String> suggestions) {}

// 调用
public LoveReport doChatWithReport(String message, String chatId) {
    return chatClient.prompt()
            .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，" +
                    "标题为{用户名}的恋爱报告，内容为建议列表")
            .user(message)
            .call()
            .entity(LoveReport.class);  // ← 自动映射为 Java Record
}
```

**LLM 实际收到的是**："请以 JSON 格式输出，包含 `title` 字段和 `suggestions` 数组字段……"，Spring AI 自动完成 JSON Schema 生成和解析。

---

#### 第5步：RAG 知识库（检索增强生成）

**目标**：让 AI 具备恋爱领域的专业知识，能回答"如何提升魅力"、"异地恋怎么维持"等需要领域知识的问题。

**思考**：大模型的训练数据是通用的，对恋爱领域的细节问答可能不够专业。RAG 的思路是：**先把领域知识文档向量化存储，用户提问时检索相关文档片段，作为上下文一起发给 LLM**。

**RAG 全链路数据流（ETL + Query）**：

```
                    ┌────── 写入阶段 (ETL) ──────┐
                    │                              │
  知识库 Markdown 文档 (3 篇)                       │
  单身篇 / 恋爱篇 / 已婚篇                          │
        │                                         │
        ▼                                         │
  LoveAppDocumentLoader                           │
  · 扫描 classpath:document/*.md                  │
  · 从文件名提取 status 元数据（单身/恋爱/已婚）       │
  · MarkdownDocumentReader 解析为 Document 列表      │
        │                                         │
        ▼                                         │
  MyTokenTextSplitter                             │
  · TokenTextSplitter(chunkSize=200, overlap=100) │
  · 按 token 数切割，保持语义完整性                  │
        │                                         │
        ▼                                         │
  MyKeywordEnricher                               │
  · KeywordMetadataEnricher + LLM                 │
  · 自动提取 5 个关键词作为文档元数据                 │
        │                                         │
        ▼                                         │
  SimpleVectorStore                               │
  · DashScope Embedding 向量化                     │
  · 内存存储（开箱即用）                            │
                    └──────────────────────────────┘

                    ┌────── 查询阶段 (Query) ──────┐
                    │                              │
  用户提问: "怎么追心仪的女生？"                     │
        │                                         │
        ▼                                         │
  QueryRewriter                                   │
  · RewriteQueryTransformer + LLM                │
  · "怎么追心仪的女生" → "单身男性如何追求心仪的女性对象"│
        │                                         │
        ▼                                         │
  VectorStore 相似度检索                           │
  · 重写后的查询 → Embedding → 余弦相似度 TopK      │
        │                                         │
        ▼                                         │
  QuestionAnswerAdvisor                           │
  · 拼接：系统提示词 + 检索到的文档片段 + 用户问题    │
        │                                         │
        ▼                                         │
  LLM 生成回答（结合检索到的领域知识）               │
                    └──────────────────────────────┘
```

**每个环节的设计考量**：

**5.1 文档加载 — 元数据提取是关键**

```java
// LoveAppDocumentLoader.java
// 从文件名中提取状态标签作为过滤维度
// 文件名: "恋爱常见问题和回答 - 单身篇.md"
String status = filename.substring(filename.length() - 6, filename.length() - 4);
// status = "单身"
```

**设计意图**：用户在"单身"状态下提问，应该优先检索单身相关的文档。通过 `status` 元数据实现**过滤式检索**（Filtered Search），避免检索到不相关的已婚或恋爱篇内容。

**5.2 文档切割 — 平衡语义完整性和检索精度**

```java
// MyTokenTextSplitter.java
TokenTextSplitter splitter = new TokenTextSplitter(
    200,   // chunkSize: 每块 200 个 token
    100,   // overlap: 相邻块重叠 100 token（避免语义截断）
    10,    // minChunkSize: 最小块大小
    5000,  // maxChunkSize: 最大块大小
    true   // 保留分隔符
);
```

**设计意图**：chunk 太小 → 语义碎片化、检索不准；chunk 太大 → 噪声多、LLM 上下文浪费。200 token 是经验值，在问答场景中恰好覆盖一个完整问答对。100 token 重叠确保切割边界不会切断关键信息。

**5.3 关键词增强 — 提高检索召回率**

```java
// MyKeywordEnricher.java
KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashscopeChatModel, 5);
```

**设计意图**：用户提问和文档用词可能不同（"追女生" vs "追求心仪对象"）。通过 LLM 提取关键词补充到文档元数据中，缩小**用户查询和文档之间的语义鸿沟**。

**5.4 查询重写 — 优化用户模糊提问**

```java
// QueryRewriter.java
queryTransformer = RewriteQueryTransformer.builder()
        .chatClientBuilder(ChatClient.builder(dashscopeChatModel))
        .build();

public String doQueryRewrite(String prompt) {
    Query query = new Query(prompt);
    Query transformedQuery = queryTransformer.transform(query);
    return transformedQuery.text();
}
```

**设计意图**：用户通常提问题很口语化、模糊。LLM 重写后，查询更精准、关键词更丰富，检索命中率更高。例如："怎么追女生啊" → "单身男性如何有效追求心仪的女性对象"。

**5.5 向量存储 — 三种方案分层**

```java
// LoveAppVectorStoreConfig.java
@Bean
VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
    SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
    // 1. 加载 → 2. 切割 → 3. 增强 → 4. 写入
    List<Document> documentList = loveAppDocumentLoader.loadMarkdowns();
    List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documentList);
    List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documentList);
    simpleVectorStore.add(enrichedDocuments);
    return simpleVectorStore;
}
```

| 方案 | 实现 | 适用场景 |
|------|------|----------|
| SimpleVectorStore | 内存 Map 存储 | **开发/演示**，零依赖 |
| PGVector | PostgreSQL + HNSW 索引 | **生产环境**，持久化 + 高性能 |
| DashScope 云知识库 | 阿里云托管检索服务 | 免运维，按量付费 |

**5.6 自定义 RAG Advisor — 按用户状态过滤**

```java
// LoveAppRagCustomAdvisorFactory.java
public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
    // 按 status 元数据过滤：仅检索与用户当前状态匹配的文档
    Filter.Expression expression = new FilterExpressionBuilder()
            .eq("status", status).build();

    DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .filterExpression(expression)      // 过滤条件
            .similarityThreshold(0.5)          // 相似度阈值
            .topK(3)                           // 最多返回 3 条
            .build();

    return RetrievalAugmentationAdvisor.builder()
            .documentRetriever(retriever)
            .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createInstance())
            .build();
}
```

**设计意图**：如果用户是"单身"，就不应该检索"已婚"的文档内容。通过 `status` 元数据过滤实现**精准检索**。`similarityThreshold=0.5` 过滤低相关度结果，`topK=3` 控制上下文长度。

**5.7 兜底策略 — 防止知识库外的问题**

```java
// LoveAppContextualQueryAugmenterFactory.java
// 当检索不到相关文档时（相似度低于阈值），返回预设的友好提示
ContextualQueryAugmenter.builder()
    .allowEmptyContext(false)  // 不允许空上下文
    .emptyContextPromptTemplate(
        new PromptTemplate("抱歉，我只能回答恋爱相关的问题..."))
    .build();
```

**设计意图**：防止用户问"怎么写代码"这种不相关问题，避免 LLM 在无知识库支撑下胡乱回答。

---

#### 第6步：工具调用（Tool Calling）

**目标**：让 AI 能够执行搜索网页、读写文件、生成 PDF 等操作。

**思考**：纯文本对话的 AI 能力有限。需要给它"手和脚"——能够执行真实世界操作的函数。Spring AI 通过 `@Tool` 注解定义工具，LLM 自动判断何时调用哪个工具。

**工具注册流程**：

```
1. 定义工具类 + @Tool 注解
   ↓
2. ToolRegistration.java 集中注册为 ToolCallback[] Bean
   ↓
3. LoveAppService 通过 @Resource 注入所有工具
   ↓
4. chatClient.prompt().toolCallbacks(allTools) 传给 LLM
   ↓
5. LLM 自主判断是否调用工具、调用哪个、传什么参数
   ↓
6. Spring AI 执行工具，将结果返回给 LLM
   ↓
7. LLM 结合工具结果生成最终回复
```

```java
// LoveAppService.java — 工具调用
public String doChatWithTools(String message, String chatId) {
    return chatClient.prompt()
            .user(message)
            .toolCallbacks(allTools)  // ← 注入全部 7 个工具
            .call()
            .chatResponse()
            .getResult().getOutput().getText();
}
```

**设计要点**：LLM 会根据 `@Tool(description="...")` 中的描述自动理解每个工具的用途，不需要硬编码 if-else 逻辑。

---

#### 第7步：MCP 协议集成

**目标**：通过标准化的 MCP 协议调用外部工具服务。

**思考**：工具调用的痛点在于——每个工具 API 不同，集成方式各异。MCP 协议提供了统一标准的工具发现和调用方式。本项目有一个独立的 MCP 图片搜索服务（`yu-image-search-mcp-server`），通过 MCP 协议暴露 `searchImage` 工具。

```java
// LoveAppService.java — MCP 工具调用
@Resource
private ToolCallbackProvider toolCallbackProvider;  // MCP 工具自动发现

public String doChatWithMcp(String message, String chatId) {
    return chatClient.prompt()
            .user(message)
            .toolCallbacks(toolCallbackProvider)  // ← MCP 工具同样走 ToolCallback 机制
            .call()
            .chatResponse()
            .getResult().getOutput().getText();
}
```

**设计要点**：MCP 工具和本地 `@Tool` 工具使用相同的 `toolCallbacks()` 接口，对上层代码透明——LLM 不关心工具是本地的还是远程的。

---

#### 第8步：自定义 Advisor — 日志观测 + 推理增强

**目标**：观察每次 AI 请求/响应的完整 Prompt，以及提升 LLM 推理质量。

**8.1 MyLoggerAdvisor — 日志观测**

```java
// 实现 CallAdvisor + StreamAdvisor 双接口
// 同步模式：before 记录请求 → chain 执行 → observeAfter 记录响应
// 流式模式：ChatClientMessageAggregator 聚合流式块后记录完整响应

private ChatClientRequest before(ChatClientRequest request) {
    log.info("AI Request: {}", request.prompt());  // 打印完整 Prompt
    return request;
}

private void observeAfter(ChatClientResponse response) {
    log.info("AI Response: {}", response.chatResponse().getResult().getOutput().getText());
}
```

**8.2 ReReadingAdvisor — 推理增强**

Re-Reading（重复阅读）是一种 Prompt 工程技巧：让 LLM 把问题读两遍，有助于提升复杂推理的准确性。

```java
// 将用户消息改写为：
// "{原始问题}\nRead the question again: {原始问题}"
private ChatClientRequest before(ChatClientRequest request) {
    String userText = request.prompt().getUserMessage().getText();
    String newUserText = "%s\nRead the question again: %s".formatted(userText, userText);
    return new ChatClientRequest(request.prompt().augmentUserMessage(newUserText), ...);
}
```

### 四、Controller 层 — 暴露 API

```java
@RestController
@RequestMapping("/ai")
public class AiController {

    // 会话管理
    GET    /ai/sessions?type=love              → 获取历史对话列表
    GET    /ai/sessions/{chatId}/messages       → 获取指定对话的全部消息
    DELETE /ai/sessions/{chatId}                → 清空指定对话

    // AI 对话（4 种方式，逐步增强）
    GET /ai/love_app/chat/sync                  → 同步对话（基础）
    GET /ai/love_app/chat/sse                   → SSE 流式（Flux<String>）
    GET /ai/love_app/chat/server_sent_event     → SSE 流式（ServerSentEvent）
    GET /ai/love_app/chat/sse_emitter           → SSE 流式（SseEmitter）
}
```

### 五、整体架构总结

```
                            前端 (Vue3)
                               │
                     SSE (EventSource) + REST (Axios)
                               │
              ┌────────────────┼────────────────┐
              ▼                                 ▼
      AiController                        HealthController
              │
              ▼
       LoveAppService
              │
      ┌───────┼───────────┬──────────────┬──────────────┐
      ▼       ▼           ▼              ▼              ▼
  ChatClient  ChatMemory  RAG Pipeline   ToolCallbacks  MCP
  (LLM调用)   (Kryo文件)   (向量检索)      (7个工具)     (远程工具)
      │                    │
      ▼                    ▼
  DashScope          SimpleVectorStore
  (qwen-plus)        (内存向量库)
```

**核心设计原则**：

1. **渐进式增强**：基础对话 → 多轮记忆 → 流式输出 → 结构化输出 → RAG → 工具调用 → MCP，每一步在前一步上叠加
2. **零依赖启动**：默认使用内存向量库 + 文件对话记忆，不需要数据库
3. **Advisor 链模式**：通过 Spring AI 的 Advisor 机制，以插件方式叠加能力（记忆、日志、RAG）
4. **同一入口、多种能力**：同一个 ChatClient，通过不同方法暴露不同能力组合
5. **工具统一抽象**：本地 `@Tool` 和远程 MCP 工具都通过 `ToolCallback` 接口接入

---

## API 接口文档

服务运行在 `http://localhost:8123/api`，接口文档页面：`http://localhost:8123/api/doc.html`

### 1. 健康检查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 返回 `ok`，用于服务探活 |

### 2. 会话管理

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/ai/sessions` | `type` (love/super) | 获取指定类型的对话列表，返回 `[{id, title}]` |
| GET | `/api/ai/sessions/{chatId}/messages` | `type` (love/super) | 获取指定对话的全部历史消息 |
| DELETE | `/api/ai/sessions/{chatId}` | `type` (love/super) | 清空指定对话 |

### 3. AI 恋爱大师 — 对话接口

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/ai/love_app/chat/sync` | `message`, `chatId` | 同步对话，直接返回完整回复文本 |
| GET | `/api/ai/love_app/chat/sse` | `message`, `chatId` | **SSE 流式对话**（`text/event-stream`），逐字推送回复 |
| GET | `/api/ai/love_app/chat/server_sent_event` | `message`, `chatId` | SSE 流式对话，包装为 `ServerSentEvent` 对象 |
| GET | `/api/ai/love_app/chat/sse_emitter` | `message`, `chatId` | SSE 流式对话，基于 `SseEmitter`，3 分钟超时 |

**SSE 流式响应格式：**

```
data: 你
data: 好
data: ，
data: 我
data: 是
...
data: [DONE]
```

Flux 是服务器内部的数据流表示，而 SSE 是将这条数据流通过 HTTP 持续发送给浏览器的一种传输协议。

### 4. AI 超级智能体 — 对话接口

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/ai/manus/chat` | `message`, `chatId` | **SSE 流式对话**，实时推送智能体思考/行动/观察过程 |

**超级智能体 SSE 输出示例：**
```
data: 思考：用户想要搜索图片，我需要调用搜图工具

data: 正在调用图片搜索工具，搜索关键词：风景壁纸

data: 工具返回结果：找到 5 张相关图片...

data: 已为您找到以下图片资源...

data: [DONE]
```

---

## AI Agent 架构（ReAct 模式）

超级智能体 YuManus 基于 ReAct（Reasoning + Acting）模式，实现"**思考 → 行动 → 观察**"闭环。

### 四层继承体系

```
BaseAgent (抽象基类)
  ↑ 定义运行框架、步骤循环、SSE 流式推送
ReActAgent
  ↑ 引入 think() → shouldAct() → act() 模式
ToolCallAgent
  ↑ 实现 LLM 工具调用、工具执行、状态判断
YuManus (@Component)
  ↑ 超级智能体，配置系统提示词和最大步数
```

### 核心执行流程

```
用户输入
  → 加载历史消息（从 Kryo 文件恢复）
  → 追加用户消息到 messageList
  → 进入步骤循环（最多 20 步）：
      │
      ├─ think()：调用 LLM，检测是否需要调用工具
      │   └─ 如果有工具调用 → shouldAct = true
      │   └─ 如果没有工具调用 → 追加 AI 回复到消息列表
      │
      ├─ shouldAct() → true → act()
      │   └─ 执行工具调用，将结果追加到消息列表
      │   └─ 如果是 TerminateTool → 状态设为 FINISHED
      │
      └─ 检查状态：
          └─ FINISHED → 结束循环
          └─ 达到 maxSteps(20) → 强制结束
  → 保存消息到 Kryo 文件
  → 每步结果通过 SSE 实时推送到前端
```

### AgentState 状态机

```
IDLE → RUNNING → FINISHED
              ↘ ERROR
```

---

## 内置工具集

所有工具通过 `ToolRegistration.java` 统一注册为 Spring Bean。

| 工具 | 类 | 方法 | 说明 |
|------|-----|------|------|
| 文件读取 | `FileOperationTool` | `readFile(fileName)` | 从 `tmp/file/` 读取文件内容 |
| 文件写入 | `FileOperationTool` | `writeFile(fileName, content)` | 写入内容到 `tmp/file/` |
| 网页搜索 | `WebSearchTool` | `searchWeb(query)` | 调用 SearchAPI.io，返回百度前 5 条搜索结果 |
| 网页抓取 | `WebScrapingTool` | `scrapeWebPage(url)` | 使用 Jsoup 抓取网页 HTML |
| 资源下载 | `ResourceDownloadTool` | `downloadResource(url, fileName)` | 下载文件到 `tmp/download/` |
| 终端命令 | `TerminalOperationTool` | `executeTerminalCommand(command)` | Windows 下执行 cmd 命令 |
| PDF 生成 | `PDFGenerationTool` | `generatePDF(fileName, content)` | 使用 iText 7 生成 PDF |
| 终止执行 | `TerminateTool` | `doTerminate()` | 标记智能体任务完成 |

智能体通过 `@Tool` 注解自动发现工具，LLM 根据用户意图自主决定调用哪个工具。

---

## RAG 知识库（检索增强生成）

AI 恋爱大师集成了完整的 RAG 管道，为恋爱领域问答提供知识增强。

### RAG 数据流

```
Markdown 文档 (classpath:document/*.md)
  → LoveAppDocumentLoader（加载文档，提取元数据）
  → MyTokenTextSplitter（TokenTextSplitter 切割，chunk=200, overlap=100）
  → MyKeywordEnricher（LLM 提取关键词，丰富元数据）
  → VectorStore（SimpleVectorStore 内存存储 / PGVector）
  → 用户查询
      → QueryRewriter（LLM 重写查询，优化检索效果）
      → VectorStore 相似度检索
      → QuestionAnswerAdvisor / RetrievalAugmentationAdvisor
      → LLM 结合检索到的上下文生成回答
```

### 向量存储方案

| 方案 | 实现 | 状态 |
|------|------|------|
| SimpleVectorStore | 内存向量存储，DashScope Embedding | 默认启用 |
| PGVector | PostgreSQL + PGVector 扩展，维度 1536，HNSW 索引 | 需配置数据库后启用 |
| DashScope 云知识库 | `RetrievalAugmentationAdvisor` + 云检索器 | 可选 |

### 自定义顾问

- **`LoveAppRagCustomAdvisorFactory`**：基于文档 `status` 元数据（单身/恋爱/已婚）进行过滤检索，相似度阈值 0.5，TopK=3
- **`LoveAppContextualQueryAugmenterFactory`**：当检索无结果时返回友好提示"抱歉，我只能回答恋爱相关的问题..."

---

## 对话记忆持久化

### FileBasedChatMemory

基于 **Kryo 序列化**的文件持久化方案，无需外部数据库依赖。

```
工作目录/tmp/chat-memory/
  ├── {chatId_1}.kryo   ← Kryo 序列化的消息列表
  ├── {chatId_2}.kryo
  └── ...
```

- **序列化**：消息对象 → `ChatMessageDTO` → Kryo 二进制 → `.kryo` 文件
- **反序列化**：`.kryo` 文件 → Kryo → `ChatMessageDTO` → Spring AI `Message` 对象
- **会话列表**：扫描目录所有 `.kryo` 文件，取首条用户消息（截断 20 字符）作为会话标题
- 支持旧格式兼容（直接序列化的 `Message` 对象）

---

## MCP 协议集成：运行机制详解

### 整体架构：两方角色

MCP（Model Context Protocol）是一个标准化的 AI 工具调用协议。在本项目中，MCP 涉及**两个独立的 Java 进程**，通过 JSON-RPC 2.0 消息格式通信：

```
┌─────────────────────────────────┐      MCP 协议         ┌──────────────────────────┐
│   tian-ai-agent-admin (主应用)     │  ◄──────────────►     │  yu-image-search-mcp-     │
│   MCP Client 角色               │   JSON-RPC 消息        │  server (独立进程)         │
│                                 │                        │  MCP Server 角色           │
│   LoveAppService                │                        │                           │
│        │                        │                        │  ImageSearchTool          │
│        ▼                        │                        │    @Tool searchImage()    │
│   ToolCallbackProvider           │                        │         │                  │
│   (MCP 客户端自动发现远程工具)     │                        │         ▼                  │
│                                 │                        │  Pexels API (外部图片服务)   │
└─────────────────────────────────┘                        └──────────────────────────┘
```

主应用不知道图片搜索是如何实现的，只知道"有一个叫 `searchImage` 的远程工具可以用"——这就是 MCP 的核心价值：**工具实现与调用方解耦**。

---

### MCP 协议底层：JSON-RPC 2.0 三阶段交互

MCP 协议底层就是 **JSON-RPC 2.0**，一次完整的工具调用经历三个阶段：

```
阶段1: 初始化握手 (initialize)
   Client → Server: { "jsonrpc": "2.0", "method": "initialize",
                       "params": { "protocolVersion": "2025-06-18", ... } }
   Server → Client: { "jsonrpc": "2.0", "result": {
                       "serverInfo": { "name": "yu-image-search-mcp-server", "version": "0.0.1" },
                       "capabilities": { "tools": {} }
                     }}

阶段2: 工具发现 (tools/list)
   Client → Server: { "jsonrpc": "2.0", "method": "tools/list" }
   Server → Client: { "jsonrpc": "2.0", "result": { "tools": [
     { "name": "searchImage",
       "description": "search image from web",
       "inputSchema": { "type": "object", "properties": {
           "query": { "type": "string", "description": "Search query keyword" }
       }}}
   ]}}

阶段3: 工具调用 (tools/call)
   Client → Server: { "jsonrpc": "2.0", "method": "tools/call",
                       "params": { "name": "searchImage",
                                   "arguments": { "query": "风景壁纸" }}}
   Server → Client: { "jsonrpc": "2.0", "result": {
                       "content": [{ "type": "text", "text": "url1,url2,url3..." }]
                     }}
```

---

### 两种通信模式对比

| | SSE 模式（默认） | STDIO 模式 |
|------|----------|------------|
| **传输层** | HTTP + Server-Sent Events | 进程标准输入/输出 |
| **端口** | MCP Server 监听 **8127** 端口 | 无端口，父子进程管道通信 |
| **启动方式** | 先独立启动 MCP Server，主应用再连接 | 主应用启动时自动 spawn 子进程 |
| **Web 服务器** | 内嵌 Tomcat（MCP Server 端） | 关闭 (`web-application-type: none`) |
| **适用场景** | 远程服务、微服务架构 | 本地工具、桌面应用（如 Claude Desktop） |
| **配置方式** | `application.yml` 中配置 SSE URL | `mcp-servers.json` 中配置启动命令 |

---

### SSE 模式：完整运行时流程

#### 第1步：启动 MCP Server（独立进程）

```bash
java -jar yu-image-search-mcp-server/target/yu-image-search-mcp-server-0.0.1-SNAPSHOT.jar
```

```yaml
# application-sse.yml — MCP Server 端配置
spring.ai.mcp.server:
  name: yu-image-search-mcp-server    # 服务名称，在握手阶段返回给客户端
  version: 0.0.1
  type: SYNC                          # 同步模式：客户端等待工具执行完毕才继续
  stdio: false                        # false = SSE 模式
```

启动后，`spring-ai-starter-mcp-server-webmvc` 依赖自动注册 MCP 端点：

```
GET  /mcp/sse       ← SSE 长连接端点（客户端通过这个端点建立持久连接）
POST /mcp/message   ← JSON-RPC 消息端点（实际消息通过 SSE 通道收发）
```

同时，`MethodToolCallbackProvider` 扫描 `ImageSearchTool` 上的 `@Tool` 注解，将 `searchImage` 方法注册为可调用的 MCP 工具。

#### 第2步：主应用配置 MCP Client 连接

```yaml
# application.yml（tian-ai-agent-admin，当前被注释掉）
spring.ai.mcp.client.sse.connections:
  server1:
    url: http://localhost:8127/sse   # ← 指向 MCP Server 的 SSE 端点
```

取消注释后，Spring AI MCP Client 自动配置在启动时执行：

1. 向 `http://localhost:8127/sse` 发起 HTTP 请求，建立**持久 SSE 长连接**
2. 通过 SSE 通道发送 `initialize` 握手请求
3. 发送 `tools/list` 发现远程工具有哪些
4. 将远程工具包装为 `ToolCallbackProvider` Bean，注入 Spring 容器

#### 第3步：用户调用时的完整执行路径

```
用户在恋爱大师界面输入："帮我找几张风景壁纸"
        │
        ▼
AiController: GET /api/ai/love_app/chat/sse?message=帮我找几张风景壁纸
        │
        ▼
LoveAppService.doChatWithMcp(message, chatId)
        │
        ▼
chatClient.prompt()
    .user(message)
    .toolCallbacks(toolCallbackProvider)   ← MCP 工具注入
    .call()
        │
        ▼
LLM (DashScope qwen-plus) 分析用户意图：
  → 用户想搜索图片
  → 需要调用 searchImage 工具
  → 参数: query="风景壁纸"
        │
        ▼
Spring AI Tool Calling 框架拦截工具调用
  → 识别 searchImage 是 MCP 远程工具（非本地 @Tool）
  → 路由到 MCP Client 层
        │
        ▼
MCP Client 通过已建立的 SSE 长连接，发送 JSON-RPC 请求:
────────────────────────────────────────────────────────
POST (via SSE channel)
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "searchImage",
    "arguments": { "query": "风景壁纸" }
  }
}
────────────────────────────────────────────────────────
        │
        ▼
MCP Server (yu-image-search-mcp-server) 收到消息
  → JSON-RPC 路由: tools/call → searchImage
  → 调用 ImageSearchTool.searchImage("风景壁纸")
        │
        ▼
searchImage() 方法内部:
  1. 设置请求头: Authorization: <Pexels_API_KEY>
  2. GET https://api.pexels.com/v1/search?query=风景壁纸
  3. Pexels 返回 JSON:
     { "photos": [
         { "src": { "medium": "https://images.pexels.com/..." } },
         { "src": { "medium": "https://images.pexels.com/..." } },
         ...
     ]}
  4. 解析提取 "photos[].src.medium" 字段
  5. 返回: "url1,url2,url3,url4,url5"
        │
        ▼
MCP Server 通过 SSE 通道返回 JSON-RPC 响应:
────────────────────────────────────────────────────────
{
  "jsonrpc": "2.0",
  "result": {
    "content": [
      { "type": "text", "text": "url1,url2,url3,url4,url5" }
    ]
  }
}
────────────────────────────────────────────────────────
        │
        ▼
Spring AI MCP Client 收到响应
  → 解析 result.content[0].text
  → 将工具结果追加到 LLM 消息列表:
    messages.add(new ToolResponseMessage("url1,url2,url3,url4,url5"))
        │
        ▼
LLM 第二次推理（结合工具结果）:
  → "已为您找到以下风景壁纸，这些图片的链接分别是：
     url1 - 这是一张...
     url2 - 这是一张..."
        │
        ▼
SSE 流式返回给前端 → 用户看到图片搜索结果
```

---

### STDIO 模式：运行流程

**核心区别**：不需要先手动启动 MCP Server，主应用启动时会自动 spawn 子进程。

```json
// mcp-servers.json — 定义如何启动 MCP Server 子进程
{
  "mcpServers": {
    "yu-image-search-mcp-server": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",           // 开启 STDIO 模式
        "-Dspring.main.web-application-type=none",      // 不启动 Tomcat
        "-Dlogging.pattern.console=",                   // 清空日志格式（避免污染 stdout）
        "-jar",
        "yu-image-search-mcp-server/target/yu-image-search-mcp-server-0.0.1-SNAPSHOT.jar"
      ]
    },
    "amap-maps": {                                      // 另一个 MCP Server 示例
      "command": "npx.cmd",
      "args": ["-y", "@amap/amap-maps-mcp-server"],
      "env": { "AMAP_MAPS_API_KEY": "替换为您的Key" }
    }
  }
}
```

```yaml
# application.yml — 主应用配置 STDIO 模式
spring.ai.mcp.client.stdio:
  servers-configuration: classpath:mcp-servers.json
```

**STDIO 模式启动流程**：

```
主应用启动
  │
  ├─ 读取 classpath:mcp-servers.json
  │
  ├─ 对每个 mcpServer 配置：
  │    ProcessBuilder.start("java", "-jar", "yu-image-search-mcp-server.jar", ...)
  │    → 子进程启动
  │    → stdin/stdout 管道连接建立
  │
  ├─ 通过子进程的 stdin 发送 JSON-RPC 消息
  │
  └─ 通过子进程的 stdout 读取 JSON-RPC 响应

工具调用的后续流程与 SSE 模式完全一致，只是传输层从 HTTP 变成了管道通信。
```

**SSE vs STDIO 架构对比**：

```
SSE 模式：
  主应用 (8123) ──HTTP──► MCP Server (8127) ──HTTP──► Pexels API
  两个独立端口，各运行一个 Tomcat

STDIO 模式：
  主应用 (8123) ──stdin/stdout管道──► MCP Server 子进程 ──HTTP──► Pexels API
  只有一个端口，MCP Server 作为无界面的子进程运行
```

---

### MCP Server 端的工具注册机制

```java
// YuImageSearchMcpServerApplication.java — MCP Server 启动类
@SpringBootApplication
public class YuImageSearchMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(YuImageSearchMcpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imageSearchTool)  // ← 扫描 ImageSearchTool 上所有 @Tool 方法
                .build();
    }
}
```

`MethodToolCallbackProvider` 在初始化时做了三件事：

1. **反射扫描**：找到 `ImageSearchTool` 中所有带 `@Tool` 注解的方法
2. **提取 Schema**：从注解中提取工具名、描述、参数类型和描述，生成 JSON Schema
3. **注册到 MCP 协议层**：当 MCP Client 发送 `tools/list` 请求时，返回工具的 JSON Schema

**工具类的 MCP 注解**：

```java
// ImageSearchTool.java
@Service
public class ImageSearchTool {
    @Tool(description = "search image from web")       // ← 工具描述，LLM 据此判断何时调用
    public String searchImage(
        @ToolParam(description = "Search query keyword") // ← 参数描述
        String query
    ) {
        // 调用 Pexels API，解析并返回 medium 尺寸图片 URL 列表
        List<String> urls = searchMediumImages(query);
        return String.join(",", urls);
    }

    private List<String> searchMediumImages(String query) {
        // GET https://api.pexels.com/v1/search?query=xxx
        // Authorization: <API_KEY>
        // 解析响应 JSON: photos[].src.medium
    }
}
```

---

### MCP 在本项目中的使用方式

在 `LoveAppService` 中，MCP 远程工具和本地 `@Tool` 工具的使用方式**完全相同**：

```java
// 本地工具 — 7 个直接在项目中定义的 @Tool 方法
@Resource
private ToolCallback[] allTools;

// MCP 远程工具 — 通过 MCP 协议自动发现的工具
@Resource
private ToolCallbackProvider toolCallbackProvider;

// 使用方式完全一致，都是传给 toolCallbacks()
// LLM 不关心工具是本地的还是远程的

// 方式1: 使用本地工具
public String doChatWithTools(String message, String chatId) {
    return chatClient.prompt()
            .user(message)
            .toolCallbacks(allTools)           // 本地工具
            .call().chatResponse().getResult().getOutput().getText();
}

// 方式2: 使用 MCP 远程工具
public String doChatWithMcp(String message, String chatId) {
    return chatClient.prompt()
            .user(message)
            .toolCallbacks(toolCallbackProvider) // MCP 远程工具
            .call().chatResponse().getResult().getOutput().getText();
}
```

**这就是 MCP 协议的核心价值**：工具的实现位置对调用方透明。LLM 看到的都是 `searchImage(query)` 这个函数签名，不关心它是在本地 JVM 里执行还是通过 HTTP/管道在另一个进程里执行。

---

### 当前 MCP 功能为何被注释

```yaml
# application.yml
# 临时注释掉，便于大家开发调试和部署（实际需要启动 MCP 服务）
#    mcp:
#      client:
#        sse:
#          connections:
#            server1:
#              url: http://localhost:8127/sse
```

**原因**：MCP 需要满足三个前置条件才能工作：

1. 需要有效的 **Pexels API Key**（`ImageSearchTool.java` 中替换）
2. 需要先**编译并启动 MCP Server** 进程（`mvn package` → `java -jar`）
3. 确保端口 8127 未被占用

为了做到"项目拉下来就能跑"，默认关闭了 MCP 配置。如需启用：

```bash
# 1. 替换 ImageSearchTool.java 中的 API_KEY
# 2. 编译 MCP Server
cd tian-ai-agent-backend
mvn clean package -DskipTests -pl yu-image-search-mcp-server

# 3. 启动 MCP Server（SSE 模式）
java -jar yu-image-search-mcp-server/target/yu-image-search-mcp-server-0.0.1-SNAPSHOT.jar

# 4. 取消 application.yml 中 MCP 配置的注释

# 5. 重启主应用
mvn spring-boot:run -pl tian-ai-agent-admin
```

---

### MCP ToolCallbackProvider 自动注入原理

**关键事实**：你在 `LoveAppService` 里注入 MCP 工具只写了一行 `@Resource ToolCallbackProvider`，**没有写任何连接、握手、工具发现的代码**。这一切由 Spring AI 的自动配置完成。

#### 你的代码 vs 框架代码

| 你写的 | Spring AI 自动配置做的 |
|--------|----------------------|
| `pom.xml` 引入 `spring-ai-starter-mcp-client` | 加载 `McpClientAutoConfiguration` 自动配置类 |
| `application.yml` 配置 `spring.ai.mcp.client.sse.connections.server1.url` | 读取配置，创建 `McpClient` 实例 |
| `@Resource ToolCallbackProvider` | 建立 SSE 长连接 → 发送 `initialize` 握手 → 发送 `tools/list` 发现工具 → 将远程工具包装为 `ToolCallback` → 创建 `ToolCallbackProvider` Bean |
| `chatClient.toolCallbacks(toolCallbackProvider)` | LLM 决定调工具时，自动发送 `tools/call` 请求 |

#### 自动配置全流程

```
Spring Boot 应用启动
  │
  └─ 扫描 classpath，发现 spring-ai-starter-mcp-client 依赖
       │
       └─ McpClientAutoConfiguration 自动配置类触发
            │
            ├─ 1. 读取 application.yml 中的 spring.ai.mcp.client.* 配置
            │     spring.ai.mcp.client.sse.connections:
            │       server1:
            │         url: http://localhost:8127/sse
            │
            ├─ 2. 发现配置了 SSE 连接 → URL = http://localhost:8127/sse
            │
            ├─ 3. 创建 McpClient 实例
            │     底层: HttpClient 向 http://localhost:8127/sse 发起 GET 请求
            │     建立持久 SSE 长连接（连接持续到应用关闭）
            │
            ├─ 4. 通过 SSE 通道自动发送 initialize 请求（MCP 握手）
            │     ┌──────────────────────────────────────────┐
            │     │ Client → Server:                         │
            │     │ {                                        │
            │     │   "method": "initialize",                │
            │     │   "params": {                            │
            │     │     "protocolVersion": "2025-06-18",     │
            │     │     "clientInfo": { "name": "tian-ai-agent-admin" }
            │     │   }                                     │
            │     │ }                                        │
            │     │                                          │
            │     │ Server → Client:                         │
            │     │ {                                        │
            │     │   "result": {                            │
            │     │     "serverInfo": {                      │
            │     │       "name": "yu-image-search-mcp-server",
            │     │       "version": "0.0.1"                 │
            │     │     },                                   │
            │     │     "capabilities": { "tools": {} }      │
            │     │   }                                      │
            │     │ }                                        │
            │     └──────────────────────────────────────────┘
            │
            ├─ 5. 握手成功后，自动发送 tools/list 请求（工具发现）
            │     ┌──────────────────────────────────────────┐
            │     │ Client → Server:                         │
            │     │ { "method": "tools/list" }               │
            │     │                                          │
            │     │ Server → Client:                         │
            │     │ {                                        │
            │     │   "result": {                            │
            │     │     "tools": [{                          │
            │     │       "name": "searchImage",             │
            │     │       "description": "search image from web",
            │     │       "inputSchema": {                   │
            │     │         "type": "object",                │
            │     │         "properties": {                  │
            │     │           "query": {                     │
            │     │             "type": "string",            │
            │     │             "description": "Search query keyword"
            │     │           }                              │
            │     │         }                                │
            │     │       }                                  │
            │     │     }]                                   │
            │     │   }                                      │
            │     │ }                                        │
            │     └──────────────────────────────────────────┘
            │
            ├─ 6. 将每个远程工具包装为 ToolCallback 对象
            │     对每个 tools/list 返回的工具:
            │       → 根据 inputSchema 生成参数映射
            │       → 包装为 McpToolCallback（实现了 ToolCallback 接口）
            │
            └─ 7. 将所有 McpToolCallback 打包
                 → 创建 ToolCallbackProvider Bean
                 → 注册到 Spring 容器
                      ↑
                      │
          这个 Bean 就是你在 LoveAppService 里
          @Resource 注入的那个 toolCallbackProvider
```

#### 调用时的自动路由

```
用户消息: "帮我找风景壁纸"
  → chatClient.prompt().toolCallbacks(toolCallbackProvider).call()
  → LLM 决策: 需要调用 searchImage(query="风景壁纸")
  → Spring AI Tool Calling 框架:
      遍历 toolCallbackProvider 中的所有工具
      → 找到 name="searchImage" 的 McpToolCallback
      → 通过 SSE 通道发送 tools/call JSON-RPC 请求
      → 等待 Server 返回结果
      → 将结果作为 ToolResponseMessage 追加到 LLM 消息列表
```

#### 依赖关系

```
spring-ai-starter-mcp-client (你引入的 starter)
  └─ spring-ai-mcp-client (核心 MCP 客户端实现)
       ├─ McpClientAutoConfiguration (自动配置，Spring Boot 启动时触发)
       ├─ McpClient (封装 initialize / tools/list / tools/call 协议交互)
       └─ McpToolCallback (将远程工具包装为 ToolCallback 接口)
```

#### 为什么 @Resource 注入不会报错？

`McpClientAutoConfiguration` 使用 `@ConditionalOnProperty` 条件注解：

```java
// Spring AI 源码（概念示意）
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.client")  // ← 关键条件
public class McpClientAutoConfiguration {

    @Bean
    @ConditionalOnProperty("spring.ai.mcp.client.sse.connections")  // ← 只有配了 SSE 连接才创建
    public ToolCallbackProvider mcpToolCallbackProvider(...) {
        // 建立连接、握手、发现工具...
        return new DefaultToolCallbackProvider(remoteTools);
    }
}
```

**当前项目**：MCP 配置被注释了 → `@ConditionalOnProperty` 条件不满足 → `ToolCallbackProvider` Bean 不会被创建。但 `LoveAppService` 里写了 `@Resource ToolCallbackProvider`——这意味着：

- MCP 配置**未注释**：条件满足 → Bean 创建 → `@Resource` 注入成功
- MCP 配置**被注释**（当前）：条件不满足 → Bean 不存在 → Spring 启动时抛出 `NoSuchBeanDefinitionException`，**应用启动失败**

这就是为什么配置注释里写"临时注释掉，便于大家开发调试"——启用 MCP 需要先配好 MCP Server + API Key 一整套环境。

---

### 为什么 MCP Server 要拆成独立模块？

**核心问题**：图片搜索工具能不能直接写在 `tian-ai-agent-admin` 模块里，像其他 7 个工具那样用 `@Tool` 注解就行？

**答案**：技术上完全可以，项目中两种方式都有。拆开是刻意设计，用来演示 MCP 协议的三个核心价值。

#### 代码证据：两种方式并存

```java
// LoveAppService.java — 本地工具和 MCP 远程工具可以任意切换

// 方式1: 本地工具 — 直接写在 admin 模块里，同一个 JVM 进程
@Resource
private ToolCallback[] allTools;           // 7 个工具都在同一个模块
public String doChatWithTools(...) {
    chatClient.toolCallbacks(allTools)     // ← 本地调用，零网络延迟
}

// 方式2: MCP 远程工具 — 独立进程，通过 JSON-RPC 协议调用
@Resource
private ToolCallbackProvider toolCallbackProvider;
public String doChatWithMcp(...) {
    chatClient.toolCallbacks(toolCallbackProvider)  // ← 远程调用，走 MCP 协议
}
```

可以看到，对调用方（`LoveAppService`）来说，`allTools` 和 `toolCallbackProvider` 的使用方式完全一样——都是传给 `chatClient.toolCallbacks()`。这恰好演示了 MCP 的核心价值：**工具实现的位置对 LLM 和调用方透明**。

#### 设计意图1：演示跨语言工具复用

注意 `mcp-servers.json` 里不只有 Java 工具：

```json
{
  "mcpServers": {
    "yu-image-search-mcp-server": {     // Java 实现的搜图服务
      "command": "java", "-jar", "..."
    },
    "amap-maps": {                       // Node.js 实现的高德地图服务
      "command": "npx.cmd",              // ← 不是 Java！是 npm 包
      "args": ["-y", "@amap/amap-maps-mcp-server"]
    }
  }
}
```

如果所有工具都写成同一个模块里的 `@Tool` 方法，就无法演示"别人用 Node.js 写的工具，我的 Java 项目通过 MCP 协议直接拿来用"。**MCP 的设计目标就是：只要遵循协议，任何语言写的工具都能被任何语言的 AI 应用调用。**

#### 设计意图2：进程隔离——一个工具崩溃不影响主应用

```
写成一起（本地 @Tool — 同一个 JVM）:
  admin 进程 (8123)
    ├─ WebSearchTool         ← 正常
    ├─ ImageSearchTool       ← Pexels API 超时，线程池耗尽
    ├─ FileOperationTool     ← 正常
    └─ LoveAppService        ← 整个服务不可用 ❌

写成独立模块（MCP — 不同进程）:
  admin 进程 (8123)              MCP Server 进程 (8127)
    ├─ LoveAppService     ←→     ImageSearchTool
    ├─ WebSearchTool              ↑ 即使这里 OOM 崩溃
    └─ 正常运行 ✓                 ↑ admin 可返回错误降级
```

当图片搜索调用外部 API（Pexels），存在超时、限流、异常返回等风险。独立进程出问题不会拖垮主应用的 JVM。

#### 设计意图3：独立部署与独立扩展

```
场景：图片搜索流量暴增

方式A（写在一起）：
  → 整个 admin 应用扩容 3 个实例
  → 每个实例都带着完整的 7 个工具 + 图片搜索
  → 浪费资源，数据库连接池也跟着扩容

方式B（独立 MCP Server）：
  → 只扩容 MCP Server 到 3 个实例
  → admin 应用保持 1 个实例不变
  → 按需扩容，精准控制资源
```

#### 一句话总结

> **本地工具写在一起（简单直接，零延迟），需要跨语言复用 / 需要进程隔离 / 需要独立扩缩容的工具走 MCP（标准协议，解耦部署）**。项目中把图片搜索拆成独立 MCP Server，就是为了演示这条边界什么时候该画、怎么画。

---

## 前端架构

### 路由设计

| 路径 | 组件 | 说明 |
|------|------|------|
| `/` | `Home.vue` | 首页，展示两个 AI 应用入口卡片 |
| `/love-master` | `LoveMaster.vue` | AI 恋爱大师聊天页（粉色主题） |
| `/super-agent` | `SuperAgent.vue` | AI 超级智能体聊天页（蓝色主题） |

### SSE 流式通信

前端使用原生 `EventSource` 连接后端 SSE 接口：

```javascript
// 恋爱大师 — 单气泡实时更新
const eventSource = new EventSource(
  'http://localhost:8123/api/ai/love_app/chat/sse?message=xxx&chatId=xxx'
);
eventSource.onmessage = (e) => {
  if (e.data === '[DONE]') { eventSource.close(); return; }
  messages[aiMsgIndex].content += e.data;  // 逐字追加显示
};

// 超级智能体 — 多气泡分句显示
eventSource.onmessage = (e) => {
  if (e.data === '[DONE]') { flushBuffer(); return; }
  buffer += e.data;
  if (endsWithPunctuation(buffer)) { createBubble(buffer); }
};
```

### 会话管理

- 前端生成 `chatId`（格式：`love_xxxxxxxx` 或 `super_xxxxxxxx`）
- 侧边栏显示历史会话列表，支持切换、新建、清空
- 会话数据从后端 Kryo 文件加载，前端负责渲染

---

## 关键设计模式

1. **Agent 模式**：抽象基类定义运行框架，子类实现具体策略（ReAct → ToolCall → YuManus）
2. **Advisor 链**：Spring AI 的 Advisor 机制用于横切关注点（日志记录、提示词增强、RAG 检索）
3. **工具注册中心**：所有工具在 `ToolRegistration` 集中管理，以 `ToolCallback[]` 注入
4. **文件持久化**：Kryo 序列化替代数据库，开箱即用
5. **SSE 推送**：实时流式输出，前端渐进式渲染
