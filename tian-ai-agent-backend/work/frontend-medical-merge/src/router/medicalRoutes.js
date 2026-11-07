export const medicalRoutes = [
  { path: '/medical', name: 'Medical', component: () => import('../views/Medical.vue'), meta: { title: '医疗助手 - 天医' } },
  // Keep existing bookmarks while using one chat page and the original agent sessions.
  { path: '/medical-agent', redirect: to => ({ path: '/medical', query: { ...to.query, mode: 'agent' }, hash: to.hash }) }
]
