<template>
  <div class="app-frame">
    <header class="app-header">
      <RouterLink class="brand" to="/agent">
        <span class="brand-mark"><WorkspaceIcon name="brand" /></span>
        <span>Tian <small>知识工作台</small></span>
      </RouterLink>
      <nav v-if="desktopReady" class="workspace-nav" aria-label="工作台导航">
        <RouterLink v-for="item in navigation" :key="item.to" :to="item.to">
          <WorkspaceIcon :name="item.icon" /> {{ item.label }}
        </RouterLink>
      </nav>
      <div class="access">
        <template v-if="desktopReady">
          <RouterLink class="ui-button access-button" to="/mcp-settings">MCP 设置</RouterLink>
          <RouterLink class="ui-button access-button" to="/agent-presets">Skills / Prompt</RouterLink>
          <RouterLink class="ui-button access-button" to="/model-settings">模型设置</RouterLink>
          <RouterLink class="ui-button access-button" to="/tool-settings">配置中心</RouterLink>
        </template>
        <button v-if="desktopClient && desktopReady" class="ui-button access-button" type="button" @click="editingDesktop = true">
          <WorkspaceIcon name="key" /> 访问配置
        </button>
        <button v-else-if="!desktopClient && !editingToken" class="ui-button access-button" type="button" @click="editingToken = true">
          <WorkspaceIcon name="key" /> {{ hasToken ? '访问已配置' : '设置访问令牌' }}
        </button>
        <form v-else-if="!desktopClient" class="access-form ui-panel" @submit.prevent="saveToken">
          <label for="access-token">访问令牌</label>
          <input id="access-token" v-model="tokenInput" class="ui-field" type="password" placeholder="输入后端访问令牌" autocomplete="off" />
          <div>
            <button class="ui-button" type="button" @click="editingToken = false">取消</button>
            <button class="ui-button primary" type="submit">保存</button>
          </div>
        </form>
      </div>
    </header>

    <DesktopAccess v-if="editingDesktop" :required="!desktopReady"
      @cancel="editingDesktop = false" @connected="onDesktopConnected" />
    <div v-if="desktopReady" class="app-layout" :class="{ 'chat-layout': showSidebar }">
      <AppSidebar v-if="showSidebar" :current-chat-id="currentChatId" :medical="isMedicalSpace" :history-list="historyList"
        @new-chat="handleNewChat" @select-chat="handleSelectChat" @delete-chat="handleDeleteChat" @clear-chat="handleClearChat" />
      <main class="main-content">
        <div v-if="showSpaceSelector" class="scope-bar">
          <div><WorkspaceIcon name="agent" /><strong>Agent 问答</strong></div>
          <label for="agent-space"><span>检索范围</span>
            <select id="agent-space" v-model="spaceId" class="ui-field">
              <option value="">不使用知识库</option>
              <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }} · 整个空间</option>
            </select>
          </label>
        </div>
        <RouterView :chat-id-prop="currentChatId" :space-id="spaceId || undefined" :medical="isMedicalSpace"
          v-bind="showSidebar ? { modelProviders, modelProviderId: modelProviders.length ? modelProviderId : '' } : {}"
          @update:model-provider-id="modelProviderId = $event" @space-created="addSpace" @space-deleted="removeSpace" @update-history="loadHistory" @update-chat-id="handleUpdateChatId"
          @chat-activity="promoteChat" />
      </main>
    </div>
    <MedicalBoundary v-if="desktopReady && showSpaceSelector && isMedicalSpace" />
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceIcon from './components/WorkspaceIcon.vue'
import AppSidebar from './components/AppSidebar.vue'
import MedicalBoundary from './components/MedicalBoundary.vue'
import DesktopAccess from './components/DesktopAccess.vue'
import { getSessions, deleteSession, getKnowledgeSpaces, getModelProviders, getApiToken, setApiToken, desktopClient, savedDesktopApi } from './api'

const route = useRoute()
const router = useRouter()
const navigation = [
  { to: '/knowledge-base', icon: 'documents', label: '知识库导入' },
  { to: '/agent', icon: 'agent', label: 'Agent 问答' },
  { to: '/wiki', icon: 'wiki', label: 'Wiki 知识图谱' },
  { to: '/agent-runs', icon: 'logs', label: '运行日志' }
]

// 只有在聊天相关页面显示侧边栏
const showSidebar = computed(() => {
  return route.name === 'Agent'
})
const showSpaceSelector = showSidebar
const aiType = 'agent'

const currentChatId = ref('')
const historyList = ref([])
let historyRequest = 0
const spaceId = ref('')
const modelProviderId = ref('')
const modelProviders = ref([])
const spaces = ref([])
const isMedicalSpace = computed(() => spaces.value.some(space => space.id === spaceId.value && space.medical))
const addSpace = space => {
  if (!spaces.value.some(item => item.id === space.id)) spaces.value.push(space)
}
const removeSpace = id => {
  spaces.value = spaces.value.filter(space => space.id !== id)
  if (spaceId.value === id) spaceId.value = ''
}
const hasToken = Boolean(getApiToken())
const desktopReady = !desktopClient || Boolean(savedDesktopApi && hasToken)
const editingDesktop = ref(!desktopReady)
function onDesktopConnected({ needsModel }) {
  if (needsModel) window.history.replaceState(null, '', router.resolve('/model-settings').href)
  window.location.reload()
}
const editingToken = ref(false)
const tokenInput = ref('')
const saveToken = () => {
  setApiToken(tokenInput.value)
  window.location.reload()
}
const loadSpaces = async () => {
  if (!showSpaceSelector.value || !hasToken) return
  try {
    const { data } = await getKnowledgeSpaces()
    spaces.value = Array.isArray(data) ? data.filter(space => !space.deleting) : []
    if (spaceId.value && !spaces.value.some(space => space.id === spaceId.value)) spaceId.value = ''
  } catch {
    spaces.value = []
  }
}

// 加载会话列表
const loadHistory = async () => {
  if (!desktopReady || !showSidebar.value) return
  const requestId = ++historyRequest
  const type = aiType
  const selected = spaceId.value
  try {
    const res = await getSessions(type, selected || undefined)
    if (requestId !== historyRequest || !showSidebar.value || selected !== spaceId.value) return
    historyList.value = Array.isArray(res.data) ? res.data : (res.data?.items || res.data?.sessions || [])
    return historyList.value
  } catch (error) {
    if (requestId !== historyRequest || !showSidebar.value || selected !== spaceId.value) return
    console.error('Failed to load history:', error)
    return []
  }
}

const promoteChat = id => {
  const index = historyList.value.findIndex(chat => chat.id === id)
  if (index < 0) return
  historyRequest++
  if (index === 0) return
  const [chat] = historyList.value.splice(index, 1)
  historyList.value.unshift(chat)
}

const loadInitialHistory = async () => {
  const sessions = await loadHistory()
  if (!currentChatId.value && sessions?.length) currentChatId.value = sessions[0].id
}

// 监听路由变化，切换智能体时重新加载历史记录
watch([showSidebar, spaceId], () => {
  historyRequest++
  currentChatId.value = ''
  historyList.value = []
  if (showSidebar.value) loadInitialHistory()
})
watch(() => route.path, loadSpaces)
watch(showSidebar, async (active, _, onCleanup) => {
  if (!active || !hasToken) return
  let cancelled = false
  onCleanup(() => { cancelled = true })
  modelProviders.value = []
  try {
    const { data } = await getModelProviders()
    if (cancelled) return
    modelProviders.value = data.providers.filter(provider => provider.enabled && provider.hasApiKey && provider.protocol !== 'OPENAI_EMBEDDING')
      .sort((a, b) => a.priority - b.priority)
    if (!modelProviders.value.some(provider => provider.id === modelProviderId.value)) modelProviderId.value = ''
  } catch {
    if (!cancelled) modelProviderId.value = ''
  }
}, { immediate: true })

// 初始化加载
onMounted(() => {
  loadInitialHistory()
  loadSpaces()
})

const handleUpdateChatId = (id) => {
  currentChatId.value = id
}

const handleNewChat = () => {
  // 生成新对话 ID
  const token = crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const newId = 'agent_' + token
  currentChatId.value = newId
}

const handleSelectChat = (chat) => {
  currentChatId.value = chat.id
}

const handleDeleteChat = async (chat) => {
  if (!chat?.id || !window.confirm(`确定删除对话“${chat.title || '未命名对话'}”吗？`)) return
  const type = aiType
  const selected = spaceId.value
  try {
    await deleteSession(chat.id, type, selected || undefined)
    if (!showSidebar.value || selected !== spaceId.value) return
    historyList.value = historyList.value.filter(item => item.id !== chat.id)
    if (currentChatId.value === chat.id) currentChatId.value = ''
    const sessions = await loadHistory()
    if (!currentChatId.value && sessions?.length) currentChatId.value = sessions[0].id
  } catch (error) {
    console.error('Failed to delete chat:', error)
  }
}

const handleClearChat = async () => {
  if (!currentChatId.value) return
  const chat = historyList.value.find(item => item.id === currentChatId.value) || { id: currentChatId.value, title: '当前对话' }
  await handleDeleteChat(chat)
}
</script>

<style scoped>
.app-frame { display: flex; flex-direction: column; height: 100%; overflow: hidden; }
.app-header {
  z-index: 10;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) auto;
  align-items: center;
  flex-shrink: 0;
  min-height: 62px;
  gap: 12px;
  padding: 8px 20px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}
.brand { display: flex; align-items: center; gap: 9px; color: var(--ink); font-size: 17px; font-weight: 760; text-decoration: none; white-space: nowrap; }
.brand small { margin-left: 3px; color: var(--muted); font-size: 12px; font-weight: 550; }
.brand-mark { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 9px; background: var(--accent); color: white; }
.workspace-nav { display: flex; gap: 4px; min-width: 0; overflow-x: auto; }
.workspace-nav a {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 11px;
  border-radius: 8px;
  color: var(--muted);
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
}
.workspace-nav svg,.access svg,.scope-bar svg { width: 16px; height: 16px; flex: none; }
.workspace-nav a:hover { color: var(--ink); background: var(--surface-muted); }
.workspace-nav a.router-link-active { background: var(--accent-soft); color: var(--accent-ink); font-weight: 700; }
.access { position: relative; display: flex; gap: 6px; }
.access .router-link-active { border-color: var(--accent); color: var(--accent-ink); }
.access-button { font-size: 12px; }
.access-form {
  position: absolute;
  top: 0;
  right: 0;
  display: grid;
  gap: 10px;
  width: min(280px, 88vw);
  padding: 14px;
}
.access-form div { display: flex; justify-content: flex-end; gap: 6px; }
.app-layout { display: flex; flex: 1; min-height: 0; }
.main-content { display: flex; flex: 1; flex-direction: column; min-width: 0; min-height: 0; overflow-y: auto; }
.chat-layout .main-content { overflow: hidden; }
.scope-bar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 7px 14px;
  padding: 9px 18px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}
.scope-bar div,.scope-bar label { display: inline-flex; align-items: center; gap: 8px; }
.scope-bar svg { color: var(--accent); }
.scope-bar label { max-width: 100%; color: var(--muted); font-size: 12px; }
.scope-bar select { min-height: 32px; min-width: 0; max-width: min(240px, 65vw); }
@media (max-width: 880px) {
  .app-header { grid-template-columns: minmax(0, 1fr) auto; gap: 6px 10px; padding: 8px 12px; }
  .workspace-nav { grid-column: 1 / -1; grid-row: 2; }
}
@media (max-width: 720px) { .chat-layout { flex-direction: column; } }
@media (max-width: 480px) {
  .workspace-nav { justify-content: space-between; }
  .workspace-nav a { padding-inline: 6px; font-size: 12px; }
  .workspace-nav svg { display: none; }
  .scope-bar { padding-inline: 12px; }
  .brand small { display: none; }
  .access-button { padding-inline: 7px; }
}
</style>
