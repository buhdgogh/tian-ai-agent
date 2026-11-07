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
