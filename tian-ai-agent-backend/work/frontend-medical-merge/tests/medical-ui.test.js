import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createServer as createHttpServer } from 'node:http'
import { createServer } from 'vite'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, reactive } from 'vue'

test('merged assistant dispatches all four modes and restores agent history', async () => {
  const fixture = { calls: [], histories: [], agentError: false }
  globalThis.__medicalUi = fixture
  const server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true, hmr: { server: createHttpServer() } },
    plugins: [{
      name: 'medical-ui-fixture', enforce: 'pre',
      resolveId(source, importer) {
        const medical = importer?.endsWith('/views/Medical.vue')
        const session = importer?.endsWith('/composables/useChatSession.js')
        if (source === '../api' && (medical || session)) return '\0medical-api'
        if (source === '../stores/user' && session) return '\0medical-user'
        if (source === '../components/ChatRoom.vue' && medical) return '\0medical-chat'
      },
      load(id) {
        if (id.endsWith('/views/Medical.vue')) {
          const { descriptor } = parse(readFileSync(id, 'utf8'), { filename: id })
          return compileScript(descriptor, { id: 'medical-ui-test', inlineTemplate: true }).content
        }
        if (id === '\0medical-user') return 'export const loadCurrentUser=async()=>({userId:"zhangsan"})'
        if (id === '\0medical-chat') return `export default {
          props:['messages','connectionStatus','aiType','title','suggestions'],
          setup(props,{emit}){globalThis.__medicalUi.chat=props;globalThis.__medicalUi.send=text=>emit('send-message',text);return()=>null}
        }`
        if (id === '\0medical-api') return `
          const f=()=>globalThis.__medicalUi;
          export const getSessionMessages=async(id,type)=>{
            f().histories.push(type);
            return {data:type==='agent'?[{messageType:'ASSISTANT',status:'COMPLETED',text:'🤔 思考：内部规划\\n🛠️ 正在使用工具 searchPubMed 获取信息\\n🤔 思考：历史分析结论\\n🛠️ 正在使用工具 doTerminate 获取信息'}]:[]};
          };
          export const createSession=async()=>({data:{id:'created'}});
          export const streamMedicalAssistant=async(body,chunk)=>{f().calls.push(['chat',body]);chunk('普通回答')};
          export const chatMedicalRag=async body=>{f().calls.push(['rag',body]);return {data:{answer:'检索回答',sources:[{title:'证据'}]}}};
          export const createMedicalEvidenceReport=async body=>{f().calls.push(['report',body]);return {data:{summary:'报告摘要',nextSteps:['复查']}}};
          export const streamMedicalAgent=async(body,chunk)=>{
            f().calls.push(['agent',body]);
            if(f().agentError){chunk('执行错误: 工具异常');return}
            chunk('🤔 思考：内部规划\\n🛠️ 正在使用工具 searchPubMed 获取信息');
            chunk('🤔 思考：深度分析结论\\n🛠️ 正在使用工具 doTerminate 获取信息');
          };
        `
      }
    }]
  })
  let app
  try {
    const { default: Medical } = await server.ssrLoadModule('/src/views/Medical.vue')
    const state = reactive({ modeProp: 'chat', chatIdProp: 'existing_chat' })
    const node = () => ({ children: [] })
    const renderer = createRenderer({
      createElement: node, createText: node, createComment: node,
      setText: () => {}, setElementText: () => {}, patchProp: () => {},
      insert: (el, parent) => { parent.children.push(el); el.parent = parent },
      remove: el => { if (el.parent) el.parent.children = el.parent.children.filter(item => item !== el) },
      parentNode: el => el.parent, nextSibling: () => null
    })
    app = renderer.createApp({ setup: () => () => h(Medical, { ...state }) })
    app.mount(node())
    const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
    await settle()
    for (const mode of ['chat', 'rag', 'report', 'agent']) {
      state.modeProp = mode
      state.chatIdProp = `existing_${mode}`
      await settle()
      assert.equal(fixture.chat.connectionStatus, 'disconnected')
      if (mode === 'agent') {
        assert.equal(fixture.chat.messages[0].content, '历史分析结论')
        assert.ok(fixture.chat.messages[0].trace.some(step => step.tool === 'searchPubMed'))
      }
      fixture.send('健康问题')
      await settle()
      const [actualMode, body] = fixture.calls.at(-1)
      assert.equal(actualMode, mode)
      assert.equal(body.chatId, `existing_${mode}`)
      const reply = fixture.chat.messages.at(-1)
      assert.equal(reply.status, 'COMPLETED')
      if (mode === 'agent') {
        assert.equal(reply.content, '深度分析结论')
        assert.ok(reply.trace.some(step => step.tool === 'searchPubMed'))
        assert.equal(fixture.chat.aiType, 'agent')
      }
      if (mode === 'rag') assert.equal(reply.sources[0].title, '证据')
      if (mode === 'report') assert.equal(reply.report.summary, '报告摘要')
    }
    fixture.agentError = true
    fixture.send('另一个问题')
    await settle()
    assert.equal(fixture.chat.messages.at(-1).status, 'FAILED')
    // Switching back must use the medical store and stop treating plain text as Agent steps.
    state.modeProp = 'chat'
    await settle()
    assert.equal(fixture.histories.at(-1), 'medical')
    assert.equal(fixture.chat.aiType, 'medical')
  } finally {
    app?.unmount()
    delete globalThis.__medicalUi
    await server.close()
  }
})
