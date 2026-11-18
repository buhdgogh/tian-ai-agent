import { createRouter, createWebHashHistory } from 'vue-router'

export const primaryNavigation = [
  { path: '/workspace', label: '工作区' },
  { path: '/agent', label: 'Agent 问答' },
  { path: '/knowledge-base', label: '知识库' },
  { path: '/knowledge-chat', label: '知识库问答' },
  { path: '/wiki', label: 'Wiki 知识图谱' },
  { path: '/agent-runs', label: '运行日志' },
]
export const settingsNavigation = [
  { path: '/mcp-settings', label: 'MCP 设置' },
  { path: '/agent-presets', label: 'Skills / Prompt' },
  { path: '/model-settings', label: '模型设置' },
  { path: '/tool-settings', label: '配置中心' },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/agent' },
    { path: '/agent', name: 'agent', alias: ['/chat', '/medical', '/medical-agent'], component: () => import('../views/AgentView.vue') },
    { path: '/workspace', component: () => import('../views/WorkspaceView.vue') },
    { path: '/knowledge-base', component: () => import('../views/KnowledgeView.vue') },
    { path: '/knowledge-chat', name: 'knowledge-chat', component: () => import('../views/AgentView.vue'), props: { knowledgeOnly: true } },
    { path: '/wiki', component: () => import('../views/WikiView.vue') },
    { path: '/agent-runs', component: () => import('../views/RunsView.vue') },
    { path: '/tasks', redirect: to => ({ path: '/agent-runs', query: to.query }) },
    { path: '/mcp-settings', component: () => import('../views/McpView.vue') },
    { path: '/agent-presets', component: () => import('../views/PresetsView.vue') },
    { path: '/model-settings', component: () => import('../views/ModelsView.vue') },
    { path: '/tool-settings', component: () => import('../views/ToolSettingsView.vue') },
    { path: '/health', component: () => import('../views/HealthView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
