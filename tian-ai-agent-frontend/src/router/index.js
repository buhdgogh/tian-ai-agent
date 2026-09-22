import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Home', component: () => import('../views/Home.vue'), meta: { title: '天医 - 循证医疗信息与安全支持智能体' } },
  { path: '/medical', name: 'Medical', component: () => import('../views/Medical.vue'), meta: { title: '医疗信息助手 - 天医' } },
  { path: '/medical-agent', name: 'MedicalAgent', component: () => import('../views/MedicalAgent.vue'), meta: { title: '深度循证分析 - 天医' } },
  { path: '/knowledge-base', name: 'KnowledgeBase', component: () => import('../views/KnowledgeBase.vue'), meta: { title: '医疗知识库 - 天医' } },
  { path: '/tasks', name: 'Tasks', component: () => import('../views/MedicalTasks.vue'), meta: { title: '任务与人工审核 - 天医' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
router.beforeEach(to => { if (to.meta.title) document.title = to.meta.title })
export default router
