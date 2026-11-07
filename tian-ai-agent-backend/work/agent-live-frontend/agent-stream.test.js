import test from 'node:test'
import assert from 'node:assert/strict'
import { applyAgentStreamEvent, createSseParser, newTurnId } from '../src/utils/agentStream.js'

test('SSE supports fragmented CRLF frames, heartbeat comments and Chinese UTF-8 deltas', () => {
  const received = [], parser = createSseParser(data => received.push(JSON.parse(data)))
  const wire = ': heartbeat\r\n\r\nevent: answer_delta\r\ndata: {"type":"answer_delta","delta":"甲乙"}\r\n\r\nevent: done\r\ndata: {"type":"done","status":"COMPLETED"}\r\n\r\n'
  const bytes = new TextEncoder().encode(wire), decoder = new TextDecoder()
  for (let i = 0; i < bytes.length; i++) parser.push(decoder.decode(bytes.slice(i, i + 1), { stream: true }))
  parser.push(decoder.decode()); parser.finish()
  assert.equal(received.length, 2); assert.equal(received[0].delta, '甲乙'); assert.equal(received[1].type, 'done')
})

test('execution updates stay separate from answer and collapse at answer_start', () => {
  const message = { content: '', trace: [], streamPhase: 'analysis' }
  applyAgentStreamEvent(message, { type: 'progress', seq: 1, title: '检索资料' })
  applyAgentStreamEvent(message, { type: 'tool_start', seq: 2, id: 'tool-1', tool: 'search', title: '搜索', status: 'running' })
  assert.equal(message.trace.length, 2); assert.equal(message.content, ''); assert.equal(message.streamPhase, 'analysis')
  applyAgentStreamEvent(message, { type: 'tool_end', seq: 3, id: 'tool-1', title: '搜索', status: 'completed', durationMs: 100 })
  assert.equal(message.trace.length, 2); assert.equal(message.trace[1].durationMs, 100)
  applyAgentStreamEvent(message, { type: 'answer_start', seq: 4 })
  assert.equal(message.streamPhase, 'answer')
  applyAgentStreamEvent(message, { type: 'answer_delta', seq: 5, delta: '甲' })
  applyAgentStreamEvent(message, { type: 'answer_delta', seq: 5, delta: '重复' })
  applyAgentStreamEvent(message, { type: 'answer_delta', seq: 6, delta: '乙' })
  assert.equal(message.content, '甲乙')
  assert.equal(applyAgentStreamEvent(message, { type: 'done', seq: 7, status: 'COMPLETED', answer: '甲乙' }), true)
  assert.equal(message.status, 'COMPLETED'); assert.equal(message.streamPhase, 'completed')
})

test('cancellation retains partial answer and generated turn ids fit MySQL', () => {
  const message = { content: '部分回答', streamPhase: 'answer' }
  applyAgentStreamEvent(message, { type: 'done', seq: 1, status: 'CANCELLED', answer: '部分回答' })
  assert.equal(message.content, '部分回答'); assert.equal(message.incomplete, true)
  for (let i = 0; i < 10; i++) assert.match(newTurnId(), /^[a-zA-Z0-9]{1,32}$/)
})
