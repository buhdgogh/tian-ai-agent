<template>
  <div class="advisor-page">
    <header class="page-header">
      <button class="back-button" @click="router.push('/')">← 返回</button>
      <div class="heading">
        <h1>启航学业就业助手</h1>
        <p>学业 · 升学 · 实习 · 求职</p>
      </div>
      <span class="chat-id">{{ chatId }}</span>
    </header>

    <div class="mode-bar" aria-label="对话模式">
      <button v-for="item in modes" :key="item.id" :class="{ active: mode === item.id }" :disabled="connectionStatus === 'connecting'" @click="mode = item.id">
        <span>{{ item.icon }}</span>{{ item.label }}
      </button>
    </div>

    <main class="chat-area">
      <ChatRoom
        ref="chatRoomRef"
        :messages="messages"
        :connection-status="connectionStatus"
        ai-type="advisor"
        theme-color="#0f766e"
        theme-color-rgb="15, 118, 110"
        title="启航学业就业助手"
        :subtitle="activeMode.description"
        welcome-icon="🎓"
        :suggestions="activeMode.suggestions"
        :placeholder="activeMode.placeholder"
        @send-message="sendMessage"
      />
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithAdvisor, chatWithAdvisorRag, chatWithAdvisorTools, createPlanningReport, getSessionMessages } from '../api'

const props = defineProps({ chatIdProp: String })
const emit = defineEmits(['update-history', 'update-chat-id'])
const router = useRouter()
const messages = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
const chatRoomRef = ref(null)
const mode = ref('chat')
let eventSource = null

const modes = [
  { id: 'chat', icon: '💬', label: '学业求职咨询', description: '多轮了解你的专业、兴趣、能力与经历，把学业或求职目标拆成可执行计划', placeholder: '告诉我你的年级、专业、目标、经历和当前困难…', suggestions: ['帮我规划本学期的学习重点', '如何在考研和就业之间做选择？', '帮我制定三个月实习准备计划'] },
  { id: 'rag', icon: '📚', label: '知识问答', description: '结合知识库资料回答学业政策、培养方案、升学与岗位问题', placeholder: '询问已收录资料中的政策、培养方案、考试或岗位要求…', suggestions: ['培养方案对实践学分有什么要求？', '总结知识库中的升学政策', '目标岗位需要哪些核心能力？'] },
  { id: 'tools', icon: '🛠', label: '工具分析', description: '使用受控工具完成信息检索和辅助分析', placeholder: '描述需要检索或分析的规划问题…', suggestions: ['分析 Java 后端岗位常见能力要求', '帮我比较两个职业方向', '查找适合应届生的准备方法'] },
  { id: 'report', icon: '📋', label: '规划报告', description: '根据你的职业画像生成结构化行动计划报告', placeholder: '完整描述你的兴趣、能力、经历、目标、期限和限制条件…', suggestions: ['为软件工程大三学生生成实习规划报告', '生成我的职业方向分析报告', '生成从现在到秋招的求职路线图'] }
]
const activeMode = computed(() => modes.find(item => item.id === mode.value) || modes[0])

const generateChatId = () => {
  const token = crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const id = `advisor_${token}`
  chatId.value = id
  emit('update-chat-id', id)
  return id
}

const closeStream = () => {
  if (eventSource) eventSource.close()
  eventSource = null
}

const normalizeMessages = list => (Array.isArray(list) ? list : []).map(item => ({
  content: item.content || item.text || '',
  isUser: ['USER', 'user'].includes(item.messageType || item.role),
  time: item.timestamp ? new Date(item.timestamp).getTime() : Date.now()
}))

const loadMessages = async id => {
  closeStream()
  connectionStatus.value = 'disconnected'
  chatRoomRef.value?.clearInput()
  try {
    const { data } = await getSessionMessages(id, 'advisor')
    messages.value = normalizeMessages(data)
  } catch (error) {
    console.error('加载会话失败:', error)
    messages.value = []
  }
}

watch(() => props.chatIdProp, value => {
  if (value && value !== chatId.value) {
    chatId.value = value
    loadMessages(value)
  } else if (!value) {
    closeStream()
    chatId.value = ''
    messages.value = []
  }
}, { immediate: true })

const addMessage = (content, isUser) => messages.value.push({ content, isUser, time: Date.now() })
const errorText = error => error?.response?.data?.message || error?.response?.data?.error || error?.response?.data || error?.message || '请求失败，请稍后重试。'
const finish = () => {
  connectionStatus.value = 'disconnected'
  closeStream()
  emit('update-history')
}

const sendMessage = async message => {
  if (!chatId.value) generateChatId()
  addMessage(message, true)
  const answerIndex = messages.value.length
  addMessage('', false)
  connectionStatus.value = 'connecting'

  if (mode.value === 'chat') {
    let received = false
    eventSource = chatWithAdvisor(message, chatId.value)
    eventSource.onmessage = event => {
      if (event.data === '[DONE]') return finish()
      if (event.data) {
        received = true
        messages.value[answerIndex].content += event.data
      }
    }
    eventSource.onerror = error => {
      if (!received) messages.value[answerIndex].content = '连接规划助手失败，请确认后端服务已启动。'
      else console.debug('SSE stream closed', error)
      finish()
    }
    return
  }

  try {
    const action = mode.value === 'rag' ? chatWithAdvisorRag : mode.value === 'tools' ? chatWithAdvisorTools : createPlanningReport
    const { data } = await action(message, chatId.value)
    messages.value[answerIndex].content = typeof data === 'string' ? data : formatReport(data)
  } catch (error) {
    messages.value[answerIndex].content = `请求失败：${errorText(error)}`
  } finally {
    finish()
  }
}

const formatReport = report => {
  if (!report || typeof report !== 'object') return String(report || '未生成报告')
  const labels = { title: '规划主题', currentAssessment: '职业画像', recommendedDirections: '推荐方向', abilityGaps: '能力差距', goals: '规划目标', actionPlan: '行动计划', risks: '风险与应对', nextQuestions: '待补充信息' }
  return Object.entries(report).map(([key, value]) => {
    const text = Array.isArray(value) ? value.map(item => {
      if (typeof item !== 'object') return `- ${item}`
      const actions = Array.isArray(item.actions) ? item.actions.map(action => `  - ${action}`).join('\n') : ''
      return `- ${item.phase || '阶段'}：${item.objective || ''}\n${actions}${item.completionCriteria ? `\n  完成标准：${item.completionCriteria}` : ''}`
    }).join('\n') : typeof value === 'object' ? JSON.stringify(value, null, 2) : value
    return `## ${labels[key] || key}\n\n${text}`
  }).join('\n\n')
}

onMounted(() => { if (!chatId.value) generateChatId() })
onBeforeUnmount(closeStream)
</script>

<style scoped>
.advisor-page{height:100vh;display:flex;flex-direction:column;background:#f6faf9;color:#18312e}.page-header{height:76px;display:grid;grid-template-columns:1fr auto 1fr;align-items:center;padding:0 24px;background:#ffffffde;backdrop-filter:blur(12px);border-bottom:1px solid #dce9e6;z-index:5}.back-button{justify-self:start;border:0;background:transparent;color:#5f716f;font:inherit}.heading{text-align:center}.heading h1{font-size:20px;color:#0f766e;margin:0}.heading p{font-size:11px;color:#849490;margin:4px 0 0;letter-spacing:1px}.chat-id{justify-self:end;color:#94a3b8;font:12px Consolas,monospace}.mode-bar{display:flex;justify-content:center;gap:7px;padding:9px 16px;background:#fff;border-bottom:1px solid #e5eeec;overflow-x:auto}.mode-bar button{border:1px solid transparent;background:transparent;border-radius:999px;padding:8px 14px;color:#64748b;font:13px inherit;white-space:nowrap}.mode-bar button span{margin-right:6px}.mode-bar button.active{background:#e8f7f3;border-color:#bde4db;color:#087367;font-weight:600}.chat-area{flex:1;min-height:0;overflow:hidden}@media(max-width:700px){.page-header{height:64px;padding:0 14px}.chat-id{display:none}.heading h1{font-size:17px}.mode-bar{justify-content:flex-start}.mode-bar button{padding:7px 11px}}
</style>
