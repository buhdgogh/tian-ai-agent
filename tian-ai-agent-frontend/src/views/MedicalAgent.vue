<template>
  <ChatRoom :messages="messages" :connection-status="status" :medical="medical" :emergency-text="emergency"
    ai-type="agent" title="Agent 问答" subtitle="输入问题，Agent 会自动识别意图并选择执行路径。" welcome-icon="⌘"
    @send-message="send" @stop-generation="stop">
    <template #composer-actions>
      <select id="agent-model" class="ui-field composer-model" aria-label="选择模型"
        :value="modelProviderId || ''" :disabled="!modelProviders.length"
        @change="emit('update:modelProviderId', $event.target.value)">
        <option value="">模型 · 自动选择</option>
        <option v-for="provider in modelProviders" :key="provider.id" :value="provider.id">{{ provider.model }} · {{ provider.id }}</option>
      </select>
    </template>
  </ChatRoom>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { streamAgentDispatch, getSessionMessages, getTask } from '../api'
import { apiError, emergencyMessage, newChatId, normalizeHistory, toolLabel } from '../utils/medical'

const props = defineProps({
  chatIdProp: String,
  spaceId: String,
  medical: Boolean,
  modelProviderId: String,
  modelProviders: { type: Array, default: () => [] }
})
const emit = defineEmits(['update-history', 'update-chat-id', 'chat-activity', 'update:modelProviderId'])
const messages = ref([])
const status = ref('disconnected')
const emergency = ref('')
let controller
let version = 0
let localChatId
const pollTimers = new Set()

function stop() {
  controller?.abort()
  status.value = 'disconnected'
}

function reset() {
  version++
  stop()
  pollTimers.forEach(clearTimeout)
  pollTimers.clear()
}

function showTask(target, task) {
  if (!messages.value.includes(target)) return
  target.task = task
  if (task.status === 'COMPLETED') {
    target.content = task.result?.executionResult || '工作流已完成。'
    return
  }
  if (task.status === 'FAILED' || task.status === 'CANCELLED') {
    target.content = task.status === 'FAILED' ? '工作流执行失败。详情见运行日志。' : '工作流已取消。'
    return
  }
  const timer = setTimeout(async () => {
    pollTimers.delete(timer)
    if (!messages.value.includes(target)) return
    try {
      const { data } = await getTask(task.id)
      showTask(target, data)
    } catch (error) {
      if (messages.value.includes(target)) target.content = '暂时无法刷新任务，' + apiError(error)
    }
  }, 1800)
  pollTimers.add(timer)
}

async function send(text) {
  if (status.value === 'connecting') return
  const scope = ++version
  const chatId = props.chatIdProp || newChatId('agent')
  if (!props.chatIdProp) {
    localChatId = chatId
    emit('update-chat-id', chatId)
  }
  emit('chat-activity', chatId)
  emergency.value = ''
  messages.value.push({ content: text, isUser: true, time: Date.now() },
    { content: '', isUser: false, time: Date.now(), trace: [] })
  const target = messages.value.at(-1)
  controller = new AbortController()
  status.value = 'connecting'
  try {
    let serverError = false
    const request = { message: text, chatId, spaceId: props.spaceId, modelProviderId: props.modelProviderId || undefined }
    const { sawDone } = await streamAgentDispatch(request, (type, data) => {
      if (scope !== version) return
      if (type === 'run') {
        target.runId = data.runId
      } else if (type === 'route') {
        const strategy = { KEYWORD: '规则匹配', LLM: '模型分类', KEYWORD_FALLBACK: '规则回退' }[data.strategy]
        if (strategy) target.trace.push({
          type: 'thinking', title: '意图识别 · ' + strategy,
          detail: data.intentTypes?.join('、'), status: 'completed'
        })
        if (!data.requiresWorkflow) target.trace.push({ type: 'thinking', title: 'ReAct', status: 'called' })
      } else if (type === 'delta') {
        target.content += data.text
      } else if (type === 'tools') {
        const tools = data.names.filter(name => name !== 'doTerminate')
        if (tools.length) target.content = ''
        tools.forEach(name => target.trace.push({ type: 'tool', title: toolLabel(name), tool: name, status: 'called' }))
      } else if (type === 'task' && data.id) {
        target.trace.push({ type: 'thinking', title: 'Plan–Execute–Replan', detail: '已提交工作流', status: 'called' })
        target.content = '工作流已提交，正在等待结果。'
        showTask(target, data)
      } else if (type === 'error') {
        serverError = true
        target.runId = data.runId || target.runId
        if (target.content) target.incomplete = true
        else target.content = data.message
      }
    }, controller.signal)
    if (scope !== version) return
    if (!sawDone && !serverError) throw new Error('流式连接中断，请查看运行日志。')
    if (!target.content.trim()) target.content = 'Agent 本轮未返回最终回答。'
    if (props.medical) emergency.value = emergencyMessage(target.content)
    emit('update-history')
  } catch (error) {
    if (scope !== version) return
    if (target.content) target.incomplete = true
    else target.content = apiError(error)
  } finally {
    if (scope === version) status.value = 'disconnected'
  }
}

watch([() => props.chatIdProp, () => props.spaceId], async ([id, spaceId]) => {
  if (id && id === localChatId) {
    localChatId = null
    return
  }
  reset()
  const scope = version
  messages.value = []
  emergency.value = ''
  if (!id) return
  try {
    const { data } = await getSessionMessages(id, 'agent', spaceId)
    if (scope === version) messages.value = normalizeHistory(data)
  } catch (error) {
    if (scope === version) messages.value = [{ content: apiError(error), time: Date.now() }]
  }
}, { immediate: true })

onBeforeUnmount(() => {
  reset()
  messages.value = []
})
</script>

<style scoped>
.composer-model {
  max-width: min(220px, 58vw);
  height: 32px;
  padding: 4px 8px;
  border-radius: 8px;
  color: var(--muted);
  font-size: 12px;
  text-overflow: ellipsis;
}
</style>
