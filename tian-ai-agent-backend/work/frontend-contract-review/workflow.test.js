import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTaskSnapshot, taskWorkflow, taskAnswer, taskStepState, mergeAgentTasks } from '../src/utils/workflow.js'
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

test('response task exposes nested plan, review and final answer without execution payloads', () => {
  const task = { id: 'response', type: 'AGENT_RESPONSE', status: 'COMPLETED', result: JSON.stringify({
    answer: 'final answer', workflow: { plan: { steps: [{ id: 'search', status: 'COMPLETED' }] }, review: { passed: true } }
  }) }
  assert.equal(taskAnswer(task), 'final answer')
  assert.equal(taskWorkflow(task).review.passed, true)
  assert.equal(taskStepState(task, 0), 'done')
  const message = { error: 'connection lost', trackingError: 'retry', incomplete: true, workflowProgress: { progress: 95 }, metadata: { workflowProgress: { progress: 95 } } }
  assert.equal(applyTaskSnapshot(message, task), false)
  assert.equal(message.content, 'final answer')
  assert.equal(message.workflowReview.passed, true)
  assert.equal(message.streamPhase, 'completed')
  for (const key of ['error', 'trackingError', 'incomplete', 'workflowProgress']) assert.equal(message[key], undefined)
  assert.equal(message.metadata.workflowProgress, undefined)
})

test('task list merges both agent types, deduplicates and sorts newest first', () => {
  const response = { id: 'new', type: 'AGENT_RESPONSE', createdAt: '2026-11-05T10:00:00Z' }
  const legacy = { id: 'old', type: 'AGENT_WORKFLOW', createdAt: '2026-11-04T10:00:00Z', result: { executionResult: 'legacy answer' } }
  assert.deepEqual(mergeAgentTasks([{ data: [response] }, { data: { items: [legacy, response] } }]).map(task => task.id), ['new', 'old'])
  assert.equal(taskAnswer(legacy), 'legacy answer')
})

test('terminal snapshots retain partial answer and stop showing active progress', () => {
  for (const status of ['FAILED', 'CANCELLED']) {
    const message = { content: 'partial answer', workflowProgress: { progress: 95 }, metadata: { workflowProgress: { progress: 95 } } }
    applyTaskSnapshot(message, { id: 'response', status })
    assert.equal(message.content, 'partial answer')
    assert.equal(message.streamPhase, 'stopped')
    assert.equal(message.workflowProgress, undefined)
    assert.equal(message.metadata.workflowProgress, undefined)
  }
})

test('explicit failed and skipped step states are not presented as completed', () => {
  const task = { status: 'COMPLETED', result: { workflow: { plan: { steps: [{ status: 'FAILED' }, { status: 'SKIPPED' }] } } } }
  assert.equal(taskStepState(task, 0), 'failed')
  assert.equal(taskStepState(task, 1), 'stopped')
})
