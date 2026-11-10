import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/agent' },
  { path: '/chat', alias: '/medical', redirect: '/agent' },
  { path: '/agent', alias: '/medical-agent', name: 'Agent', component: () => import('../views/MedicalAgent.vue'), meta: { title: 'Agent 问答 - Tian 知识工作台' } },
  { path: '/knowledge-base', name: 'KnowledgeBase', component: () => import('../views/KnowledgeBase.vue'), meta: { title: '知识库导入 - Tian 知识工作台' } },
  { path: '/wiki', name: 'Wiki', component: () => import('../views/WikiGraph.vue'), meta: { title: 'Wiki 知识图谱 - Tian 知识工作台' } },
  { path: '/tasks', redirect: to => ({ path: '/agent-runs', query: { taskId: to.query.taskId } }) },
  { path: '/agent-runs', name: 'AgentRuns', component: () => import('../views/AgentRuns.vue'), meta: { title: 'Agent 运行日志 - Tian 知识工作台' } },
  { path: '/mcp-settings', name: 'McpSettings', component: () => import('../views/McpSettings.vue'), meta: { title: 'MCP 设置 - Tian 知识工作台' } },
  { path: '/agent-presets', name: 'AgentPresets', component: () => import('../views/AgentPresetSettings.vue'), meta: { title: 'Skills 与 Prompt 模板 - Tian 知识工作台' } },
  { path: '/model-settings', name: 'ModelSettings', component: () => import('../views/ModelSettings.vue'), meta: { title: '模型设置 - Tian 知识工作台' } },
  { path: '/tool-settings', name: 'ToolSettings', component: () => import('../views/ToolSettings.vue'), meta: { title: '配置中心 - Tian 知识工作台' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const history = import.meta.env.MODE === 'desktop' ? createWebHashHistory() : createWebHistory()
const router = createRouter({ history, routes, scrollBehavior: () => ({ top: 0 }) })
router.beforeEach(to => { if (to.meta.title) document.title = to.meta.title })
export default router
