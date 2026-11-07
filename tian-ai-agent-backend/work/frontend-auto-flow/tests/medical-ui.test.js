import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createServer as createHttpServer } from 'node:http'
import { createServer } from 'vite'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer } from 'vue'

test('one assistant automatically dispatches, reconnects and cancels server workflows', async t => {
  const fixture = {}
  globalThis.__medicalUi = fixture
  const server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true, hmr: { server: createHttpServer() } },
    plugins: [{
      name: 'automatic-medical-fixture', enforce: 'pre',
      resolveId(source, importer) {
        const medical = importer?.endsWith('/views/Medical.vue')
        const session = importer?.endsWith('/composables/useChatSession.js')
        if (source === '../api' && (medical || session)) return '\0auto-api'
        if (source === '../stores/user' && session) return '\0auto-user'
        if (source === '../components/ChatRoom.vue' && medical) return '\0auto-chat'
      },
      load(id) {
        if (id.endsWith('/views/Medical.vue')) {
          const { descriptor } = parse(readFileSync(id, 'utf8'), { filename: id })
          return compileScript(descriptor, { id: 'automatic-ui-test', inlineTemplate: true }).content
        }
        if (id === '\0auto-user') return 'export const loadCurrentUser=async()=>({userId:"zhangsan"})'
        if (id === '\0auto-chat') return `export default {
          props:['messages','connectionStatus'],
          setup(props,{emit}){globalThis.__medicalUi.chat=props;globalThis.__medicalUi.send=text=>emit('send-message',text);globalThis.__medicalUi.stop=()=>emit('stop-generation');return()=>null}
        }`
        if (id === '\0auto-api') return `
          const f=()=>globalThis.__medicalUi;
          export const getSessionMessages=async()=>({data:f().history||[]});
          export const createSession=async()=>({data:{id:'created'}});
          export const dispatchMedical=async body=>{f().calls.push(body);return {data:f().response}};
          export const getTask=async()=>{f().polls++;return {data:f().polledTask}};
          export const cancelTask=async id=>{f().cancels.push(id);if(f().cancelError)throw new Error('暂时断网');return {data:f().cancelledTask}};
        `
      }
    }]
  })
  try {
    const { default: Medical } = await server.ssrLoadModule('/src/views/Medical.vue')
    const node = () => ({ children: [] })
    const renderer = createRenderer({
      createElement: node, createText: node, createComment: node,
      setText: () => {}, setElementText: () => {}, patchProp: () => {},
      insert: (el, parent) => { parent.children.push(el); el.parent = parent },
      remove: el => { if (el.parent) el.parent.children = el.parent.children.filter(item => item !== el) },
      parentNode: el => el.parent, nextSibling: () => null
    })
    const settle = () => new Promise(resolve => setImmediate(resolve))
    const mount = async (type = 'agent') => {
      fixture.calls = []; fixture.cancels = []; fixture.polls = 0; fixture.cancelError = false
      const app = renderer.createApp(Medical, { chatIdProp: 'one_chat', sessionTypeProp: type })
      app.mount(node())
      await settle()
      return app
    }
    const result = { executionResult: '检索和比较后的完整报告', plan: { steps: [{ id: 'kb', description: '检索证据' }, { id: 'report', description: '整理报告', dependsOn: ['kb'] }] }, review: { passed: true, requiresHumanReview: true } }
    const completed = { id: 'task', status: 'COMPLETED', progress: 100, stage: '完成', result }
    const running = { id: 'task', status: 'RUNNING', progress: 30, stage: '正在检索证据' }
    await t.test('a simple question uses the same automatic entry without mode selection', async () => {
      fixture.response = { mode: 'REACT', answer: '健康信息回答' }
      const app = await mount()
      try {
        fixture.send('健康问题')
        await settle()
        assert.deepEqual(fixture.calls, [{ message: '健康问题', chatId: 'one_chat', sessionType: 'agent' }])
        assert.equal(fixture.chat.messages.at(-1).content, '健康信息回答')
        assert.equal(fixture.chat.messages.at(-1).status, 'COMPLETED')
      } finally { app.unmount() }
    })
    await t.test('a composite goal returns one answer with its dependency plan and review', async () => {
      fixture.response = { mode: 'WORKFLOW', task: completed }
      const app = await mount('medical')
      try {
        fixture.send('先检索证据再比较并生成报告')
        await settle()
        const reply = fixture.chat.messages.at(-1)
        assert.equal(reply.content, result.executionResult)
        assert.equal(reply.workflow.plan.steps[1].dependsOn[0], 'kb')
        assert.equal(reply.workflowReview.requiresHumanReview, true)
        assert.equal(fixture.calls[0].sessionType, 'medical')
      } finally { app.unmount() }
    })
    await t.test('refresh reconnects a persisted running task without submitting it again', async () => {
      fixture.history = [{ messageType: 'ASSISTANT', status: 'RUNNING', text: '', metadata: { taskId: 'task' } }]
      fixture.polledTask = completed
      const app = await mount()
      try {
        await settle()
        assert.equal(fixture.polls, 1)
        assert.equal(fixture.calls.length, 0)
        assert.equal(fixture.chat.messages.at(-1).content, result.executionResult)
      } finally { app.unmount(); fixture.history = [] }
    })
    await t.test('stop cancels the server task and preserves the confirmed cancelled state', async () => {
      fixture.response = { mode: 'WORKFLOW', task: running }
      fixture.cancelledTask = { id: 'task', status: 'CANCELLED', stage: '已取消' }
      const app = await mount()
      try {
        fixture.send('检索并报告')
        await settle()
        assert.equal(fixture.chat.messages.at(-1).workflowProgress.stage, '正在检索证据')
        fixture.stop()
        await settle()
        assert.deepEqual(fixture.cancels, ['task'])
        assert.equal(fixture.chat.messages.at(-1).status, 'CANCELLED')
        assert.equal(fixture.chat.connectionStatus, 'disconnected')
      } finally { app.unmount() }
    })
    await t.test('a cancellation failure does not pretend that background work stopped', async () => {
      fixture.response = { mode: 'WORKFLOW', task: running }
      const app = await mount()
      try {
        fixture.cancelError = true
        fixture.send('检索并报告')
        await settle()
        fixture.stop()
        await settle()
        assert.equal(fixture.chat.messages.at(-1).status, 'RUNNING')
        assert.match(fixture.chat.messages.at(-1).trackingError, /停止任务失败/)
      } finally { app.unmount() }
    })
    await t.test('a result that completed during cancellation remains completed', async () => {
      fixture.response = { mode: 'WORKFLOW', task: running }
      fixture.cancelledTask = completed
      const app = await mount()
      try {
        fixture.send('检索并报告')
        await settle()
        fixture.stop()
        await settle()
        assert.equal(fixture.chat.messages.at(-1).status, 'COMPLETED')
        assert.equal(fixture.chat.messages.at(-1).content, result.executionResult)
      } finally { app.unmount() }
    })
  } finally {
    delete globalThis.__medicalUi
    await server.close()
  }
})
