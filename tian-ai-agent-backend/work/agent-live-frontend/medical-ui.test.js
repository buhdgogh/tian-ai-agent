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
          setup(props,{emit}){globalThis.__medicalUi.chat=props;globalThis.__medicalUi.send=text=>emit('send-message',text);globalThis.__medicalUi.stop=()=>emit('stop-generation');globalThis.__medicalUi.edit=body=>emit('edit-message',body);return()=>null}
        }`
        if (id === '\0auto-api') return `
          const f=()=>globalThis.__medicalUi;
          export const getSessionMessages=async()=>({data:f().history||[]});
          export const createSession=async()=>({data:{id:'created'}});
          export const dispatchMedical=async body=>{f().calls.push(body);if(f().dispatchError)throw f().dispatchError;return {data:f().dispatchReply?await f().dispatchReply(body):f().response}};
          export const streamAgentResponse=async(body,onEvent,signal)=>{
            f().calls.push(body);if(f().dispatchError)throw f().dispatchError;
            if(f().streamReply)return f().streamReply(body,onEvent,signal);
            const response=f().dispatchReply?await f().dispatchReply(body):f().response;
            if(signal.aborted)return {sawDone:false};
            let seq=0;const push=event=>onEvent({...event,seq:++seq});
            push({type:'progress',title:'分析问题',detail:'正在核对证据'});
            if(response.mode==='WORKFLOW'){
              const task=response.task;push({type:'task',taskId:task.id});
              push({type:'progress',title:task.stage,stage:task.stage,progress:task.progress});
              if(task.status!=='COMPLETED')return new Promise(resolve=>signal.addEventListener('abort',()=>resolve({sawDone:false}),{once:true}));
              const workflow=task.result;push({type:'review',review:workflow.review,plan:workflow.plan});
              push({type:'answer_start'});push({type:'answer_delta',delta:workflow.executionResult});
              push({type:'done',status:'COMPLETED',answer:workflow.executionResult,workflow});
            }else{
              push({type:'answer_start'});push({type:'answer_delta',delta:response.answer||''});
              push({type:'done',status:'COMPLETED',answer:response.answer||''});
            }
            return {sawDone:true};
          };
          export const getTask=async()=>{f().polls++;return {data:f().polledTask}};
          export const interruptConversationTurn=async(id,turnId)=>{f().cancels.push(turnId);if(f().cancelError)throw new Error('暂时断网');return {data:f().cancelledTask}};
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
      fixture.calls = []; fixture.cancels = []; fixture.polls = 0; fixture.cancelError = false; fixture.dispatchReply = null; fixture.dispatchError = null; fixture.streamReply = null
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
        assert.equal(fixture.calls[0].message, '健康问题')
        assert.equal(fixture.calls[0].chatId, 'one_chat')
        assert.equal(fixture.calls[0].sessionType, 'agent')
        assert.match(fixture.calls[0].turnId, /^[a-zA-Z0-9]{1,32}$/)
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
    await t.test('SSE shows execution immediately and appends answer text before completion', async () => {
      const app = await mount(); let push, finish
      try {
        fixture.streamReply = (body,onEvent,signal) => new Promise(resolve => { push=onEvent; finish=resolve; signal.addEventListener('abort',()=>resolve({sawDone:false}),{once:true}) })
        fixture.send('检索问题'); await settle()
        push({type:'progress',seq:1,title:'检索资料'}); await settle()
        const reply=fixture.chat.messages.at(-1)
        assert.equal(reply.trace[0].title,'检索资料'); assert.equal(reply.content,''); assert.equal(reply.streamPhase,'analysis')
        push({type:'answer_start',seq:2}); push({type:'answer_delta',seq:3,delta:'第一段'}); await settle()
        assert.equal(reply.streamPhase,'answer'); assert.equal(reply.content,'第一段'); assert.equal(fixture.chat.connectionStatus,'connecting')
        push({type:'answer_delta',seq:4,delta:'第二段'}); push({type:'done',seq:5,status:'COMPLETED',answer:'第一段第二段'}); finish({sawDone:true}); await settle()
        assert.equal(reply.content,'第一段第二段'); assert.equal(reply.status,'COMPLETED')
      } finally { finish?.({sawDone:false}); app.unmount() }
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
        assert.deepEqual(fixture.cancels, [fixture.calls[0].turnId])
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
        assert.match(fixture.chat.messages.at(-1).trackingError, /停止回答失败/)
      } finally { app.unmount() }
    })
    await t.test('a result that completed during cancellation remains completed', async () => {
      fixture.response = { mode: 'WORKFLOW', task: running }
      fixture.cancelledTask = completed
      const app = await mount()
      try {
        fixture.send('检索并报告')
        await settle()
        fixture.history = [{messageType:'USER',turnId:fixture.calls[0].turnId,text:'检索并报告'}, {messageType:'ASSISTANT',turnId:fixture.calls[0].turnId,text:result.executionResult,status:'COMPLETED'}]
        fixture.stop()
        await settle()
        assert.equal(fixture.chat.messages.at(-1).status, 'COMPLETED')
        assert.equal(fixture.chat.messages.at(-1).content, result.executionResult)
      } finally { app.unmount(); fixture.history = [] }
    })
    await t.test('a stopped question can be edited while an old response is still returning', async () => {
      let resolveOld
      fixture.cancelledTask = {status:'CANCELLED'}
      const app = await mount()
      try {
        fixture.dispatchReply = async body => {
          if (body.message === '旧问题') return new Promise(resolve => {resolveOld=resolve})
          return {mode:'REACT',answer:'新问题的回答'}
        }
        fixture.send('旧问题')
        await settle()
        const originalTurnId = fixture.calls[0].turnId
        fixture.stop()
        await settle()
        assert.equal(fixture.chat.messages.at(-1).status, 'CANCELLED')
        fixture.edit({turnId:originalTurnId,text:'修改后的新问题'})
        await settle()
        assert.equal(fixture.calls[1].replacesTurnId, originalTurnId)
        assert.notEqual(fixture.calls[1].turnId, originalTurnId)
        assert.equal(fixture.chat.messages[0].content, '修改后的新问题')
        assert.equal(fixture.chat.messages.length, 2)
        assert.equal(fixture.chat.messages.at(-1).content, '新问题的回答')
        resolveOld({mode:'REACT',answer:'迟到的旧答案'})
        await settle()
        assert.equal(fixture.chat.messages.at(-1).content, '新问题的回答')
      } finally { resolveOld?.({mode:'INTERRUPTED'}); app.unmount() }
    })
    await t.test('a rejected revision keeps the original question visible', async () => {
      fixture.history = [{messageType:'USER',turnId:'old',text:'原问题'}, {messageType:'ASSISTANT',turnId:'old',text:'',status:'CANCELLED'}]
      const app = await mount()
      try {
        fixture.dispatchError = {response:{status:409}}
        fixture.edit({turnId:'old',text:'修改后的问题'})
        await settle()
        assert.equal(fixture.chat.messages[0].content, '原问题')
        assert.equal(fixture.chat.messages.at(-1).status, 'CANCELLED')
        assert.equal(fixture.chat.messages.length, 2)
      } finally { app.unmount(); fixture.history=[] }
    })
    await t.test('completed answers cannot be edited through the stopped-question action', async () => {
      fixture.history = [{messageType:'USER',turnId:'old',text:'原问题'}, {messageType:'ASSISTANT',turnId:'old',text:'完成答案',status:'COMPLETED'}]
      const app = await mount()
      try {
        fixture.edit({turnId:'old',text:'修改后的问题'})
        await settle()
        assert.equal(fixture.calls.length, 0)
      } finally { app.unmount(); fixture.history=[] }
    })
  } finally {
    delete globalThis.__medicalUi
    await server.close()
  }
})
