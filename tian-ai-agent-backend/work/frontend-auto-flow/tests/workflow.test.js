import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTaskSnapshot } from '../src/utils/workflow.js'
import { normalizeHistory } from '../src/utils/medical.js'

test('pending tasks expose progress without claiming completion', () => {
  const message = { content: '' }
  assert.equal(applyTaskSnapshot(message, { id: 'task', status: 'RUNNING', progress: 30, stage: '检索证据' }), true)
  assert.equal(message.content, '')
  assert.equal(message.workflowProgress.stage, '检索证据')
  assert.equal(message.metadata.taskId, 'task')
})
test('completed tasks combine final answer, plan and review into the same reply', () => {
  const message = {}
  const result = { executionResult: '完整报告', plan: { steps: [{ id: 'retrieve' }, { id: 'report', dependsOn: ['retrieve'] }] }, review: { passed: false, requiresHumanReview: true } }
  assert.equal(applyTaskSnapshot(message, { id: 'task', status: 'COMPLETED', progress: 100, stage: '完成', result: JSON.stringify(result) }), false)
  assert.equal(message.content, '完整报告')
  assert.equal(message.workflow.plan.steps[1].dependsOn[0], 'retrieve')
  assert.equal(message.workflowReview.requiresHumanReview, true)
})
test('failed and cancelled tasks preserve their actual status', () => {
  const message = {}
  assert.equal(applyTaskSnapshot(message, { id: 'task', status: 'FAILED', error: '检索服务异常' }), false)
  assert.equal(message.error, '检索服务异常')
  assert.equal(message.status, 'FAILED')
  applyTaskSnapshot(message, { id: 'task', status: 'CANCELLED' })
  assert.equal(message.status, 'CANCELLED')
})
test('history retains task id, progress and review for reconnect', () => {
  const [reply] = normalizeHistory([{ messageType: 'ASSISTANT', status: 'RUNNING', text: '', metadata: { taskId: 'task', workflowProgress: { progress: 45, stage: '比较证据' } } }])
  assert.equal(reply.metadata.taskId, 'task')
  assert.equal(reply.workflowProgress.stage, '比较证据')
})
