import test from 'node:test'
import assert from 'node:assert/strict'
import { createMemoryHistory, createRouter } from 'vue-router'
import { medicalRoutes } from '../src/router/medicalRoutes.js'

test('legacy agent URLs open the automatic assistant without a forced mode', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: medicalRoutes.map(route => route.component ? { ...route, component: { render: () => null } } : route)
  })
  await router.push('/medical-agent?mode=agent&from=bookmark#answer')
  assert.equal(router.currentRoute.value.path, '/medical')
  assert.equal(router.currentRoute.value.query.mode, undefined)
  assert.equal(router.currentRoute.value.query.from, 'bookmark')
  assert.equal(router.currentRoute.value.hash, '#answer')
  await router.push('/medical?mode=rag')
  assert.equal(router.currentRoute.value.query.mode, 'rag')
  assert.equal(router.currentRoute.value.name, 'Medical')
})
