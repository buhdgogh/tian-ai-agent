import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { computed, createRenderer, nextTick, reactive } from 'vue'

test('session lifecycle creates records first, isolates history, and rejects stale responses', async t => {
  const server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true },
    plugins: [{
      name: 'session-api-fixture',
      enforce: 'pre',
      resolveId(source, importer) {
        if (!importer?.endsWith('/composables/useChatSession.js')) return
        if (source === '../api') return '\0fixture-api'
        if (source === '../stores/user') return '\0fixture-user'
      },
      load(id) {
        if (id === '\0fixture-api') return 'export const createSession = (...args) => globalThis.__chatApi.create(...args); export const getSessionMessages = (...args) => globalThis.__chatApi.history(...args)'
        if (id === '\0fixture-user') return 'export const loadCurrentUser = () => Promise.resolve({userId:"zhangsan"})'
      }
    }]
  })
  try {
    const { useChatSession } = await server.ssrLoadModule('/src/composables/useChatSession.js')
    const renderer = createRenderer({
      createComment: () => ({}), insert: () => {}, remove: () => {}, parentNode: () => null, nextSibling: () => null
    })
    const mount = (initialId = '') => {
      const props = reactive({ chatIdProp: initialId, type: 'medical' }), events = []
      let state
      const app = renderer.createApp({ setup() {
        state = useChatSession(props, (name, id) => {
          events.push([name, id])
          if (name === 'update-chat-id') props.chatIdProp = id
        }, computed(() => props.type))
        return () => null
      } })
      app.mount({})
      return { props, state, events, unmount: () => app.unmount() }
    }
    await t.test('first send creates a database session before emitting its id', async () => {
      let created = false, historyCalls = 0
      globalThis.__chatApi = {
        create: async body => { assert.equal(body.type, 'medical'); created = true; return { data: { id: 'db_chat' } } },
        history: async () => { historyCalls++; return { data: [] } }
      }
      const view = mount()
      const run = view.state.begin()
      view.state.messages.value.push({ content: '保留本轮', isUser: true })
      assert.equal(await run.ensureId(), 'db_chat')
      await nextTick()
      assert.ok(created)
      assert.equal(historyCalls, 0)
      assert.equal(run.active(), true)
      assert.equal(view.state.messages.value.length, 1)
      run.finish()
      view.unmount()
    })
    await t.test('switching medical to rag requests the correct history and discards late replies', async () => {
      let resolveOld
      const calls = []
      globalThis.__chatApi = { history: (id, type) => {
        calls.push(type)
        if (type === 'medical') return new Promise(resolve => { resolveOld = resolve })
        return Promise.resolve({ data: [{ messageType: 'ASSISTANT', text: 'RAG历史' }] })
      } }
      const view = mount('same_id')
      view.props.type = 'rag'
      await nextTick()
      await new Promise(resolve => setImmediate(resolve))
      resolveOld({ data: [{ messageType: 'ASSISTANT', text: '过期历史' }] })
      await new Promise(resolve => setImmediate(resolve))
      assert.deepEqual(calls, ['medical', 'rag'])
      assert.equal(view.state.messages.value[0].content, 'RAG历史')
      view.unmount()
    })
    await t.test('stop aborts the active request and keeps the cancelled reply', async () => {
      const view = mount()
      const run = view.state.begin()
      view.state.messages.value.push({ isUser: false, content: '部分回复', status: 'RUNNING' })
      view.state.stop()
      assert.equal(run.signal.aborted, true)
      assert.equal(run.active(), false)
      assert.equal(view.state.messages.value[0].status, 'CANCELLED')
      run.finish()
      assert.equal(view.state.status.value, 'disconnected')
      view.unmount()
    })
    await t.test('unmount aborts work and suppresses late history updates', async () => {
      const view = mount()
      const run = view.state.begin()
      view.unmount()
      run.finish()
      assert.equal(run.signal.aborted, true)
      assert.equal(view.events.length, 0)
    })
  } finally {
    delete globalThis.__chatApi
    await server.close()
  }
})
