<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as api from '../api/workbench'
import { errorCode, errorText } from '../api/java'
import { getAgentPresets, getModelProviders, type AgentPreset } from '../api/settings'
import { useSessionStore } from '../stores/sessions'
import { useWorkspaceStore } from '../stores/workspace'

const props = defineProps<{ knowledgeOnly?: boolean }>()
const route = useRoute()
const sessions = useSessionStore()
const workspace = useWorkspaceStore()
const spaces = ref<api.Space[]>([])
const prompts = ref<AgentPreset[]>([])
const skills = ref<AgentPreset[]>([])
const models = ref<{ id: string; label: string }[]>([])
const chatId = ref(api.newId())
const spaceId = ref('')
const modelId = ref('')
const skillIds = ref<string[]>([])
const composer = ref<HTMLTextAreaElement>()
const messageList = ref<HTMLDivElement>()
const draft = ref('')
const notice = ref('')
const javaNotice = ref('')
const loadingJava = ref(false)
const creatingWorkspaceChat = ref(false)
const collapsedGroups = ref(new Set<string>())
const activeRun = computed(() => sessions.runs.get(chatId.value))
const busy = computed(() => Boolean(activeRun.value))
const loadingHistory = computed(() => sessions.opening.has(chatId.value))
const localBusy = computed(() => activeRun.value?.local ?? false)
const phase = computed(() => activeRun.value?.phase ?? '')
const promptClosed = ref(false)
let autoSelectHistory = true
const targetDialog = ref<{ workspaceId: string; write: boolean; commands: boolean; inferred: string } | null>(null)
const workspaceToolLabels: Record<string, string> = {
  workspace_list: '浏览目录', workspace_read: '读取文件', workspace_write: '创建文件',
  workspace_replace: '修改文件', workspace_test: '运行测试',
}

const visibleSessions = computed(() => sessions.sessions.filter(item => Boolean(item.knowledgeOnly) === Boolean(props.knowledgeOnly)))
const sessionGroups = computed(() => {
  const groups = new Map<string, { path: string; label: string; sessions: api.Session[] }>()
  for (const session of visibleSessions.value) {
    const path = session.workspacePath || ''
    const key = normalizePath(path)
    let group = groups.get(key)
    if (!group) {
      group = { path, label: path ? directoryName(path) : props.knowledgeOnly ? '知识库对话' : '普通对话', sessions: [] }
      groups.set(key, group)
    }
    group.sessions.push(session)
  }
  return [...groups.entries()]
})
const workspaceActionsDisabled = computed(() => creatingWorkspaceChat.value || location.value.pending === 'choose' || !sessions.canSave())
const currentSession = computed(() => visibleSessions.value.find(item => item.id === chatId.value))
const messages = computed(() => currentSession.value ? sessions.messages(chatId.value) : [])
const selectedSpace = computed(() => spaces.value.find(item => item.id === spaceId.value))
const scopeName = computed(() => selectedSpace.value?.name || '已选知识库')
const location = computed(() => workspace.at(chatId.value))
const workspaceLabel = computed(() => currentSession.value?.workspacePath || location.value.path)
const workspaceName = computed(() => directoryName(workspaceLabel.value))
const otherWorkspaces = computed(() => {
  const dirs = new Map<string, { id: string; label: string; path: string }>()
  for (const session of [...sessions.sessions].reverse()) {
    const path = workspace.at(session.id).path
    if (!session.knowledgeOnly && path && !samePath(path, workspaceLabel.value || '') && !dirs.has(path.toLowerCase())) {
      dirs.set(path.toLowerCase(), { id: session.id, label: path.split(/[\\/]/).filter(Boolean).at(-1) || path, path })
    }
  }
  return [...dirs.values()]
})
const promptTerm = computed(() => draft.value.match(/(?:^|\s)\/([^\s/]*)$/u)?.[1]?.toLowerCase())
const matchingPrompts = computed(() => promptClosed.value || promptTerm.value === undefined
  ? [] : prompts.value.filter(item => item.name.toLowerCase().includes(promptTerm.value ?? '')))

watch([chatId, () => messages.value.length, () => messages.value.at(-1)?.content, phase], () => {
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}, { flush: 'post' })

function directoryName(path: string): string {
  return path.split(/[\\/]/).filter(Boolean).at(-1) || path
}

function normalizePath(path: string): string {
  const normalized = path.replaceAll('\\', '/').replace(/\/$/u, '')
  return /^[a-z]:/iu.test(normalized) ? normalized.toLowerCase() : normalized
}

function samePath(left: string, right: string): boolean {
  return normalizePath(left) === normalizePath(right)
}

function inferTarget(message: string): { workspaceId: string; inferred: string } | null {
  const normalize = (value: string) => value.normalize('NFKC').toLowerCase().replace(/\s+/gu, '').replaceAll('\\', '/')
  const text = normalize(message)
  const defaultLabel = normalize(workspaceName.value)
  const labels = [...text.matchAll(/工作区(?:编号)?([0-9]+|[一二三四五六七八九十]+)|工作区[“「"'《]([^”」"'》]+)[”」"'》]/gu)]
    .map(match => match[1] ? '工作区' + match[1] : match[2])
  const matches = otherWorkspaces.value.filter(target => {
    const folder = normalize(target.label)
    const directory = normalize(target.path).replace(/\/$/u, '')
    return text.includes(directory + '/') || text.endsWith(directory)
      || (folder !== defaultLabel && (labels.includes(folder) || labels.includes('工作区' + folder)
        || (!folder.startsWith('工作区') && ['工作区' + folder, folder + '工作区'].some(phrase => text.includes(phrase)))))
  })
  const external = labels.filter(label => label !== defaultLabel && '工作区' + label !== defaultLabel)
  if (matches.length === 1 && external.every(label => [normalize(matches[0].label), '工作区' + normalize(matches[0].label)].includes(label))) {
    return { workspaceId: matches[0].id, inferred: matches[0].label }
  }
  const defaultPath = normalize(workspaceLabel.value || '').replace(/\/$/u, '')
  const externalPath = [...text.matchAll(/[a-z]:\/[^\s，。；、！？“”"'<>|]+/gu)]
    .some(match => match[0] !== defaultPath && !match[0].startsWith(defaultPath + '/'))
  if (matches.length || external.length || externalPath || /其他工作区|另一个工作区|外部工作区|工作区外|跨工作区/u.test(text)) {
    return { workspaceId: '', inferred: external.join('、') || '其他工作区' }
  }
  return null
}

function requestTarget() {
  const detected = inferTarget(draft.value)
  targetDialog.value = { workspaceId: detected?.workspaceId || '', inferred: detected?.inferred || '',
    write: /修改|创建|编辑|新建|写入|删除|保存|生成|修复|重构|改动|更新|实现|\/write\b|\b(?:write|edit|fix|create|update)\b/iu.test(draft.value),
    commands: /执行命令|跑.{0,5}测试|运行|编译|验证|\/exec\b|\btest\b/iu.test(draft.value) }
}

function confirmTarget() {
  const target = targetDialog.value
  if (!target) return
  targetDialog.value = null
  void send({ workspaceId: target.workspaceId || undefined, access: { write: target.write, commands: target.commands } })
}

function select(session: api.Session) {
  autoSelectHistory = false
  targetDialog.value = null
  chatId.value = session.id
  spaceId.value = session.spaceId
  modelId.value = session.modelProviderId || ''
  void sessions.openShared(session)
  draft.value = ''
  notice.value = ''
}
function newChat() {
  autoSelectHistory = false
  targetDialog.value = null
  chatId.value = api.newId()
  if (!props.knowledgeOnly) spaceId.value = ''
  draft.value = ''
  notice.value = ''
  nextTick(() => composer.value?.focus())
}
async function chooseWorkspace() {
  if (workspaceLabel.value || !sessions.canSave()) return
  const id = chatId.value
  await bindChatWorkspace(id, spaceId.value)
}

function toggleGroup(key: string) {
  if (collapsedGroups.value.has(key)) collapsedGroups.value.delete(key)
  else collapsedGroups.value.add(key)
}

async function createWorkspaceChat(source?: api.Session) {
  if (workspaceActionsDisabled.value) return
  const previous = chatId.value
  const id = api.newId()
  creatingWorkspaceChat.value = true
  notice.value = ''
  try {
    const session = await bindChatWorkspace(id, '', source?.id)
    if (session?.workspacePath) collapsedGroups.value.delete(normalizePath(session.workspacePath))
    if (chatId.value !== previous) return
    if (session) {
      select(session)
      nextTick(() => composer.value?.focus())
    } else notice.value = workspace.at(id).error
  } finally {
    creatingWorkspaceChat.value = false
  }
}

async function bindChatWorkspace(id: string, knowledgeId: string, fromWorkspaceId?: string) {
  await workspace.choose(id, fromWorkspaceId)
  const selected = workspace.at(id).path
  if (selected) {
    const session = sessions.ensure({ id, title: '新会话', spaceId: knowledgeId })
    session.workspacePath = selected
    await sessions.save(id).catch(() => {})
    return session
  }
}
async function removeSession() {
  const session = currentSession.value
  if (!session || busy.value || loadingHistory.value || location.value.pending) return
  if (!window.confirm(session.sharedChatId ? '删除此共享会话？Web 端中的记录也会删除。' : '删除此会话并清理它的沙箱？')) return
  try {
    if (!session.knowledgeOnly) await workspace.release(session.id)
    await sessions.remove(session.id)
    if (chatId.value === session.id) newChat()
  } catch (error) {
    if (chatId.value === session.id) notice.value = errorText(error)
  }
}
function insertPrompt(prompt: AgentPreset) {
  draft.value = draft.value.replace(/\/[^\s/]*$/u, () => prompt.content)
  nextTick(() => composer.value?.focus())
}

async function refreshJava() {
  if (loadingJava.value) return
  loadingJava.value = true
  javaNotice.value = ''
  try {
    const knowledgeRequest = api.getKnowledgeSpaces()
    void knowledgeRequest.then(
      items => sessions.refreshShared(['', ...items.filter(item => !item.deleting).map(item => item.id)]),
      () => sessions.refreshShared(['']),
    ).then(() => { if (currentSession.value) void sessions.openShared(currentSession.value) })
    const [knowledge, modelSettings, skillList, promptList] = await Promise.allSettled([
      knowledgeRequest, getModelProviders(), getAgentPresets('skills'), getAgentPresets('prompt-templates'),
    ])
    const failure = [knowledge, modelSettings, skillList, promptList].find(item => item.status === 'rejected')
    if (failure?.status === 'rejected') javaNotice.value = errorText(failure.reason)
    if (knowledge.status === 'fulfilled') {
      spaces.value = knowledge.value.filter(space => !space.deleting)
      if (!currentSession.value && !selectedSpace.value) spaceId.value = ''
    }
    if (modelSettings.status === 'fulfilled') {
      const settings = modelSettings.value
      models.value = settings.providers.filter(row => row.enabled && row.hasApiKey && row.protocol !== 'OPENAI_EMBEDDING')
        .sort((left, right) => left.priority - right.priority).map(row => ({ id: row.id, label: row.model }))
      if (settings.openCodeFree?.hasApiKey) models.value.push(...settings.openCodeFree.models.map(model => ({ id: 'opencode-free:' + model, label: model })))
      if (!models.value.some(model => model.id === modelId.value)) modelId.value = ''
    }
    if (skillList.status === 'fulfilled') {
      skills.value = skillList.value.filter(item => item.enabled)
      skillIds.value = skillIds.value.filter(id => skills.value.some(skill => skill.id === id))
    }
    if (promptList.status === 'fulfilled') prompts.value = promptList.value.filter(item => item.enabled)
  } finally {
    loadingJava.value = false
  }
}

function outcomeLabel(message: api.ChatMessage): string {
  switch (message.outcome) {
    case 'completed': return message.receipts?.some(item => !item.ok) ? '执行结束 · 有工具失败' : '已完成'
    case 'failed': return '执行失败'
    case 'stopped': return '已停止'
    case 'incomplete': return '结果未确认'
    default: return ''
  }
}

function confirmedResult(receipts: api.WorkspaceReceipt[]): string {
  const successful = receipts.filter(item => item.ok)
  const written = [...new Set(successful.filter(item => item.operation === 'workspace_write' || item.operation === 'workspace_replace').map(item => item.path))]
  const tests = receipts.filter(item => item.operation === 'workspace_test')
  const parts: string[] = []
  if (written.length) parts.push('已写入 ' + written.join('、'))
  if (tests.length) parts.push('测试通过 ' + tests.filter(item => item.ok).length + '/' + tests.length + ' 项')
  if (!parts.length) {
    const read = new Set(successful.filter(item => item.operation === 'workspace_read').map(item => item.path)).size
    const listed = successful.filter(item => item.operation === 'workspace_list').length
    if (read) parts.push('已读取 ' + read + ' 个文件')
    if (listed) parts.push('已浏览目录 ' + listed + ' 次')
  }
  return parts.length ? parts.join('，') + '。' : '暂无成功操作的沙箱回执。'
}

function localRequest(message: string): boolean {
  return /^(?:\/(?:exec|ls|read|write)(?:\s|$)|执行命令：|读取文件：|列出目录：|写入文件：)/u.test(message.trimStart())
}
async function stopAnswer(id = chatId.value) {
  const request = sessions.runs.get(id)
  if (!request || request.local) return
  request.polling.abort()
  try { await api.cancelAgent(request.id) }
  catch (error) { if (chatId.value === id) notice.value = errorText(error) }
}
async function send(target?: api.WorkspaceTargetRequest) {
  const message = draft.value
  const compact = message.trim()
  if (busy.value || loadingHistory.value || (!props.knowledgeOnly && location.value.pending) || !compact) return
  if (!target && !props.knowledgeOnly && workspaceLabel.value && inferTarget(compact)) {
    requestTarget()
    return
  }
  if (!sessions.canSave()) {
    notice.value = '桌面会话库未连接，请重试存储后再发送。'
    return
  }
  const local = localRequest(message)
  if (props.knowledgeOnly && (!selectedSpace.value || local)) {
    notice.value = local ? '请在 Agent 问答中执行工作区操作。' : '请选择可用的知识库后再提问。'
    return
  }
  const workspaceState = location.value
  const canRouteWorkspace = !props.knowledgeOnly

  const session = sessions.ensure({
    id: chatId.value, title: compact.slice(0, 32), spaceId: spaceId.value, knowledgeOnly: props.knowledgeOnly,
  })
  const thread = sessions.messages(session.id)
  const provider = modelId.value || undefined
  session.modelProviderId = provider
  const chosenSkills = [...skillIds.value]
  const recentRequests = canRouteWorkspace ? thread.filter(item => item.role === 'user').slice(-3).map(item => item.content.slice(0, 700)) : undefined
  if (!thread.length && !session.sharedChatId) {
    session.title = compact.slice(0, 32)
    session.spaceId = spaceId.value
  }
  if (!props.knowledgeOnly && !session.workspacePath && workspaceState.path) session.workspacePath = workspaceState.path
  notice.value = ''
  draft.value = ''
  const localOnly = local || !!target
  thread.push({ id: api.newId(), role: 'user', content: message, localOnly },
    { id: api.newId(), role: 'assistant', content: '', steps: [], localOnly })
  const input = thread[thread.length - 2]
  const output = thread[thread.length - 1]
  sessions.runs.set(session.id, {
    id: api.newId(), polling: new AbortController(), local,
    phase: local ? '准备 Docker 工作区操作' : canRouteWorkspace ? '判断是否使用工作区' : '检查 Java 服务并识别意图',
  })
  const request = sessions.runs.get(session.id)!
  let task: api.TaskRecord | undefined

  try {
    await sessions.save(session.id)
    request.polling.signal.throwIfAborted()
    await api.streamAgentDispatch({
      requestId: request.id, message, chatId: session.id, sharedChatId: session.sharedChatId || undefined, spaceId: session.spaceId || undefined,
      modelProviderId: provider, skillIds: chosenSkills, knowledgeOnly: props.knowledgeOnly, recentRequests,
      targetWorkspace: target,
    }, ({ event, data }) => {
      sessions.schedule(session.id)
      switch (event) {
        case 'run': output.runId = data.runId; break
        case 'workspace_ready':
          workspaceState.sandbox = data
          workspaceState.error = ''
          if (data.defaultWorkspace) output.outputDirectory = data.workspacePath
          break
        case 'target_workspace_ready': output.targetWorkspace = data; output.workspace = true; break
        case 'workspace_cleanup_failed': output.cleanupWarning = data.message; break
        case 'workspace_receipt': {
          output.workspace = true
          const receipts = output.receipts ??= []
          receipts.push(data)
          break
        }
        case 'route':
          output.workspace = data.strategy === 'WORKSPACE_TEAM'
          if (output.workspace) input.localOnly = output.localOnly = true
          output.route = output.workspace ? (data.workspaceRoles?.length === 1 ? '工作区 Agent' : '工作区多 Agent')
            : data.strategy === 'SANDBOX' ? 'Docker 工作区' : data.requiresWorkflow ? '工作流' : data.strategy || 'Java Agent'
          break
        case 'thinking':
          request.phase = data.name?.trim() || (data.step ? '执行第 ' + data.step + ' 步' : '准备任务')
          output.steps?.push(request.phase)
          break
        case 'tools': {
          const names = data.names.filter(name => name.trim()).map(name => workspaceToolLabels[name] || name).join('、')
          if (!names) break
          const step = output.workspace ? names : '工具：' + names
          if (step && output.steps?.at(-1) !== step) output.steps?.push(step)
          break
        }
        case 'delta': output.content += data.text; break
        case 'task':
          task = data
          output.taskId = data.id
          request.phase = '等待 Java 工作流'
          break
        case 'done':
          output.sources = data.sources
          if (!task && data.status === 'FAILED') output.outcome = 'failed'
          break
      }
    })
    if (!local) javaNotice.value = ''
    if (task) {
      const result = await api.waitForTask(task, request.polling.signal, status => {
        request.phase = status.stage || '工作流进度 ' + (status.progress ?? 0) + '%'
      })
      if (result.status === 'COMPLETED' && result.result && typeof result.result === 'object' && Array.isArray(result.result.sources)) {
        output.sources = result.result.sources as api.KnowledgeSource[]
      }
      const text = api.taskDone(result) ? api.taskText(result) : '工作流仍在运行，可打开关联任务查看进度。'
      output.content += (output.content ? '\n\n' : '') + text
      if (result.status === 'FAILED') output.outcome = 'failed'
      else if (['CANCELLED', 'CANCELED'].includes(result.status)) output.outcome = 'stopped'
      else if (!api.taskDone(result)) output.outcome = 'incomplete'
    }
    output.outcome ??= 'completed'
    if (!output.localOnly && output.outcome === 'completed') session.sharedChatId ||= session.id
    if (!output.content.trim() && !output.receipts?.length) output.outcomeDetail = '本轮没有返回可展示的结果。'
  } catch (error) {
    const code = errorCode(error)
    const declined = code === 'target_workspace_declined'
    const cancelled = request.polling.signal.aborted || code === 'agent_cancelled' || declined
    const interrupted = ['java_unavailable', 'java_timeout', 'java_response', 'agent_delivery_incomplete'].includes(code)
    output.outcome = cancelled ? 'stopped' : interrupted ? 'incomplete' : 'failed'
    output.outcomeDetail = declined ? errorText(error) : cancelled
      ? (task ? '已停止等待，后台工作流状态可在运行日志中查看。' : '已停止后续执行。下方保留已收到的执行结果。')
      : errorText(error)
    if (!cancelled && !local && (interrupted || code === 'java_unauthorized')) javaNotice.value = output.outcomeDetail
  } finally {
    if (!target && (local || output.workspace)) {
      workspaceState.revision++
      if (!local && !workspaceState.sandbox) void workspace.refreshSandbox(session.id).catch(() => {})
    }
    await sessions.save(session.id).catch(() => {})
    sessions.runs.delete(session.id)
    if (output.outcome === 'completed' && !output.localOnly) void sessions.openShared(session)
  }
}
onMounted(() => {
  const requested = route.query.spaceId
  if (typeof requested === 'string') spaceId.value = requested
  else if (visibleSessions.value[0]) select(visibleSessions.value[0])
  void refreshJava()
})
watch(visibleSessions, items => {
  if (autoSelectHistory && !currentSession.value && !draft.value && items.length && !route.query.spaceId && !workspace.at(chatId.value).path) select(items[0])
}, { flush: 'post' })
</script>

<template>
  <main class="agent-page">
    <aside class="session-rail" aria-label="会话列表">
      <div class="session-actions">
        <button class="session-action" type="button" :disabled="location.pending === 'choose'" @click="newChat">
          <span aria-hidden="true">＋</span> 新建对话
        </button>
        <button v-if="!knowledgeOnly" class="session-action" type="button" :disabled="workspaceActionsDisabled" @click="createWorkspaceChat()">
          <span class="folder-icon" aria-hidden="true"></span> 添加工作区
        </button>
      </div>
      <div class="row spread session-heading">
        <h2>会话</h2>
        <button class="session-refresh" type="button" :disabled="loadingJava || sessions.syncing" @click="refreshJava">
          {{ sessions.syncing ? '同步中…' : '同步历史' }}
        </button>
      </div>
      <p v-if="!visibleSessions.length" class="muted">暂无会话</p>
      <div v-else class="session-list">
        <section v-for="[key, group] in sessionGroups" :key="key" class="session-group" :aria-label="group.label">
          <div class="session-group-header">
            <button type="button" class="session-group-toggle" :aria-expanded="!collapsedGroups.has(key)"
              :title="group.path || group.label" @click="toggleGroup(key)">
              <span class="group-chevron" aria-hidden="true"></span>
              <span v-if="group.path" class="folder-icon" aria-hidden="true"></span>
              <span class="truncate">{{ group.label }}</span>
            </button>
            <button v-if="group.path" type="button" class="group-add" :disabled="workspaceActionsDisabled"
              :aria-label="'在 ' + group.label + ' 新建对话'" title="在此工作区新建对话" @click="createWorkspaceChat(group.sessions[0])">＋</button>
          </div>
          <div v-if="!collapsedGroups.has(key)" class="session-group-items">
            <button v-for="session in group.sessions" :key="session.id" type="button" class="item-button session-item"
              :aria-pressed="session.id === chatId" :disabled="location.pending === 'choose'" :title="session.title" @click="select(session)">
              <span class="truncate">{{ session.title }}</span>
              <span v-if="sessions.runs.has(session.id)" class="badge">运行中</span>
              <span v-else-if="session.sharedChatId" class="badge">共享</span>
            </button>
          </div>
        </section>
      </div>
      <button v-if="currentSession" type="button" class="danger session-delete" :disabled="busy || loadingHistory || !!location.pending" @click="removeSession">删除当前会话</button>
    </aside>
    <section class="conversation" :class="{ 'is-start': !messages.length }" aria-label="当前会话">
      <div v-if="javaNotice" class="java-notice row spread" role="status">
        <span>{{ javaNotice }}</span>
        <button type="button" :disabled="loadingJava" @click="refreshJava">重新连接 Java</button>
      </div>
      <p v-if="sessions.historyError" class="muted" role="status">共享历史暂不可用，已保存的对话仍可查看。{{ sessions.historyError }}</p>
      <p v-if="loadingHistory" class="muted" role="status">正在同步会话历史…</p>
      <header v-if="!messages.length" class="agent-welcome">
        <h1>{{ knowledgeOnly ? '知识库问答' : '想让 Agent 做什么？' }}</h1>
      </header>
      <div v-else ref="messageList" class="messages" aria-label="对话消息">
        <article v-for="item in messages" :key="item.id" class="bubble stack" :data-role="item.role">
          <div class="row"><strong>{{ item.role === 'user' ? '你' : 'Agent' }}</strong><span v-if="item.route" class="badge">{{ item.route }}</span>
            <span v-if="item.outcome" class="badge outcome-badge" :data-outcome="item.outcome">{{ outcomeLabel(item) }}</span>
          </div>
          <p v-if="item.outputDirectory" class="muted target-path">
            默认文件目录 <code>{{ item.outputDirectory }}</code>
          </p>
          <p v-if="item.targetWorkspace" class="muted target-path">
            本轮工作区 <code>{{ item.targetWorkspace.path }}</code> · {{ item.targetWorkspace.write ? '读写' : '只读' }}
            · {{ item.targetWorkspace.commands ? '允许命令' : '禁止命令' }}
          </p>
          <p v-if="item.cleanupWarning" class="error" role="alert">{{ item.cleanupWarning }}</p>
          <p v-if="item.content.trim()" class="message-text">{{ item.content.trimEnd() }}</p>
          <p v-if="item.outcomeDetail" class="muted">{{ item.outcomeDetail }}</p>
          <p v-if="item.receipts?.length && (!item.content.trim() || item.outcome !== 'completed')" class="message-text">
            {{ confirmedResult(item.receipts) }}
          </p>
          <p v-if="busy && item === messages.at(-1)" class="row muted" role="status"><span class="spinner" aria-hidden="true"></span>{{ phase }}</p>
          <details v-if="item.receipts?.length" :open="Boolean(item.outcome && (item.outcome !== 'completed' || !item.content.trim()))">
            <summary>沙箱执行结果（{{ item.receipts.length }}）</summary>
            <ul class="receipt-list">
              <li v-for="(receipt, index) in item.receipts" :key="index">
                <span>{{ workspaceToolLabels[receipt.operation] || receipt.operation }}</span>
                <code v-if="receipt.path">{{ receipt.path }}</code>
                <small>{{ receipt.ok ? '成功' : '失败' }}<template v-if="receipt.exitCode !== null"> · 退出码 {{ receipt.exitCode }}</template></small>
                <pre v-if="receipt.stdout || receipt.stderr || receipt.error">{{ [receipt.stdout, receipt.stderr, receipt.error].filter(Boolean).join('\n') }}</pre>
              </li>
            </ul>
          </details>
          <details v-if="item.steps?.length" :open="busy && item === messages.at(-1) && !item.workspace">
            <summary>执行路径</summary>
            <ol><li v-for="(step, index) in item.steps" :key="index">{{ step }}</li></ol>
          </details>
          <details v-if="item.sources?.length" class="message-sources">
            <summary>知识库来源（{{ item.sources.length }}）</summary>
            <div class="stack">
              <blockquote v-for="source in item.sources" :key="source.reference">
                <strong>{{ source.reference }} {{ source.source }}</strong>
                <p>{{ source.excerpt }}</p>
              </blockquote>
            </div>
          </details>
          <RouterLink v-if="item.taskId" :to="{ path: '/agent-runs', query: { taskId: item.taskId } }">查看关联任务</RouterLink>
          <RouterLink v-else-if="item.runId" :to="{ path: '/agent-runs', query: { runId: item.runId } }">查看本次运行</RouterLink>
        </article>
      </div>
      <div class="composer-area">
        <form class="agent-composer" @submit.prevent="targetDialog ? undefined : send()">
          <div v-if="matchingPrompts.length" class="prompt-picker row" aria-label="可插入的 Prompt">
            <button v-for="prompt in matchingPrompts" :key="prompt.id" type="button" @click="insertPrompt(prompt)">{{ prompt.name }}</button>
          </div>
          <textarea ref="composer" v-model="draft" rows="3" aria-label="输入消息"
            :placeholder="knowledgeOnly ? '就知识库中的资料提问，输入 / 插入 Prompt' : '描述你想完成的任务，输入 / 插入 Prompt'"
            @input="promptClosed = false; targetDialog = null" @keydown.esc="promptClosed = true; targetDialog = null" @keydown.ctrl.enter.prevent="targetDialog ? undefined : send()" />
          <section v-if="targetDialog" class="target-permission stack" aria-label="本轮工作区授权" role="region">
            <strong>本轮使用其他工作区</strong>
            <p v-if="targetDialog.inferred" class="muted">消息中的目标为 {{ targetDialog.inferred }}，请核对目录。</p>
            <label class="field">执行目录
              <select v-model="targetDialog.workspaceId">
                <option value="">从本机选择目录…</option>
                <option v-for="target in otherWorkspaces" :key="target.id" :value="target.id">{{ target.path }}</option>
              </select>
            </label>
            <div class="row">
              <label><input v-model="targetDialog.write" type="checkbox" /> 允许修改文件</label>
              <label><input v-model="targetDialog.commands" type="checkbox" /> 允许运行命令或测试</label>
            </div>
            <div class="row">
              <button type="button" class="primary" :disabled="!draft.trim()" @click="confirmTarget">继续授权</button>
              <button type="button" @click="targetDialog = null">取消</button>
            </div>
          </section>
          <div class="composer-bar">
            <div class="composer-options" aria-label="会话选项">
              <template v-if="!knowledgeOnly">
                <span v-if="workspaceLabel" class="composer-chip" :title="workspaceLabel">
                  ▱ <span class="truncate">{{ workspaceName }}</span>
                </span>
                <button v-if="!workspaceLabel && !location.sandbox" type="button" class="composer-chip"
                  :disabled="busy || !!location.pending || !sessions.canSave()" @click="chooseWorkspace">
                  {{ location.pending === 'choose' ? '选择中…' : '选择工作区' }}
                </button>
                <button v-if="workspaceLabel" type="button" class="composer-chip" :disabled="busy || !!location.pending"
                  @click="requestTarget">本轮授权其他目录</button>
              </template>
              <select v-model="modelId" class="composer-chip model-select" aria-label="切换模型" :disabled="busy">
                <option value="">模型 · 自动选择</option>
                <option v-for="model in models" :key="model.id" :value="model.id">{{ model.label }}</option>
              </select>
              <select v-if="!messages.length && (knowledgeOnly || spaces.length)" v-model="spaceId" class="composer-chip"
                aria-label="选择知识库" :disabled="busy || !spaces.length">
                <option value="" :disabled="knowledgeOnly">{{ knowledgeOnly ? '选择知识库' : '不使用知识库' }}</option>
                <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option>
              </select>
              <span v-else-if="currentSession?.spaceId" class="composer-chip truncate" :title="scopeName">{{ scopeName }}</span>
              <details v-if="skills.length" class="composer-skills">
                <summary class="composer-chip">Skills{{ skillIds.length ? ' · ' + skillIds.length : '' }}</summary>
                <div class="skills-panel stack">
                  <label v-for="skill in skills" :key="skill.id" class="field inline">
                    <input v-model="skillIds" type="checkbox" :value="skill.id" :disabled="busy || (skillIds.length >= 5 && !skillIds.includes(skill.id))" />
                    {{ skill.name }}
                  </label>
                </div>
              </details>
            </div>
            <button v-if="busy && !localBusy" class="send-action" type="button" @click="stopAnswer()">停止等待</button>
            <button v-else-if="busy" class="send-action" type="button" disabled>容器执行中</button>
            <button v-else type="submit" class="send-action primary" :disabled="loadingHistory || !!targetDialog || !draft.trim() || (knowledgeOnly ? !selectedSpace : !!location.pending)"
              aria-label="发送" title="发送（Ctrl + Enter）">↑</button>
          </div>
          <div v-if="!knowledgeOnly && (workspaceLabel || location.sandbox)" class="workspace-preview row">
            <span class="truncate" :title="workspaceLabel || location.sandbox?.workspacePath">
              {{ workspaceLabel || location.sandbox?.workspacePath }}
            </span>
            <button v-if="!location.sandbox" type="button" :disabled="busy || !!location.pending || !workspace.docker?.available"
              @click="workspace.create(chatId)">
              {{ location.pending === 'create' ? '创建中…' : '启动沙箱' }}
            </button>
            <button v-else type="button" :disabled="busy || !!location.pending" @click="workspace.stop(chatId)">
              {{ location.pending === 'stop' ? '清理中…' : location.sandbox.status === 'running' ? '停止沙箱' : '清理沙箱' }}
            </button>
            <RouterLink v-if="!workspace.docker?.available" to="/workspace">查看 Docker 状态</RouterLink>
          </div>
        </form>
        <p v-if="knowledgeOnly && !loadingJava && currentSession && !selectedSpace && !javaNotice" class="muted composer-note">
          此会话的知识库不可用，请新建会话后选择其他知识库。
        </p>
        <p v-else-if="knowledgeOnly && !loadingJava && !spaces.length && !javaNotice" class="muted composer-note">
          当前没有可用知识库。<RouterLink to="/knowledge-base">管理知识库</RouterLink>
        </p>
        <p v-if="!knowledgeOnly && location.error" class="error composer-note" role="alert">{{ location.error }}</p>
        <p v-if="notice" class="error composer-note" role="alert">{{ notice }}</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.target-permission { padding: .75rem; border-block: 1px solid var(--line, #ddd); }
.target-permission select { max-width: 100%; }
.target-path { overflow-wrap: anywhere; }
.agent-page { display: grid; grid-template-columns: clamp(166px, 22vw, 234px) minmax(0, 1fr); height: 100%; min-height: 0; }
.session-rail { display: flex; flex-direction: column; gap: .65rem; min-width: 0; min-height: 0; padding: .8rem .65rem; border-right: 1px solid var(--line); background: #f5f8f7; }
.session-actions { display: grid; gap: .2rem; }
.session-action, .session-group-toggle, .session-refresh, .group-add {
  display: flex; align-items: center; gap: .45rem; min-width: 0; padding: .4rem .45rem;
  border: 1px solid transparent; background: transparent; text-align: left;
}
.session-action:hover, .session-group-toggle:hover, .session-refresh:hover, .group-add:hover { background: #e8efed; }
.session-action { font-weight: 600; }
.session-heading { gap: .25rem; padding-inline: .35rem; }
.session-heading h2, .session-refresh { font-size: .78rem; color: var(--muted); }
.session-list { display: grid; align-content: start; gap: .45rem; min-height: 0; overflow: auto; }
.session-group { min-width: 0; }
.session-group-header { display: flex; align-items: center; min-width: 0; }
.session-group-toggle { flex: 1; color: var(--muted); font-size: .82rem; font-weight: 600; }
.group-add { flex: none; color: var(--muted); }
.group-chevron { width: .4rem; height: .4rem; flex: none; margin-inline: .12rem; border-right: 1px solid currentColor; border-bottom: 1px solid currentColor; transform: rotate(-45deg); }
.session-group-toggle[aria-expanded='true'] .group-chevron { transform: rotate(45deg) translateY(-.08rem); }
.folder-icon { position: relative; display: inline-block; flex: none; width: .95rem; height: .7rem; border: 1px solid currentColor; border-radius: .12rem; }
.folder-icon::before { position: absolute; content: ''; left: 0; top: -.25rem; width: .38rem; height: .23rem; border: 1px solid currentColor; border-bottom: 0; border-radius: .12rem .12rem 0 0; }
.session-group-items { display: grid; gap: .12rem; padding-left: .75rem; }
.session-item { display: flex; align-items: center; justify-content: space-between; gap: .35rem; min-width: 0; padding: .36rem .45rem; border-color: transparent; background: transparent; font-size: .84rem; }
.session-item .badge { flex: none; }
.session-delete { margin-top: auto; }
.conversation { display: flex; flex-direction: column; min-width: 0; min-height: 0; padding: 0 clamp(.75rem, 3vw, 2rem); }
.conversation.is-start { overflow: auto; }
.java-notice { align-self: center; width: min(100%, 920px); padding: .45rem 0; color: var(--muted); font-size: .85rem; }
.agent-welcome { padding: 1.5rem 0 1.1rem; margin-top: auto; text-align: center; }
.agent-welcome h1 { font-size: clamp(1.35rem, 3.5vw, 2.1rem); letter-spacing: -.025em; }
.messages { display: grid; gap: .85rem; flex: 1; width: min(100%, 920px); margin-inline: auto; overflow: auto; min-height: 0; align-content: start; padding: 1rem .2rem; }
.bubble { min-width: 0; padding: .75rem .9rem; border: 1px solid var(--line); border-radius: .75rem; background: var(--surface); }
.bubble[data-role='user'] { justify-self: end; max-width: 87%; border-color: #cfe3dc; background: #eaf4ef; }
.message-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.message-sources { overflow-wrap: anywhere; }
.message-sources blockquote p { white-space: pre-wrap; }
.composer-area { width: min(100%, 920px); margin: 0 auto; padding: .5rem 0 1rem; }
.is-start .composer-area { max-width: 780px; margin-bottom: auto; }
.agent-composer { position: relative; display: grid; gap: .35rem; padding: .65rem .7rem .55rem; border: 1px solid #c9d7d8; border-radius: 1rem; background: var(--surface); box-shadow: 0 6px 20px #213e3810; }
.agent-composer:focus-within { border-color: #83ab9f; }
.agent-composer textarea { width: 100%; min-height: 3.2rem; max-height: min(32vh, 14rem); padding: .3rem; border: 0; outline: none; }
.composer-bar { display: flex; align-items: flex-end; gap: .5rem; justify-content: space-between; min-width: 0; }
.composer-options { display: flex; align-items: center; flex-wrap: wrap; gap: .25rem; min-width: 0; }
.composer-chip { display: inline-flex; align-items: center; gap: .3rem; min-width: 0; max-width: min(100%, 12rem); border: 1px solid transparent; border-radius: .5rem; padding: .3rem .4rem; color: var(--muted); background: #f2f6f5; text-decoration: none; font-size: .82rem; }
select.composer-chip { display: inline-block; text-overflow: ellipsis; }
.composer-chip:hover { color: var(--ink); }
.truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.send-action { flex: none; padding: .38rem .65rem; border-radius: .6rem; }
.send-action.primary { display: grid; place-items: center; min-width: 2.2rem; min-height: 2.2rem; padding: 0; font-size: 1.35rem; line-height: 1; }
.composer-skills { position: relative; }
.composer-skills summary { list-style: none; }
.skills-panel, .prompt-picker { position: absolute; left: 0; bottom: calc(100% + .4rem); z-index: 2; width: max-content; max-width: min(100%, 24rem); padding: .45rem; border: 1px solid var(--line); border-radius: .5rem; background: var(--surface); box-shadow: 0 6px 20px #213e3818; }
.skills-panel { width: max-content; max-width: min(230px, 50vw); margin: 0; }
.workspace-preview { padding-top: .4rem; border-top: 1px solid var(--line); font-size: .85rem; }
.workspace-preview span { min-width: 0; }
.composer-note { margin: .38rem 0 0; font-size: .85rem; }
ol { margin: .3rem 0 0; padding-left: 1.35rem; }
.outcome-badge[data-outcome="failed"] { background: #f8e6e6; color: #8a2f2f; }
.outcome-badge[data-outcome="stopped"], .outcome-badge[data-outcome="incomplete"] { background: #f7efdb; color: #77551c; }
.receipt-list { display: grid; gap: .45rem; padding-left: 1.2rem; }
.receipt-list li > :is(code, small) { margin-left: .45rem; overflow-wrap: anywhere; }
.receipt-list pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: .25rem 0; }
.spinner { width: 1rem; height: 1rem; border: 2px solid #acd2c8; border-top-color: var(--brand); border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
@media (max-width: 760px) {
  .agent-page { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); }
  .session-rail { padding: .55rem .75rem; border-right: 0; border-bottom: 1px solid var(--line); }
  .session-actions { display: flex; flex-wrap: wrap; }
  .session-list { max-height: 24vh; }
  .session-delete { align-self: flex-start; margin-top: 0; }
}
</style>
