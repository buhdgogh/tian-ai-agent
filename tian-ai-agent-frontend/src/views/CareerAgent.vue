<template>
  <div class="agent-page">
    <header class="page-header">
      <button @click="router.push('/')">← 返回</button>
      <div><h1>学业就业规划 Agent</h1><p>分析 · 执行 · 复核</p></div>
      <span>{{ chatId }}</span>
    </header>
    <main class="chat-area">
      <section v-if="processSteps.length" class="process-panel">
        <header><strong>Agent 执行过程</strong><span>{{ connectionStatus === 'connecting' ? '执行中' : '已完成' }} · {{ processSteps.length }} 步</span></header>
        <div class="process-list"><article v-for="(step,index) in processSteps" :key="index"><span>{{ index + 1 }}</span><div v-html="renderMarkdown(step)"></div></article></div>
      </section>
      <ChatRoom ref="chatRoomRef" :messages="messages" :connection-status="connectionStatus" ai-type="agent" theme-color="#315fdb" theme-color-rgb="49, 95, 219" title="学业就业规划 Agent" subtitle="适合需要多步分析、工具执行与复核的复杂任务" welcome-icon="🧭" :suggestions="suggestions" placeholder="描述你的学业或求职背景、目标、期限与约束，Agent 将分步处理…" @send-message="sendMessage" />
    </main>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithCareerAgent, getSessionMessages } from '../api'

const props = defineProps({ chatIdProp: String })
const emit = defineEmits(['update-history', 'update-chat-id'])
const router = useRouter()
const messages = ref([])
const processSteps = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
const chatRoomRef = ref(null)
let eventSource = null
const suggestions = ['分析我的课程与升学目标之间的差距', '制定未来六个月的求职行动路线', '帮我完成岗位分析并安排每周任务']

const generateChatId = () => {
  const token = crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const id = `agent_${token}`
  chatId.value = id
  emit('update-chat-id', id)
}
const closeStream = () => { if (eventSource) eventSource.close(); eventSource = null }
const addMessage = (content, isUser) => messages.value.push({ content, isUser, time: Date.now() })
const loadMessages = async id => {
  closeStream(); connectionStatus.value = 'disconnected'; processSteps.value = []; chatRoomRef.value?.clearInput()
  try {
    const { data } = await getSessionMessages(id, 'agent')
    messages.value = (Array.isArray(data) ? data : []).map(item => ({ content: item.content || item.text || '', isUser: ['USER', 'user'].includes(item.messageType || item.role), time: Date.now() }))
  } catch (error) { console.error('加载 Agent 会话失败:', error); messages.value = [] }
}
watch(() => props.chatIdProp, value => {
  if (value && value !== chatId.value) { chatId.value = value; loadMessages(value) }
  else if (!value) { closeStream(); chatId.value = ''; messages.value = []; processSteps.value = [] }
}, { immediate: true })

const sendMessage = message => {
  if (connectionStatus.value === 'connecting') return
  if (!chatId.value) generateChatId()
  addMessage(message, true)
  processSteps.value = []
  connectionStatus.value = 'connecting'
  let received = false
  let completed = false
  eventSource = chatWithCareerAgent(message, chatId.value)
  eventSource.onmessage = event => {
    if (event.data === '[DONE]') {
      completed = true
      if (processSteps.value.length) addMessage(processSteps.value.join('\n\n---\n\n'), false)
      return finish()
    }
    if (event.data) { received = true; processSteps.value.push(event.data) }
  }
  eventSource.onerror = () => {
    if (completed) return
    if (!received) addMessage('连接学业就业规划 Agent 失败，请确认后端服务已启动。', false)
    else addMessage(processSteps.value.join('\n\n---\n\n'), false)
    finish()
  }
}
const renderMarkdown = content => DOMPurify.sanitize(marked.parse(String(content || ''), { gfm: true, breaks: true, async: false }), { USE_PROFILES: { html: true } })
const finish = () => { connectionStatus.value = 'disconnected'; closeStream(); emit('update-history') }
onMounted(() => { if (!chatId.value) generateChatId() })
onBeforeUnmount(closeStream)
</script>

<style scoped>
.agent-page{height:100vh;display:flex;flex-direction:column;background:#f6f8fc}.page-header{height:76px;display:grid;grid-template-columns:1fr auto 1fr;align-items:center;padding:0 24px;background:#ffffffdf;border-bottom:1px solid #e2e8f0}.page-header button{justify-self:start;border:0;background:transparent;color:#64748b;font:inherit}.page-header>div{text-align:center}.page-header h1{font-size:20px;color:#315fdb}.page-header p{font-size:11px;color:#94a3b8;margin-top:4px;letter-spacing:1px}.page-header>span{justify-self:end;color:#94a3b8;font:12px Consolas,monospace}.chat-area{flex:1;min-height:0;overflow:hidden;display:flex;flex-direction:column}.process-panel{margin:12px 20px 0;border:1px solid #cbd8f6;border-radius:12px;background:#fff;max-height:190px;overflow:auto;flex:none}.process-panel>header{position:sticky;top:0;display:flex;justify-content:space-between;padding:9px 13px;background:#edf3ff;color:#315fdb;z-index:1}.process-panel>header strong{font-size:12px}.process-panel>header span{font-size:10px}.process-list{padding:8px 12px}.process-list article{display:flex;gap:9px;padding:7px 0;border-bottom:1px solid #eef2f7;font-size:11px;color:#475569}.process-list article>span{width:20px;height:20px;border-radius:50%;background:#315fdb;color:#fff;display:grid;place-items:center;flex:none}.process-list article>div{min-width:0;max-height:70px;overflow:auto}.process-list :deep(p){margin:0}.process-list :deep(ul),.process-list :deep(ol){padding-left:18px}@media(max-width:700px){.page-header{height:64px;padding:0 14px}.page-header>span{display:none}.page-header h1{font-size:17px}.process-panel{margin:8px 10px 0}}
</style>
