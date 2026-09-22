# 启航——大学生职业规划智能体

启航是一个面向大学生的职业规划系统。项目基于 Vue 3、Spring Boot 3、Spring AI 与可控 ReAct Agent，围绕“认识自己—探索职业—分析差距—制定行动—复盘调整”提供对话、知识检索和多步任务能力。

## 核心能力

- 职业探索：结合兴趣、价值观、专业、技能与实践经历梳理候选方向
- 方向决策：比较不同职业方向的匹配依据、能力差距、风险与取舍
- 行动规划：生成带阶段、优先级、完成标准和复盘节点的计划
- 实习求职：支持岗位分析、简历优化、项目准备和面试准备
- 职业知识库：上传行业报告、职业介绍和岗位资料，进行带来源问答
- 多步 Agent：使用受控工具检索信息、分析差距并生成规划交付物
- 企业能力：异步工作流、任务中心、工具审计、会话记忆和 RAG 评测

## 项目结构

```text
Tian-AI-Agent-main/
├── tian-ai-agent-frontend/                   # Vue 3 + Vite 前端
│   └── src/                                # 页面、组件、路由与 API
└── tian-ai-agent-backend/                    # Maven 后端工程
    └── tian-ai-agent-admin/
        └── src/main/java/com/tianaiagent/
            ├── agent/                      # ReAct 与职业规划 Agent
            ├── service/                    # 职业规划对话和业务服务
            ├── rag/                        # 文档加载、检索与评测
            ├── workflow/                   # 职业规划异步工作流
            ├── toolgateway/                # 工具安全网关与审计
            └── controller/                 # REST 与 SSE 接口
```

## 技术栈

| 层次 | 技术 |
|---|---|
| 前端 | Vue 3、Vue Router、Axios、SSE、Vite |
| 后端 | Java 21、Spring Boot 3.5、Spring AI 1.0 |
| 模型 | 阿里云百炼 DashScope（默认 `qwen-plus`） |
| 知识库 | Spring AI VectorStore、混合检索、MCP 文档解析 |
| 基础设施 | Redis、阿里云 OSS、Docker |

## 快速开始

后端要求 JDK 21、Maven 3.9+，至少配置 `DASHSCOPE_API_KEY`：

```powershell
cd tian-ai-agent-backend
mvn -pl tian-ai-agent-admin spring-boot:run
```

后端默认运行在 `http://localhost:8123/api`，接口文档位于 `http://localhost:8123/api/swagger-ui.html`。

```powershell
cd tian-ai-agent-frontend
npm install
npm run dev
```

## 主要接口

| 接口 | 用途 |
|---|---|
| `GET /api/ai/advisor/chat/stream` | 职业规划助手流式对话 |
| `POST /api/ai/advisor/report` | 生成职业画像、推荐方向、能力差距和行动计划 |
| `POST /api/ai/advisor/rag-chat` | 职业知识库增强问答 |
| `GET /api/ai/career-agent/chat` | 多步职业规划 Agent |
| `POST /api/ai/workflows` | 提交异步职业规划工作流 |
| `POST /api/knowledge-base/upload` | 上传职业知识资料 |
| `POST /api/knowledge-base/query-with-sources` | 带来源的知识库问答 |

## 安全边界

- 行业趋势、招聘要求、薪酬和时间节点应以最新官方信息为准。
- 系统不伪造岗位、企业或就业数据，不替用户作出最终职业决定。
- 简历等私有资料在生产环境中必须按用户和租户隔离。
- Agent 不注册任意终端执行能力，不自动投递、报名或执行其他外部操作。

详细配置见 [后端说明](tian-ai-agent-backend/README.md) 和 [Agent/RAG 扩展说明](tian-ai-agent-backend/AGENT_RAG_ENTERPRISE_GUIDE.md)。
