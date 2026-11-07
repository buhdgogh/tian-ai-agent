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
