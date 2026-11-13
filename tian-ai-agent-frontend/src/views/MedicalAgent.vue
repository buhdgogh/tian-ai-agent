<template>
  <ChatRoom ref="chatRoom" :messages="messages" :connection-status="status" :medical="medical" :emergency-text="emergency"
    ai-type="agent" title="Agent 问答" subtitle="输入问题，Agent 会自动识别意图并选择执行路径。" welcome-icon="⌘"
    :prompt-templates="templates" :prompt-templates-loading="templatesLoading"
    :placeholder="templates.length || templatesLoading ? '输入问题，键入 / 插入 Prompt 模板' : undefined"
    @send-message="send" @stop-generation="stop">
    <template #composer-actions>
      <div class="composer-options">
        <span v-if="workspaceLocked" class="ui-pill workspace-button" :title="workspace.name">
          工作区 · {{ workspace.name }}
        </span>
        <button v-else-if="!workspaceLoading && !workspaceUnavailable" type="button" class="ui-button workspace-button"
          :disabled="pickingWorkspace || preparingWorkspace || status === 'connecting'"
          :title="workspace ? workspace.name : '选择要授权读取的本地项目目录'" @click="selectWorkspace">
          {{ workspace ? '工作区 · ' + workspace.name : '选择工作区' }}
        </button>
        <button v-if="workspace && !workspaceLocked" type="button" class="ui-button" :disabled="pickingWorkspace || preparingWorkspace || status === 'connecting'"
          aria-label="移除本地工作区" @click="clearWorkspace">移除</button>
        <select id="agent-model" class="ui-field composer-model" aria-label="选择模型"
          :value="modelProviderId || ''" :disabled="!modelProviders.length"
          @change="emit('update:modelProviderId', $event.target.value)">
          <option value="">模型 · 自动选择</option>
          <option v-for="provider in modelProviders" :key="provider.id" :value="provider.id">{{ provider.model }} · {{ provider.id }}</option>
        </select>
        <details v-if="skills.length" ref="skillPicker" class="composer-skills">
          <summary class="ui-button">Skills{{ skillIds.length ? ' · ' + skillIds.length : '' }}</summary>
          <div class="ui-panel skill-picker">
            <p>本轮最多选用 5 个 Skill</p>
            <label v-for="skill in skills" :key="skill.id" :title="skill.description">
              <input v-model="skillIds" type="checkbox" :value="skill.id"
                :disabled="status === 'connecting' || (skillIds.length >= 5 && !skillIds.includes(skill.id))" />
              {{ skill.name }}
            </label>
          </div>
        </details>
      </div>
      <span v-if="workspaceError" class="workspace-hint" role="alert">{{ workspaceError }}</span>
      <span v-else-if="workspaceLoading" class="workspace-hint" role="status">正在加载会话工作区…</span>
      <span v-else-if="workspace && !workspaceLocked" class="workspace-hint">只读 · 按需向当前模型发送文件片段</span>
    </template>
  </ChatRoom>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { streamAgentDispatch, getSessionMessages, getTask, getAgentPresets, getApiToken, replyWorkspaceRead } from '../api'
import { readLocalWorkspace } from '../utils/localWorkspace'
import { bindWorkspace, getWorkspaceBinding } from '../utils/workspaceBindings'
import { apiError, emergencyMessage, newChatId, normalizeHistory, toolLabel } from '../utils/medical'

const props = defineProps({
  chatIdProp: String,
  spaceId: String,
  medical: Boolean,
  modelProviderId: String,
  modelProviders: { type: Array, default: () => [] }
})
const emit = defineEmits(['update-history', 'update-chat-id', 'chat-activity', 'update:modelProviderId'])
const chatRoom = ref(null)
const workspace = shallowRef(null)
const workspaceError = ref('')
const pickingWorkspace = ref(false)
const preparingWorkspace = ref(false)
const workspaceLocked = ref(false)
const workspaceLoading = ref(false)
const workspaceUnavailable = ref(false)
const skillPicker = ref(null)
const skills = ref([])
const skillIds = ref([])
const templates = ref([])
const templatesLoading = ref(false)
const messages = ref([])
const status = ref('disconnected')
const emergency = ref('')
let controller
let version = 0
let localChatId
const pollTimers = new Set()

async function selectWorkspace() {
  if (workspaceLocked.value || pickingWorkspace.value || preparingWorkspace.value || status.value === 'connecting') return
  const scope = version
  workspaceError.value = ''
  if (typeof window.showDirectoryPicker !== 'function') {
    workspaceError.value = '目录选择需要通过 HTTPS 或 localhost 使用支持的 Chrome 或 Edge。'
    return
  }
  pickingWorkspace.value = true
  try {
    const directory = await window.showDirectoryPicker({ mode: 'read' })
    if (scope === version) workspace.value = directory
  } catch (error) {
    if (scope === version && error.name !== 'AbortError') workspaceError.value = '无法打开目录，请重新选择并允许读取。'
  } finally {
    if (scope === version) pickingWorkspace.value = false
  }
}

function clearWorkspace() {
  if (workspaceLocked.value || preparingWorkspace.value || status.value === 'connecting') return
  workspace.value = null
  workspaceError.value = ''
}

async function answerWorkspaceRead(root, runId, request, signal) {
  const result = await readLocalWorkspace(root, request)
  if (signal.aborted) return
  try {
    await replyWorkspaceRead(runId, request.requestId, result, signal)
  } catch {
    if (!signal.aborted) workspaceError.value = '工作区读取结果提交失败，请重试本轮问答。'
  }
}

async function loadSkills() {
  if (!getApiToken()) return
  const result = await getAgentPresets('skills').catch(() => null)
  if (!result) return
  skills.value = result.data.filter(item => item.enabled)
  skillIds.value = skillIds.value.filter(id => skills.value.some(skill => skill.id === id))
}

async function loadTemplates() {
  if (!getApiToken()) return
  templatesLoading.value = true
  try {
    const result = await getAgentPresets('prompt-templates').catch(() => null)
    if (result) templates.value = result.data.filter(item => item.enabled)
  } finally {
    templatesLoading.value = false
  }
}

onMounted(() => {
  loadSkills()
  loadTemplates()
})

function finishTrace(target, status = 'completed') {
  target.trace.forEach(step => {
    if (step.status === 'running') step.status = status
  })
}

function startThinking(target, step, root) {
  const id = 'react-' + step
  if (target.trace.some(item => item.id === id)) return
  finishTrace(target)
  target.trace.push({
    id, type: 'thinking', title: '第 ' + step + ' 步 · 思考', status: 'running',
    detail: step === 1 ? (root ? '本地工作区 · 只读' : 'ReAct') : undefined
  })
}

function stop() {
  const target = messages.value.at(-1)
  if (status.value === 'connecting' && target?.trace && !target.task) {
    finishTrace(target, 'cancelled')
    if (target.content) target.incomplete = true
    else target.content = '已停止生成。'
  }
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
  const active = task.status === 'PENDING' || task.status === 'RUNNING'
  const events = (task.events || []).map(event => event.replace(/^\d{4}-\d{2}-\d{2}T\S+\s+/, ''))
  const previousSteps = events.slice(0, -1).map((title, index) => ({
    id: 'workflow-event-' + index, workflow: true, type: 'thinking', title, status: 'completed'
  }))
  const planSteps = (task.result?.plan?.steps || []).map(step => ({
    id: 'workflow-plan-' + step.id, workflow: true, type: 'thinking', status: 'completed',
    title: '步骤 ' + step.order + ' · ' + step.role, detail: step.description
  }))
  target.trace = [...target.trace.filter(step => !step.workflow), ...previousSteps, ...planSteps, {
    id: 'workflow-stage', workflow: true, type: 'thinking', title: task.stage || '等待工作流',
    detail: active ? events.at(-1) : undefined,
    status: active ? 'running' : task.status === 'FAILED' ? 'failed' : task.status === 'CANCELLED' ? 'cancelled' : 'completed'
  }]
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
      if (messages.value.includes(target)) {
        finishTrace(target, 'failed')
        target.content = '暂时无法刷新任务，' + apiError(error)
      }
    }
  }, 1800)
  pollTimers.add(timer)
}

async function send(text) {
  if (status.value === 'connecting') return
  const selectionScope = version
  const restoreDraft = () => nextTick(() => {
    if (selectionScope === version) chatRoom.value?.restoreInput(text)
  })
  if (workspaceLoading.value || workspaceUnavailable.value || pickingWorkspace.value || preparingWorkspace.value) {
    restoreDraft()
    return
  }
  const chatId = props.chatIdProp || newChatId('agent')
  const root = workspace.value
  workspaceError.value = ''
  if (root) {
    preparingWorkspace.value = true
    try {
      if (workspaceLocked.value) {
        const permission = await root.requestPermission({ mode: 'read' })
        if (permission !== 'granted') throw new Error('请在浏览器授权提示中允许读取当前工作区，再发送消息。')
      } else {
        const binding = await bindWorkspace(chatId, props.spaceId, root)
        if (selectionScope !== version) return
        workspace.value = binding.directory
        workspaceLocked.value = true
        if (!binding.created && !await root.isSameEntry(binding.directory)) {
          throw new Error('该会话已绑定上方显示的工作区，请重新发送消息。')
        }
      }
    } catch (error) {
      if (selectionScope !== version) return
      workspaceError.value = error.message || '无法访问当前工作区，请重试。'
      restoreDraft()
      return
    } finally {
      if (selectionScope === version) preparingWorkspace.value = false
    }
    if (selectionScope !== version) return
  }
  const scope = ++version
  if (!props.chatIdProp) {
    localChatId = chatId
    emit('update-chat-id', chatId)
  }
  emit('chat-activity', chatId)
  emergency.value = ''
  messages.value.push({ content: text, isUser: true, time: Date.now() },
    { content: '', isUser: false, time: Date.now(), trace: [
      { id: 'intent', type: 'thinking', title: '意图识别', status: 'running' }
    ] })
  const target = messages.value.at(-1)
  controller = new AbortController()
  const { signal } = controller
  status.value = 'connecting'
  if (skillPicker.value) skillPicker.value.open = false
  try {
    let serverError = false
    const request = {
      message: text, chatId, spaceId: props.spaceId, modelProviderId: props.modelProviderId || undefined,
      skillIds: skillIds.value.length ? [...skillIds.value] : undefined,
      localWorkspace: root ? true : undefined
    }
    const { sawDone } = await streamAgentDispatch(request, (type, data) => {
      if (scope !== version) return
      if (type === 'run') {
        target.runId = data.runId
      } else if (type === 'workspace_request' && root && target.runId && !signal.aborted) {
        void answerWorkspaceRead(root, target.runId, data, signal)
      } else if (type === 'route') {
        const strategy = { KEYWORD: '规则匹配', LLM: '模型分类', KEYWORD_FALLBACK: '规则回退' }[data.strategy]
        Object.assign(target.trace[0], {
          title: strategy ? '意图识别 · ' + strategy : '意图识别',
          detail: data.intentTypes?.join('、'), status: 'completed'
        })
        const readsToolInventory = data.intentTypes?.some(intent =>
          intent === 'TOOL_INVENTORY' || intent === 'MCP_TOOL_INVENTORY')
        if (readsToolInventory) target.trace.push({ type: 'thinking', title: '读取工具清单', status: 'running' })
        else if (!data.requiresWorkflow && !data.intentTypes?.includes('EMERGENCY')) startThinking(target, 1, root)
        else if (data.requiresWorkflow) target.trace.push({ type: 'thinking', title: '提交工作流', status: 'running' })
      } else if (type === 'thinking') {
        startThinking(target, data.step, root)
      } else if (type === 'delta') {
        finishTrace(target)
        target.content += data.text
      } else if (type === 'tools') {
        finishTrace(target)
        const tools = data.names.filter(name => name !== 'doTerminate')
        if (tools.length) {
          const thinking = target.trace.findLast(step => step.id?.startsWith('react-'))
          if (thinking && target.content.trim()) thinking.detail = target.content.trim()
          target.content = ''
        }
        tools.forEach(name => target.trace.push({ type: 'tool', title: toolLabel(name), tool: name, status: 'running' }))
      } else if (type === 'task' && data.id) {
        finishTrace(target)
        showTask(target, data)
      } else if (type === 'error') {
        serverError = true
        if (!target.task) finishTrace(target, 'failed')
        target.runId = data.runId || target.runId
        if (target.content) target.incomplete = true
        else target.content = data.message
      }
    }, signal)
    if (scope !== version) return
    if (!sawDone && !serverError) throw new Error('流式连接中断，请查看运行日志。')
    if (!target.task) {
      finishTrace(target)
      if (!target.content.trim()) target.content = 'Agent 本轮未返回最终回答。'
    }
    if (props.medical) emergency.value = emergencyMessage(target.content)
    if (!serverError) skillIds.value = []
    emit('update-history')
  } catch (error) {
    if (scope !== version) return
    if (signal.aborted || target.task) return
    finishTrace(target, 'failed')
    if (error.status === 400 && skillIds.value.length) loadSkills()
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
  workspace.value = null
  workspaceLocked.value = false
  workspaceError.value = ''
  workspaceUnavailable.value = false
  workspaceLoading.value = Boolean(id)
  pickingWorkspace.value = false
  preparingWorkspace.value = false
  if (id) {
    getWorkspaceBinding(id, spaceId).then(directory => {
      if (scope !== version) return
      workspace.value = directory
      workspaceLocked.value = Boolean(directory)
    }).catch(() => {
      if (scope !== version) return
      workspaceUnavailable.value = true
      workspaceError.value = '无法读取会话的本地工作区绑定，请重新打开会话。'
    }).finally(() => {
      if (scope === version) workspaceLoading.value = false
    })
  }
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
.workspace-button { max-width: 180px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.workspace-hint { flex-basis: 100%; color: var(--muted); font-size: 11px; }
.composer-options { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; min-width: 0; }
.composer-skills { position: relative; }
.composer-skills summary { list-style: none; cursor: pointer; }
.composer-skills summary::-webkit-details-marker { display: none; }
.skill-picker {
  position: absolute;
  z-index: 2;
  inset: auto 0 calc(100% + 8px) auto;
  display: grid;
  gap: 8px;
  width: min(240px, 65vw);
  max-height: 230px;
  overflow: auto;
  padding: 12px;
}
.skill-picker p { margin: 0; font-size: 12px; }
.skill-picker label { display: flex; align-items: start; gap: 8px; overflow-wrap: anywhere; }
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
