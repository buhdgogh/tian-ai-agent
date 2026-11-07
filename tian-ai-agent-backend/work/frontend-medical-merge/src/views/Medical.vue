<template>
  <div class="medical-page">
    <div class="mode-bar">
      <div><span>回答模式</span><button v-for="item in modes" :key="item.id" :class="{active:mode===item.id}" :disabled="['connecting','loading'].includes(status)" @click="mode=item.id">{{ item.label }}</button></div>
      <small>{{ modeHint }}</small>
    </div>
    <ChatRoom :messages="messages" :connection-status="status" :ai-type="isAgent ? 'agent' : 'medical'" :emergency-text="emergency"
      :theme-color="isAgent ? '#315ea8' : '#087f8c'" :theme-color-rgb="isAgent ? '49,94,168' : '8,127,140'"
      title="天医医疗助手" :subtitle="modeHint" :welcome-icon="isAgent ? '🔬' : '⚕'"
      :suggestions="suggestions"
      @send-message="send" @stop-generation="stop" @refresh-history="loadHistory" />
  </div>
</template>
<script setup>
import { computed } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { chatMedicalRag, createMedicalEvidenceReport, streamMedicalAssistant, streamMedicalAgent } from '../api'
import { apiError, chunkText, emergencyMessage, normalizeSources, parseAgentEvent, sessionTypeForMode } from '../utils/medical'
import { useChatSession } from '../composables/useChatSession'

const props = defineProps({ chatIdProp: String, modeProp: { type: String, default: 'chat' } })
const emit = defineEmits(['update-history', 'update-chat-id', 'change-mode'])
const mode = computed({ get: () => props.modeProp, set: value => emit('change-mode', value) })
const sessionType = computed(() => sessionTypeForMode(mode.value))
const isAgent = computed(() => mode.value === 'agent')
const { messages, status, emergency, begin, stop, markEmergency, loadHistory } = useChatSession(props, emit, sessionType, isAgent)
const modes = [{ id: 'chat', label: '安全问答' }, { id: 'rag', label: '循证检索' }, { id: 'report', label: '安全报告' }, { id: 'agent', label: '深度分析' }]
const modeHint = computed(() => ({ chat: '日常健康信息问答，并实时识别紧急风险', rag: '结合已审核知识库检索证据，回答附带来源', report: '生成包含风险、证据、不确定性与下一步的结构化报告', agent: '适合复杂问题：动态规划、多源检索与质量复核，耗时较长' }[mode.value]))
const suggestions = computed(() => ({
  chat: ['这些症状有哪些需要立即就医的危险信号？', '帮我整理一份就诊沟通清单', '解释这份检查结果的常见含义'],
  rag: ['结合知识库解释这份检查结果', '检索有关这个健康问题的证据', '有哪些证据支持这项建议？'],
  report: ['生成一份结构化循证安全报告', '整理这份建议的风险与不确定性', '生成就医准备与下一步建议报告'],
  agent: ['梳理这些症状的危险信号并给出就医准备清单', '检索证据并比较两种常见处理方案', '对这份健康建议执行临床安全复核']
}[mode.value]))
const send = async text => {
  const run = begin()
  if (!run) return
  const requestMode = mode.value
  messages.value.push({ content: text, isUser: true, time: Date.now() })
  messages.value.push({ content: '', isUser: false, time: Date.now(), status: 'RUNNING', trace: [] })
  const target = messages.value.at(-1)
  const checkEmergency = value => { const hit = emergencyMessage(value); if (hit) markEmergency(hit) }
  try {
    const chatId = await run.ensureId()
    if (!run.active()) return
    const payload = { message: text, chatId }
    if (requestMode === 'agent') {
      await streamMedicalAgent(payload, raw => {
        if (!run.active()) return
        const part = parseAgentEvent(raw)
        target.trace.push(...part.trace)
        if (part.trace.some(event => event.status === 'failed')) target.status = 'FAILED'
        if (part.text) target.content += (target.content ? '\n\n' : '') + part.text
        if (part.sources.length) target.sources = part.sources
        if (part.report) target.report = part.report
        checkEmergency(part.report || target.content)
      }, run.signal)
    } else if (requestMode === 'chat') {
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
    if (target.status !== 'FAILED') target.status = run.signal.aborted ? 'CANCELLED' : 'COMPLETED'
    if (!target.content.trim() && !target.sources?.length && !target.report) target.content = '服务未返回有效内容，请稍后重试。'
  } catch (error) {
    target.status = run.signal.aborted ? 'CANCELLED' : 'FAILED'
    target.incomplete = true
    if (!run.signal.aborted) target.error = apiError(error)
  } finally { run.finish() }
}

</script>
<style scoped>.medical-page{height:100%;display:flex;flex-direction:column}.mode-bar{min-height:58px;padding:8px 24px;display:flex;align-items:center;justify-content:space-between;gap:15px;background:#fff;border-bottom:1px solid #e0eaea}.mode-bar>div{display:flex;align-items:center;gap:6px}.mode-bar span{font-size:12px;color:#718589;margin-right:6px}.mode-bar button{border:0;background:#f0f5f5;color:#577075;padding:7px 11px;border-radius:9px;font:12px inherit}.mode-bar button.active{background:#087f8c;color:#fff}.mode-bar small{color:#718589}@media(max-width:700px){.mode-bar{align-items:flex-start;overflow:auto}.mode-bar small,.mode-bar span{display:none}.mode-bar>div{white-space:nowrap}}</style>
