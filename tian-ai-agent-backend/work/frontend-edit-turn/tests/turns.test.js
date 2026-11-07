import test from 'node:test'
import assert from 'node:assert/strict'
import { editableLastQuestion } from '../src/utils/turns.js'

test('only the latest stopped or failed question can be edited', () => {
  const old = [{ isUser: true, turnId: 'old', content: '旧问题' }, { isUser: false, turnId: 'old', status: 'CANCELLED' }]
  assert.equal(editableLastQuestion(old).turnId, 'old')
  for (const status of ['RUNNING', 'PENDING', 'COMPLETED']) {
    assert.equal(editableLastQuestion([...old, { isUser: true, turnId: 'new' }, { isUser: false, turnId: 'new', status }]), null)
  }
  assert.equal(editableLastQuestion([...old, { isUser: true, turnId: 'new' }, { isUser: false, turnId: 'new', status: 'FAILED' }]).turnId, 'new')
})
