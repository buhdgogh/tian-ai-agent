import { computed, nextTick, ref, watch } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { cancelTask, dispatchMedical, getTask } from '../api'
import { apiError, emergencyMessage, parseAgentHistory } from '../utils/medical'
import { applyTaskSnapshot, waitForTaskPoll } from '../utils/workflow'
import { useChatSession } from '../composables/useChatSession'

const props = defineProps({ chatIdProp: String, sessionTypeProp: { type: String, default: 'agent' } })
const emit = defineEmits(['update-history', 'update-chat-id'])
const sessionType = computed(() => props.sessionTypeProp)
const agentHistory = computed(() => sessionType.value === 'agent')
const { messages, status, emergency, begin, stop: stopWaiting, markEmergency, loadHistory } = useChatSession(props, emit, sessionType, agentHistory)
const stopping = ref(false)
let tracking = null, currentReply = null
const checkEmergency = value => { const hit = emergencyMessage(value); if (hit) markEmergency(hit) }

const followTask = async (taskId, target, run, initial) => {
  const active = { taskId, target, run }
  tracking = active
  target.trackingError = ''
  try {
    let task = initial
    while (run.active()) {
      if (!task) task = (await getTask(taskId, { signal: run.signal })).data
      if (!run.active()) return
      const running = applyTaskSnapshot(target, task)
      checkEmergency(target.content)
      if (!running) return
      await waitForTaskPoll(run.signal)
      task = null
    }
  } catch (error) {
    if (run.active()) target.trackingError = `暂时无法读取任务进度：${apiError(error)} 后台任务可能仍在执行，可刷新状态继续查看。`
  } finally { if (tracking === active) tracking = null }
}

const send = async text => {
  const run = begin()
  if (!run) return
  messages.value.push({ content: text, isUser: true, time: Date.now() })
  messages.value.push({ content: '', isUser: false, time: Date.now(), status: 'RUNNING', trace: [] })
  const target = messages.value.at(-1)
  currentReply = target
  try {
    const chatId = await run.ensureId()
    if (!run.active()) return
    const { data } = await dispatchMedical({ message: text, chatId, sessionType: sessionType.value }, { signal: run.signal })
    if (!run.active()) return
    if (data.mode === 'WORKFLOW') {
      if (!data.task?.id) throw new Error('服务未返回流程任务标识。')
      await followTask(data.task.id, target, run, data.task)
    } else {
      const answer = parseAgentHistory(data.answer || '')
      target.content = answer.content || '服务未返回有效内容，请稍后重试。'
      target.trace = answer.trace
      target.status = answer.trace.some(step => step.status === 'failed') ? 'FAILED' : 'COMPLETED'
      checkEmergency(target.content)
    }
  } catch (error) {
    if (run.active()) {
      target.status = 'FAILED'
      target.error = apiError(error)
      target.incomplete = true
    }
  } finally {
    if (currentReply === target) currentReply = null
    run.finish()
  }
}

const stop = async () => {
  if (stopping.value) return
  const active = tracking
  if (active) {
    stopping.value = true
    try {
      const { data } = await cancelTask(active.taskId)
      if (!active.run.active()) return
      applyTaskSnapshot(active.target, data)
      stopWaiting()
    } catch (error) {
      if (active.run.active()) active.target.trackingError = `停止任务失败：${apiError(error)} 可再次尝试停止。`
    } finally { stopping.value = false }
  } else {
    const reply = currentReply
    stopWaiting()
    if (reply) {
      reply.status = 'RUNNING'
      reply.incomplete = false
      reply.trackingError = '已停止等待。后台处理可能仍在继续，可刷新状态确认结果。'
    }
  }
}

// A page refresh can reconnect to a workflow using its persisted taskId.
watch(messages, async history => {
  await nextTick()
  const target = history.find(message => !message.isUser && ['RUNNING', 'PENDING'].includes(message.status) && message.metadata?.taskId)
  if (!target || status.value !== 'disconnected') return
  const run = begin()
  if (!run) return
  try { await followTask(target.metadata.taskId, target, run) }
  finally { run.finish() }
})
