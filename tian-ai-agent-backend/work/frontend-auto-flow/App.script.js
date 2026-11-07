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
