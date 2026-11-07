export const medicalRoutes = [
  { path: '/medical', name: 'Medical', component: () => import('../views/Medical.vue'), meta: { title: '医疗助手 - 天医' } },
  // Old mode links now use the same automatic orchestration as every other question.
  { path: '/medical-agent', redirect: to => {
    const { mode, ...query } = to.query
    return { path: '/medical', query, hash: to.hash }
  } }
]
