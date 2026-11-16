<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as api from '../api/workbench'
import { errorCode, errorText } from '../api/java'
import { getAgentPresets, getModelProviders, type AgentPreset } from '../api/settings'
import PageHeading from '../components/PageHeading.vue'
import { useSessionStore } from '../stores/sessions'
import { useWorkspaceStore } from '../stores/workspace'

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
const busy = ref(false)
const localBusy = ref(false)
const phase = ref('')
const promptClosed = ref(false)
let activeRequest: { id: string; polling: AbortController } | undefined

const currentSession = computed(() => sessions.sessions.find(item => item.id === chatId.value))
const messages = computed(() => currentSession.value ? sessions.messages(chatId.value) : [])
const scopeName = computed(() => spaces.value.find(item => item.id === spaceId.value)?.name || (spaceId.value ? '已选知识库' : '不使用知识库'))
const activeWorkspacePath = computed(() => workspace.sandbox?.status === 'running' ? workspace.sandbox.workspacePath : undefined)
const workspaceLabel = computed(() => currentSession.value?.workspacePath || activeWorkspacePath.value)
const promptTerm = computed(() => draft.value.match(/(?:^|\s)\/([^\s/]*)$/u)?.[1]?.toLowerCase())
const matchingPrompts = computed(() => promptClosed.value || promptTerm.value === undefined
  ? [] : prompts.value.filter(item => item.name.toLowerCase().includes(promptTerm.value ?? '')))

watch([() => messages.value.length, () => messages.value.at(-1)?.content, phase], () => {
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}, { flush: 'post' })

function select(session: api.Session) {
  chatId.value = session.id
  spaceId.value = session.spaceId
  draft.value = ''
  notice.value = ''
}
function newChat() {
  chatId.value = api.newId()
  spaceId.value = ''
  draft.value = ''
  notice.value = ''
  nextTick(() => composer.value?.focus())
}
function removeSession() {
  if (!currentSession.value || !window.confirm('删除此桌面会话？')) return
  sessions.remove(chatId.value)
  newChat()
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
    const [knowledge, modelSettings, skillList, promptList] = await Promise.allSettled([
      api.getKnowledgeSpaces(), getModelProviders(), getAgentPresets('skills'), getAgentPresets('prompt-templates'),
    ])
    const failure = [knowledge, modelSettings, skillList, promptList].find(item => item.status === 'rejected')
    if (failure?.status === 'rejected') javaNotice.value = errorText(failure.reason)
    if (knowledge.status === 'fulfilled') spaces.value = knowledge.value.filter(space => !space.deleting)
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

function localRequest(message: string): boolean {
  return /^(?:\/(?:exec|ls|read|write)(?:\s|$)|执行命令：|读取文件：|列出目录：|写入文件：)/u.test(message.trimStart())
}
async function stopAnswer() {
  if (!activeRequest || localBusy.value) return
  activeRequest.polling.abort()
  try { await api.cancelAgent(activeRequest.id) }
  catch (error) { notice.value = errorText(error) }
}
async function send() {
  const message = draft.value
  const compact = message.trim()
  if (busy.value || !compact) return
  const local = localRequest(message)
  const active = workspace.sandbox?.status === 'running' ? workspace.sandbox : null
  const bound = currentSession.value?.workspacePath
  const sandbox = active && (!bound || bound === active.workspacePath) ? active : null
  if (local && bound && active && !sandbox) {
    notice.value = '该会话已绑定另一个工作区，请重新启动原工作区的沙箱。'
    return
  }

  const session = sessions.ensure({ id: chatId.value, title: compact.slice(0, 32), spaceId: spaceId.value, workspacePath: sandbox?.workspacePath })
  if (local && sandbox && !session.workspacePath) session.workspacePath = sandbox.workspacePath
  busy.value = true
  localBusy.value = local
  notice.value = ''
  draft.value = ''
  phase.value = local ? '准备 Docker 工作区操作' : '检查 Java 服务并识别意图'
  const thread = sessions.messages(session.id)
  thread.push({ id: api.newId(), role: 'user', content: message }, { id: api.newId(), role: 'assistant', content: '', steps: [] })
  const output = thread[thread.length - 1]
  const request = { id: api.newId(), polling: new AbortController() }
  activeRequest = request
  let task: api.TaskRecord | undefined

  try {
    await api.streamAgentDispatch({
      requestId: request.id, message, chatId: session.id, spaceId: session.spaceId || undefined,
      modelProviderId: modelId.value || undefined, skillIds: [...skillIds.value], sandboxId: sandbox?.id,
    }, ({ event, data }) => {
      switch (event) {
        case 'run': output.runId = data.runId; break
        case 'route':
          output.route = data.strategy === 'SANDBOX' ? 'Docker 工作区' : data.requiresWorkflow ? '工作流' : data.strategy || 'Java Agent'
          break
        case 'thinking':
          phase.value = data.name || '执行第 ' + data.step + ' 步'
          output.steps?.push(phase.value)
          break
        case 'tools': output.steps?.push('工具：' + data.names.join('、')); break
        case 'delta': output.content += data.text; break
        case 'task':
          task = data
          output.taskId = data.id
          phase.value = '等待 Java 工作流'
          break
        case 'done': break
      }
    })
    if (!local) javaNotice.value = ''
    if (task) {
      const result = await api.waitForTask(task, request.polling.signal, status => {
        phase.value = status.stage || '工作流进度 ' + (status.progress ?? 0) + '%'
      })
      const text = api.taskDone(result) ? api.taskText(result) : '工作流仍在运行，可打开关联任务查看进度。'
      output.content += (output.content ? '\n\n' : '') + text
    }
    if (!output.content) output.content = '请求已完成，没有返回正文。'
  } catch (error) {
    const cancelled = request.polling.signal.aborted || errorCode(error) === 'agent_cancelled'
    const failure = cancelled ? (task ? '已停止等待，工作流仍可在日志中查看。' : '已停止生成。') : errorText(error)
    output.content += (output.content ? '\n\n' : '') + failure
    if (!cancelled && !local && ['java_unavailable', 'java_timeout', 'java_unauthorized'].includes(errorCode(error))) {
      javaNotice.value = failure
    }
  } finally {
    if (local && sandbox) workspace.revision++
    busy.value = false
    localBusy.value = false
    activeRequest = undefined
  }
}
onMounted(() => {
  if (sessions.sessions[0]) select(sessions.sessions[0])
  void refreshJava()
  void workspace.refreshSandbox().catch(() => {})
})
onBeforeUnmount(() => {
  if (!localBusy.value) void stopAnswer()
})
</script>

<template>
  <main class="page">
    <PageHeading title="Agent 问答" detail="在同一条回答里查看执行步骤、引用和最终输出。" />
    <div v-if="javaNotice" class="panel row spread java-notice" role="status">
      <span>{{ javaNotice }}</span>
      <button type="button" :disabled="loadingJava" @click="refreshJava">重新连接 Java</button>
    </div>
    <div class="split">
      <aside class="panel stack" aria-label="会话列表">
        <div class="row spread"><h2>会话</h2><button type="button" :disabled="busy" @click="newChat">新建</button></div>
        <p v-if="!sessions.sessions.length" class="muted">暂无会话</p>
        <button v-for="session in sessions.sessions" :key="session.id" type="button" class="item-button"
          :aria-pressed="session.id === chatId" :disabled="busy" @click="select(session)">{{ session.title }}</button>
        <button v-if="currentSession" type="button" class="danger" :disabled="busy" @click="removeSession">删除当前会话</button>
      </aside>
      <section class="panel stack" aria-label="当前会话">
        <div class="form-grid">
          <label v-if="!currentSession" class="field">检索范围
            <select v-model="spaceId" :disabled="busy">
              <option value="">不使用知识库</option>
              <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option>
            </select>
          </label>
          <div v-else class="stack"><span class="muted">检索范围</span><strong>{{ scopeName }}</strong></div>
          <label class="field">模型
            <select v-model="modelId" :disabled="busy">
              <option value="">自动选择</option>
              <option v-for="model in models" :key="model.id" :value="model.id">{{ model.label }}</option>
            </select>
          </label>
        </div>
        <div class="row">
          <span v-if="workspaceLabel" class="badge">工作区 · {{ workspaceLabel }}</span>
          <RouterLink v-else to="/workspace">绑定工作区</RouterLink>
          <button v-if="!javaNotice" type="button" :disabled="loadingJava" @click="refreshJava">刷新 Java 配置</button>
        </div>
        <details v-if="skills.length">
          <summary>Skills{{ skillIds.length ? ' · ' + skillIds.length : '' }}</summary>
          <div class="row">
            <label v-for="skill in skills" :key="skill.id" class="field inline">
              <input v-model="skillIds" type="checkbox" :value="skill.id" :disabled="busy || (skillIds.length >= 5 && !skillIds.includes(skill.id))" />
              {{ skill.name }}
            </label>
          </div>
        </details>
        <div ref="messageList" class="messages stack" aria-label="对话消息">
          <p v-if="!messages.length" class="empty">输入问题开始会话。选择工作区后可使用 /ls、/read、/write 和 /exec。</p>
          <article v-for="item in messages" :key="item.id" class="bubble stack" :data-role="item.role">
            <div class="row"><strong>{{ item.role === 'user' ? '你' : 'Agent' }}</strong><span v-if="item.route" class="badge">{{ item.route }}</span></div>
            <details v-if="item.steps?.length" :open="busy && item === messages.at(-1)">
              <summary>执行路径</summary>
              <ol><li v-for="(step, index) in item.steps" :key="index">{{ step }}</li></ol>
            </details>
            <p v-if="busy && item === messages.at(-1)" class="row muted" role="status"><span class="spinner" aria-hidden="true"></span>{{ phase }}</p>
            <p v-if="item.content" class="message-text">{{ item.content }}</p>
            <RouterLink v-if="item.taskId" :to="{ path: '/agent-runs', query: { taskId: item.taskId } }">查看关联任务</RouterLink>
            <RouterLink v-else-if="item.runId" :to="{ path: '/agent-runs', query: { runId: item.runId } }">查看本次运行</RouterLink>
          </article>
        </div>
        <form class="form-stack" @submit.prevent="send">
          <label class="field">消息
            <textarea ref="composer" v-model="draft" rows="3" placeholder="输入问题，或 /exec pwd · 键入 / 插入 Prompt"
              @input="promptClosed = false" @keydown.esc="promptClosed = true" @keydown.ctrl.enter.prevent="send" />
          </label>
          <div v-if="matchingPrompts.length" class="row" aria-label="可插入的 Prompt">
            <button v-for="prompt in matchingPrompts" :key="prompt.id" type="button" @click="insertPrompt(prompt)">{{ prompt.name }}</button>
          </div>
          <div class="row spread">
            <span class="muted">Ctrl + Enter 发送</span>
            <button v-if="busy && !localBusy" type="button" @click="stopAnswer">停止等待</button>
            <button v-else-if="busy" type="button" disabled>容器执行中</button>
            <button v-else type="submit" class="primary" :disabled="!draft.trim()">发送</button>
          </div>
          <p v-if="notice" class="error" role="alert">{{ notice }}</p>
        </form>
      </section>
    </div>
  </main>
</template>

<style scoped>
.java-notice { margin-bottom: 1rem; }
.messages { max-height: 49vh; overflow-y: auto; align-content: start; padding: .2rem; }
.bubble { max-width: 100%; padding: .75rem; border-radius: .55rem; background: #f2f6f6; }
.bubble[data-role='user'] { justify-self: end; background: #e6f3eb; }
.message-text { white-space: pre-wrap; overflow-wrap: anywhere; }
ol { margin: .3rem 0 0; padding-left: 1.35rem; }
.spinner { width: 1rem; height: 1rem; border: 2px solid #acd2c8; border-top-color: var(--brand); border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
</style>
