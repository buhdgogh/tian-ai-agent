<template>
  <div class="medical-page">
    <div class="mode-bar">
      <div><span>回答模式</span><button v-for="item in modes" :key="item.id" :class="{active:mode===item.id}" :disabled="['connecting','loading'].includes(status)" @click="mode=item.id">{{ item.label }}</button></div>
      <small>{{ modeHint }}</small>
    </div>
    <ChatRoom ref="chat" :messages="messages" :connection-status="status" ai-type="medical" :emergency-text="emergency"
      title="天医医疗信息助手" subtitle="先识别危险信号，再结合可追溯资料解释健康信息" welcome-icon="⚕"
      :suggestions="['这些症状有哪些需要立即就医的危险信号？','结合知识库解释这份检查结果','生成一份结构化循证安全报告']"
      @send-message="send" @stop-generation="stop" @refresh-history="loadHistory" />
  </div>
</template>
<script setup>
import { computed } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { chatMedicalRag, createMedicalEvidenceReport, streamMedicalAssistant } from '../api'
import { apiError, chunkText, emergencyMessage, normalizeSources, sessionTypeForMode } from '../utils/medical'
import { useChatSession } from '../composables/useChatSession'

const props = defineProps({ chatIdProp: String, modeProp: { type: String, default: 'chat' } })
const emit = defineEmits(['update-history', 'update-chat-id', 'change-mode'])
const mode = computed({ get: () => props.modeProp, set: value => emit('change-mode', value) })
const sessionType = computed(() => sessionTypeForMode(mode.value))
const { messages, status, emergency, begin, stop, markEmergency, loadHistory } = useChatSession(props, emit, sessionType)
const modes = [{ id: 'chat', label: '安全问答' }, { id: 'rag', label: '循证检索' }, { id: 'report', label: '安全报告' }]
const modeHint = computed(() => ({ chat: '流式回答，并实时检查紧急风险', rag: '基于已审核知识库生成带来源的回答', report: '输出风险、证据、不确定性与下一步' }[mode.value]))
const send = async text => {
  const run = begin()
  if (!run) return
  const requestMode = mode.value
  messages.value.push({ content: text, isUser: true, time: Date.now() })
  messages.value.push({ content: '', isUser: false, time: Date.now(), status: 'RUNNING' })
  const target = messages.value.at(-1)
  const checkEmergency = value => { const hit = emergencyMessage(value); if (hit) markEmergency(hit) }
  try {
    const chatId = await run.ensureId()
    if (!run.active()) return
    const payload = { message: text, chatId }
    if (requestMode === 'chat') {
      await streamMedicalAssistant(payload, raw => {
        if (!run.active()) return
        const part = chunkText(raw)
        target.content += part.text
        if (part.sources.length) target.sources = part.sources
        if (part.report) target.report = part.report
        checkEmergency(part.report || target.content)
      }, run.signal)
    } else {
      const request = requestMode === 'rag' ? chatMedicalRag : createMedicalEvidenceReport
      const { data } = await request(payload, { signal: run.signal })
      if (!run.active()) return
      const report = requestMode === 'report' ? data?.report || data : data?.report
      target.report = report
      target.content = typeof data === 'string' ? data : data?.answer || data?.content || data?.message || (report ? '' : '未获取到回答。')
      target.sources = normalizeSources(data?.sources || report?.sources)
      target.ragMeta = requestMode === 'rag' ? { plan: data?.plan, validation: data?.validation, retrieval: data?.retrieval, riskLevel: data?.riskLevel } : null
      target.evidenceEmpty = requestMode === 'rag' && !target.sources.length
      checkEmergency(data || report || target.content)
    }
    target.status = run.signal.aborted ? 'CANCELLED' : 'COMPLETED'
    if (!target.content.trim() && !target.sources?.length && !target.report) target.content = '服务未返回有效内容，请稍后重试。'
  } catch (error) {
    target.status = run.signal.aborted ? 'CANCELLED' : 'FAILED'
    target.incomplete = true
    if (!run.signal.aborted) target.error = apiError(error)
  } finally { run.finish() }
}

</script>
<style scoped>.medical-page{height:100%;display:flex;flex-direction:column}.mode-bar{min-height:58px;padding:8px 24px;display:flex;align-items:center;justify-content:space-between;gap:15px;background:#fff;border-bottom:1px solid #e0eaea}.mode-bar>div{display:flex;align-items:center;gap:6px}.mode-bar span{font-size:12px;color:#718589;margin-right:6px}.mode-bar button{border:0;background:#f0f5f5;color:#577075;padding:7px 11px;border-radius:9px;font:12px inherit}.mode-bar button.active{background:#087f8c;color:#fff}.mode-bar small{color:#718589}@media(max-width:700px){.mode-bar{align-items:flex-start;overflow:auto}.mode-bar small,.mode-bar span{display:none}.mode-bar>div{white-space:nowrap}}</style>
