<template>
  <div class="app-frame">
  <div class="app-layout">
    <AppSidebar 
      v-if="showSidebar"
      :aiType="aiType"
      :currentChatId="currentChatId"
      :historyList="historyList"
      @new-chat="handleNewChat"
      @select-chat="handleSelectChat"
      @delete-chat="handleDeleteChat"
      @clear-chat="handleClearChat"
    />
    <main class="main-content">
      <router-view 
        :chatIdProp="currentChatId" 
        @update-history="loadHistory" 
        @update-chat-id="handleUpdateChatId"
      />
    </main>
  </div>
  <MedicalBoundary />
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import MedicalBoundary from './components/MedicalBoundary.vue'
import { getSessions, deleteSession } from './api'

const route = useRoute()

// 只有在聊天相关页面显示侧边栏
const showSidebar = computed(() => {
  return ['/medical', '/medical-agent'].includes(route.path)
})

const aiType = computed(() => {
  if (route.path === '/medical') return 'medical'
  if (route.path === '/medical-agent') return 'agent'
  return 'default'
})

const currentChatId = ref('')
const historyList = ref([])

// 加载会话列表
const loadHistory = async () => {
  if (!showSidebar.value) return
  try {
    const res = await getSessions(aiType.value)
    historyList.value = Array.isArray(res.data) ? res.data : (res.data?.items || res.data?.sessions || [])
    return historyList.value
  } catch (error) {
    console.error('Failed to load history:', error)
    historyList.value = []
    return []
  }
}

const loadInitialHistory = async () => {
  const sessions = await loadHistory()
  if (!currentChatId.value && sessions?.length) currentChatId.value = sessions[0].id
}

// 监听路由变化，切换智能体时重新加载历史记录
watch(() => aiType.value, async (newType) => {
  if (newType !== 'default') {
    currentChatId.value = ''
    await loadInitialHistory()
  }
})

// 初始化加载
onMounted(() => {
  loadInitialHistory()
})

const handleUpdateChatId = (id) => {
  currentChatId.value = id
}

const handleNewChat = () => {
  // 生成新对话 ID
  const token = crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const newId = (aiType.value === 'medical' ? 'medical_' : 'agent_') + token
  currentChatId.value = newId
}

const handleSelectChat = (chat) => {
  currentChatId.value = chat.id
}

const handleDeleteChat = async (chat) => {
  if (!chat?.id || !window.confirm(`确定删除对话“${chat.title || '未命名对话'}”吗？`)) return
  try {
    await deleteSession(chat.id, aiType.value)
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

<style>
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
