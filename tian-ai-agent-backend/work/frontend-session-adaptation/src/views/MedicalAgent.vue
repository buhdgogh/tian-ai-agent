<template>
  <div class="agent-page">
    <div class="agent-head">
      <div><b>深度循证分析</b><span>确定性急症检查 · 动态规划 · 多源检索 · 质量复核</span></div>
      <i v-if="status === 'connecting'">正在执行动态医疗信息任务</i>
    </div>
    <ChatRoom :messages="messages" :connection-status="status" ai-type="agent" theme-color="#315ea8" theme-color-rgb="49,94,168"
      :emergency-text="emergency" title="深度循证分析" subtitle="面向复杂医疗信息任务，动态检索并核对多来源证据" welcome-icon="🔬"
      :suggestions="['梳理这些症状的危险信号并给出就医准备清单','检索证据并比较两种常见处理方案','对这份健康建议执行临床安全复核']"
      @send-message="send" @stop-generation="stop" @refresh-history="loadHistory"/>
  </div>
</template>
<script setup>
import ChatRoom from '../components/ChatRoom.vue'
import { streamMedicalAgent } from '../api'
import { apiError, emergencyMessage, parseAgentEvent } from '../utils/medical'
import { useChatSession } from '../composables/useChatSession'

const props = defineProps({ chatIdProp: String })
const emit = defineEmits(['update-history', 'update-chat-id'])
const { messages, status, emergency, begin, stop, markEmergency, loadHistory } = useChatSession(props, emit, 'agent', true)
const send = async text => {
  const run = begin()
  if (!run) return
  messages.value.push({ content: text, isUser: true, time: Date.now() })
  messages.value.push({ content: '', isUser: false, time: Date.now(), trace: [], status: 'RUNNING' })
  const target = messages.value.at(-1)
  try {
    const chatId = await run.ensureId()
    if (!run.active()) return
    await streamMedicalAgent({ message: text, chatId }, raw => {
      if (!run.active()) return
      const part = parseAgentEvent(raw)
      if (part.trace.length) target.trace.push(...part.trace)
      if (part.trace.some(event => event.status === 'failed')) target.status = 'FAILED'
      if (part.text) target.content += (target.content ? '\n\n' : '') + part.text
      if (part.sources.length) target.sources = part.sources
      if (part.report) target.report = part.report
      const hit = emergencyMessage(part.report || target.content)
      if (hit) markEmergency(hit)
    }, run.signal)
    if (target.status !== 'FAILED') target.status = run.signal.aborted ? 'CANCELLED' : 'COMPLETED'
    if (!target.content.trim() && !target.sources?.length && !target.report) target.content = '本次执行没有产生有效内容，请稍后重试。'
  } catch (error) {
    target.status = run.signal.aborted ? 'CANCELLED' : 'FAILED'
    target.incomplete = true
    if (!run.signal.aborted) target.error = apiError(error)
  } finally { run.finish() }
}

</script>
<style scoped>
.agent-page{height:100%;display:flex;flex-direction:column}.agent-head{min-height:66px;padding:10px 24px;background:#fff;border-bottom:1px solid #e2e8f0;display:flex;align-items:center;justify-content:space-between}.agent-head b{display:block;color:#234b80}.agent-head span{font-size:11px;color:#7a8da5}.agent-head>i{padding:6px 10px;border-radius:99px;background:#e5edff;color:#315ea8;font:normal 11px inherit}@media(max-width:700px){.agent-head>i{display:none}}
</style>
