# 天医——循证医疗信息与安全支持智能体前端

基于 Vue 3 + Vite 的单页面应用，面向医疗信息解释、危险信号识别、循证检索和临床安全复核。系统只提供信息与沟通支持，不能替代医生诊断、药师审方或急救服务。

## 页面

- `/medical`：流式医疗信息问答、RAG、工具增强和结构化循证安全报告
- `/medical-agent`：安全分诊、证据检索、临床安全复核三阶段 Agent
- `/knowledge-base`：医疗文档元数据上传、异步索引和带来源问答
- `/tasks`：医疗工作流进度与人工审核状态

所有 AI 对话内容均按 Markdown 渲染，并在插入页面前使用 DOMPurify 清洗。健康问题只通过 POST 请求体提交，不会出现在 URL 中；流式请求支持主动停止并保留不完整结果。

## 本地开发

```bash
npm install
npm run dev
```

开发服务器默认运行于 `http://localhost:3000`，并将 `/api` 代理到 `http://127.0.0.1:8123`。可复制 `.env.example` 并通过 `VITE_API_BASE_URL` 覆盖 API 地址。

## 构建

```bash
npm run build
```

Nginx 配置包含适用于 POST SSE 的关闭缓冲与长连接设置。

## Windows 桌面端

桌面端共用 Vue 页面和 Java API。启动 Java 后端后，在本目录运行以下命令，Tauri 会启动端口 3000 上的 Vite。若已有 Web 开发服务占用该端口，先停止该实例。

### 调试和打包

~~~powershell
npm run tauri -- dev
npm run tauri -- build --bundles nsis
~~~

桌面前端输出到 dist-desktop，Windows 安装包默认生成在 src-tauri/target/release/bundle/nsis。Web 构建仍输出到 dist。

### 后端连接

首次打开桌面端时，输入管理员提供的后端 API 地址和工作台访问令牌，点击「验证并连接」。连接成功后，可以在「模型设置」保存个人模型和 API Key。后端地址会保留在本机，访问令牌随窗口会话保存。通过右上角「访问配置」可切换后端。

用户必须能够访问所填的 Java 后端。跨网络使用时采用可访问的 HTTPS 地址，例如 `https://agent.example.com/api`。可通过 `.env.desktop.local` 中的 `VITE_DESKTOP_API_BASE_URL` 为安装包预填地址。

## 医疗安全约束

- 任意页面持续展示“不能替代专业诊疗与急救”的安全边界。
- 回复包含“拨打 120”或报告风险级别为 `EMERGENCY` 时，显示不可关闭的红色紧急提示。
- 循证来源展示发布机构、版本、发布日期、适用人群、生效状态和审核状态。
- 未检索到来源时明确提示证据不足，不把回答包装成确定的医疗结论。
- 输入区提醒用户不要提交无关的身份信息。
