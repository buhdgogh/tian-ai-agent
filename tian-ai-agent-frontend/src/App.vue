<template>
  <div class="app-frame">
  <div v-if="currentUser && route.name !== 'Login'" class="account-bar">
    <span>当前用户：{{ currentUser.username }}</span>
    <button :disabled="signingOut" @click="handleLogout">{{ signingOut ? '正在退出…' : '退出登录' }}</button>
  </div>
  <div v-if="logoutError" class="session-notice" role="alert">
    {{ logoutError }} <button :disabled="signingOut" @click="handleLogout">重试退出</button>
  </div>
  <div class="app-layout">
    <AppSidebar 
      v-if="showSidebar"
      :aiType="aiType"
      :currentChatId="currentChatId"
      :currentChatType="currentChatType"
      :historyList="historyList"
      :username="currentUser?.username || currentUser?.userId"
      :is-creating="creating"
      @new-chat="handleNewChat"
      @select-chat="handleSelectChat"
      @delete-chat="handleDeleteChat"
      @clear-chat="handleClearChat"
    />
    <main class="main-content">
      <div v-if="notice" class="session-notice" role="alert">{{ notice }} <button @click="loadHistory">重试</button></div>
      <router-view 
        :chatIdProp="currentChatId" 
        :sessionTypeProp="currentChatType"
        @update-history="loadHistory" 
        @update-chat-id="handleUpdateChatId"
      />
    </main>
  </div>
  <MedicalBoundary />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import MedicalBoundary from './components/MedicalBoundary.vue'
import { createSession, getSessions, deleteSession } from './api'
import { currentUser, loadCurrentUser, signOut } from './stores/user'
import { apiError } from './utils/medical'

const route = useRoute(), router = useRouter()
const signingOut = ref(false), logoutError = ref('')
const currentChatId = ref(''), currentChatType = ref('agent'), historyList = ref([]), notice = ref(''), creating = ref(false)
const showSidebar = computed(() => route.path === '/medical')
const aiType = computed(() => showSidebar.value ? 'medical' : 'default')
let listVersion = 0, contextVersion = 0
const handleLogout = async () => {
  if (signingOut.value) return
  signingOut.value = true
  logoutError.value = ''
  try { await signOut(); await router.replace('/login') }
  catch (error) { logoutError.value = apiError(error) }
  finally { signingOut.value = false }
}
const loadHistory = async () => {
  if (!showSidebar.value) return []
  const version = ++listVersion
  try {
    await loadCurrentUser()
    const groups = await Promise.all(['agent', 'medical', 'rag'].map(async type => {
      const { data } = await getSessions(type)
      const rows = Array.isArray(data) ? data : data?.items || data?.sessions || []
      return rows.map(chat => ({ ...chat, type }))
    }))
    if (version !== listVersion || !showSidebar.value) return []
    historyList.value = groups.flat().sort((a, b) => String(b.updatedAt || b.createdAt || '').localeCompare(String(a.updatedAt || a.createdAt || '')))
    notice.value = ''
    return historyList.value
  } catch (error) {
    if (version === listVersion) notice.value = apiError(error)
    return []
  }
}
const handleSelectChat = chat => { currentChatType.value = chat.type || 'agent'; currentChatId.value = chat.id }
watch(showSidebar, async () => {
  const version = ++contextVersion
  listVersion++
  currentChatId.value = ''
  currentChatType.value = 'agent'
  historyList.value = []
  notice.value = ''
  const sessions = await loadHistory()
  if (version === contextVersion && !currentChatId.value && sessions.length) handleSelectChat(sessions[0])
}, { immediate: true })
const handleUpdateChatId = id => { currentChatId.value = id }
const handleNewChat = async () => {
  if (creating.value) return
  creating.value = true
  const version = contextVersion
  try {
    await loadCurrentUser()
    const { data } = await createSession({ type: 'agent' })
    if (version !== contextVersion) return
    handleSelectChat({ id: data.id, type: 'agent' })
    await loadHistory()
  } catch (error) { if (version === contextVersion) notice.value = apiError(error) }
  finally { creating.value = false }
}
const handleDeleteChat = async chat => {
  if (!chat?.id || !window.confirm(`确定删除对话“${chat.title || '未命名对话'}”吗？`)) return
  const version = contextVersion, type = chat.type || currentChatType.value
  try {
    await deleteSession(chat.id, type)
    if (version !== contextVersion) return
    if (currentChatId.value === chat.id && currentChatType.value === type) currentChatId.value = ''
    const sessions = await loadHistory()
    if (version === contextVersion && !currentChatId.value && sessions.length) handleSelectChat(sessions[0])
  } catch (error) { if (version === contextVersion) notice.value = apiError(error) }
}
const handleClearChat = () => {
  if (!currentChatId.value) return
  return handleDeleteChat(historyList.value.find(item => item.id === currentChatId.value && item.type === currentChatType.value)
    || { id: currentChatId.value, type: currentChatType.value, title: '当前对话' })
}

</script>

<style>
.account-bar { flex-shrink: 0; display: flex; align-items: center; justify-content: flex-end; gap: 14px; padding: 7px 18px; background: #fff; border-bottom: 1px solid #e0eaea; color: #577075; font-size: 12px; }
.account-bar button { border: 1px solid #cedddd; border-radius: 7px; padding: 5px 10px; background: #f5f9f9; color: #087985; }
.session-notice { padding: 10px 18px; background: #fff2dc; color: #875b08; }
.app-frame {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.app-layout {
  display: flex;
  width: 100%;
  flex: 1;
  min-height: 0;
  background-color: #f8fafc;
  color: #1e293b;
  overflow: hidden;
}

.main-content {
  flex: 1;
  width: 0; /* 防止子元素溢出 */
  height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body {
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  font-size: 16px;
  color: #1e293b;
  background-color: #f8fafc;
  width: 100%;
  height: 100%;
  overflow-x: hidden;
}

a {
  text-decoration: none;
  color: inherit;
}

button {
  cursor: pointer;
}

/* 响应式字体大小 */
@media (max-width: 768px) {
  html, body {
    font-size: 15px;
  }
}

@media (max-width: 480px) {
  html, body {
    font-size: 14px;
  }
}

/* 滚动条样式 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb {
  background: #ccc;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: #aaa;
}
</style>
