import test from 'node:test'
import assert from 'node:assert/strict'
import { apiError, normalizeHistory, parseAgentHistory, sessionTypeForMode } from '../src/utils/medical.js'

test('RAG has separate history while chat and report share medical sessions', () => {
  assert.equal(sessionTypeForMode('rag'), 'rag')
  assert.equal(sessionTypeForMode('chat'), 'medical')
  assert.equal(sessionTypeForMode('report'), 'medical')
})
test('persisted pending and failed messages remain visible without content', () => {
  const result = normalizeHistory([
    { id: 1, messageType: 'USER', text: '问题', status: 'COMPLETED', turnId: 'turn' },
    { id: 2, messageType: 'ASSISTANT', text: '', status: 'RUNNING', turnId: 'turn' },
    { id: 3, messageType: 'ASSISTANT', text: '', status: 'FAILED', metadata: { error: '数据库异常' } },
    { id: 4, messageType: 'TOOL', text: '内部结果' }
  ])
  assert.equal(result.length, 3)
  assert.equal(result[0].isUser, true)
  assert.equal(result[1].status, 'RUNNING')
  assert.equal(result[1].turnId, 'turn')
  assert.equal(result[2].incomplete, true)
  assert.equal(result[2].error, '数据库异常')
})
test('agent history reconstructs tool path and only displays final answer', () => {
  const raw = 'Step 1: 🤔 思考：内部规划内容\n🛠️ 正在使用工具 searchPubMed 获取信息\n\nStep 2: 🤔 思考：最终回答\n🛠️ 正在使用工具 doTerminate 获取信息'
  const parsed = parseAgentHistory(raw)
  assert.equal(parsed.content, '最终回答')
  assert.ok(parsed.trace.some(step => step.tool === 'searchPubMed'))
  assert.ok(!parsed.content.includes('内部规划'))
  const [message] = normalizeHistory([{ messageType: 'ASSISTANT', text: raw, status: 'COMPLETED' }], { agent: true })
  assert.equal(message.content, '最终回答')
})
test('agent error events remain failures', () => {
  const parsed = parseAgentHistory('❌ 错误：检索失败')
  assert.equal(parsed.trace[0].status, 'failed')
  assert.match(parsed.content, /检索失败/)
})
test('structured report and workflow review survive history conversion', () => {
  const [report] = normalizeHistory([{ messageType: 'ASSISTANT', text: JSON.stringify({ summary: '摘要', nextSteps: ['复查'] }) }])
  assert.equal(report.report.summary, '摘要')
  const [workflow] = normalizeHistory([{ messageType: 'ASSISTANT', text: '结果', metadata: { workflow: { review: { requiresHumanReview: true } } } }])
  assert.equal(workflow.workflowReview.requiresHumanReview, true)
})
test('backend error strings and identity errors have usable messages', () => {
  assert.match(apiError({ response: { status: 400, data: { error: '缺少会话' } } }), /缺少会话/)
  assert.match(apiError({ status: 403 }), /当前用户/)
  assert.match(apiError({ status: 404 }), /不存在/)
})
