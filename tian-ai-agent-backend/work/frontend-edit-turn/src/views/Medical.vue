<template>
  <div class="medical-page">
    <div v-if="pageError" class="edit-error" role="alert">{{ pageError }} <button @click="refresh">刷新对话</button></div>
    <ChatRoom :messages="messages" :connection-status="status" ai-type="medical" :emergency-text="emergency"
      title="天医医疗助手" subtitle="描述你的问题或目标，系统会按需检索证据、分析并整理报告" welcome-icon="⚕"
      :suggestions="['这些症状有哪些需要立即就医的危险信号？','检索证据并比较两种常见处理方案','结合检查结果分析风险，并整理一份循证报告']"
      @send-message="send" @stop-generation="stop" @refresh-history="refresh" @edit-message="editQuestion" />
  </div>
</template>
<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { dispatchMedical, getTask, interruptConversationTurn } from '../api'
import { apiError, emergencyMessage, newChatId, parseAgentHistory } from '../utils/medical'
import { editableLastQuestion } from '../utils/turns'
import { applyTaskSnapshot, waitForTaskPoll } from '../utils/workflow'
import { useChatSession } from '../composables/useChatSession'

const props = defineProps({ chatIdProp: String, sessionTypeProp: { type: String, default: 'agent' } })
const emit = defineEmits(['update-history', 'update-chat-id'])
const sessionType = computed(() => props.sessionTypeProp)
const agentHistory = computed(() => sessionType.value === 'agent')
const { messages, status, emergency, begin, stop: stopWaiting, markEmergency, loadHistory } = useChatSession(props, emit, sessionType, agentHistory)
const stopping = ref(false), pageError = ref('')
let tracking = null, currentReply = null, currentRun = null
const refresh = () => { pageError.value=''; return loadHistory() }
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

const send = async (text, replaces = null) => {
  const run = begin()
  if (!run) return
  pageError.value = ''
  const oldMessages = [...messages.value]
  const replacedReply = replaces ? oldMessages.find(message => !message.isUser && message.turnId === replaces) : null
  const replacesTurnId = replacedReply?.submitted === false ? null : replaces
  if (replaces) messages.value = oldMessages.filter(message => message.turnId !== replaces)
  const turnId = newChatId('turn')
  messages.value.push({ content: text, isUser: true, time: Date.now(), turnId, metadata: replacesTurnId ? { replacesTurnId } : {} })
  messages.value.push({ content: '', isUser: false, time: Date.now(), turnId, status: 'RUNNING', trace: [], submitted: false })
  const target = messages.value.at(-1)
  currentReply = target
  currentRun = run
  try {
    const chatId = await run.ensureId()
    if (!run.active()) return
    target.submitted = true
    const { data } = await dispatchMedical({ message: text, chatId, sessionType: sessionType.value, turnId,
      ...(replacesTurnId ? { replacesTurnId } : {}) }, { signal: run.signal })
    if (!run.active()) return
    if (data.mode === 'INTERRUPTED') {
      target.status = 'CANCELLED'; target.incomplete = true
    } else if (data.mode === 'WORKFLOW') {
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
      if (replaces && error.response?.status >= 400 && error.response.status < 500) {
        messages.value = oldMessages
        pageError.value = apiError(error)
      } else {
        target.status = 'FAILED'
        target.error = apiError(error)
        target.incomplete = true
      }
    }
  } finally {
    if (currentReply === target) { currentReply = null; currentRun = null }
    run.finish()
  }
}

const stop = async () => {
  if (stopping.value) return
  const target = tracking?.target || currentReply
  const run = tracking?.run || currentRun
  if (target && run) {
    stopping.value = true
    try {
      if (target.submitted !== false) {
        const { data } = await interruptConversationTurn(props.chatIdProp, target.turnId, sessionType.value)
        if (!run.active()) return
        target.status = data.status
        if (data.status === 'COMPLETED') {
          stopWaiting()
          await loadHistory()
          return
        }
        if (data.warning) target.trackingError = data.warning
      }
      stopWaiting()
      if (target.submitted === false) target.status = 'CANCELLED'
      target.incomplete = true
    } catch (error) {
      if (run.active()) target.trackingError = `停止回答失败：${apiError(error)} 可再次尝试停止。`
    } finally { stopping.value = false }
  } else {
    stopWaiting()
  }
}

const editQuestion = ({turnId, text}) => {
  if (!text?.trim() || editableLastQuestion(messages.value)?.turnId !== turnId) return
  return send(text.trim(), turnId)
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

</script>
<style scoped>.medical-page{height:100%;min-height:0;display:flex;flex-direction:column}.edit-error{padding:10px 18px;background:#fff2dc;color:#875b08;font-size:13px}</style>
