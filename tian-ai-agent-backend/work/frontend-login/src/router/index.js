import { createRouter, createWebHistory } from 'vue-router'
import { clearCurrentUser, loadCurrentUser } from '../stores/user'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { title: '登录 - 天医', public: true } },
  { path: '/', name: 'Home', component: () => import('../views/Home.vue'), meta: { title: '天医 - 循证医疗信息与安全支持智能体' } },
  { path: '/medical', name: 'Medical', component: () => import('../views/Medical.vue'), meta: { title: '医疗信息助手 - 天医' } },
  { path: '/medical-agent', name: 'MedicalAgent', component: () => import('../views/MedicalAgent.vue'), meta: { title: '深度循证分析 - 天医' } },
  { path: '/knowledge-base', name: 'KnowledgeBase', component: () => import('../views/KnowledgeBase.vue'), meta: { title: '医疗知识库 - 天医' } },
  { path: '/tasks', name: 'Tasks', component: () => import('../views/MedicalTasks.vue'), meta: { title: '任务与人工审核 - 天医' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
router.beforeEach(async to => {
  if (to.meta.title) document.title = to.meta.title
  if (to.meta.public) return true
  try {
    await loadCurrentUser(true)
    return true
  } catch {
    clearCurrentUser()
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
})
window.addEventListener('auth-required', () => {
  clearCurrentUser()
  if (router.currentRoute.value.name !== 'Login') {
    router.replace({ name: 'Login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})
export default router
