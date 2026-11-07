import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import MedicalBoundary from './components/MedicalBoundary.vue'
import { createSession, getSessions, deleteSession } from './api'
import { currentUser, loadCurrentUser } from './stores/user'
import { apiError, sessionTypeForMode } from './utils/medical'

const route = useRoute()
const showSidebar = computed(() => ['/medical', '/medical-agent'].includes(route.path))
const aiType = computed(() => route.path === '/medical' ? 'medical' : route.path === '/medical-agent' ? 'agent' : 'default')
const medicalMode = ref('chat')
const historyType = computed(() => aiType.value === 'medical' ? sessionTypeForMode(medicalMode.value) : aiType.value)
const currentChatId = ref(''), historyList = ref([]), notice = ref(''), creating = ref(false)
let listVersion = 0, contextVersion = 0
const loadHistory = async () => {
  if (!showSidebar.value) return []
  const type = historyType.value, version = ++listVersion
  try {
    await loadCurrentUser()
    const { data } = await getSessions(type)
    if (version !== listVersion || type !== historyType.value) return []
    historyList.value = Array.isArray(data) ? data : data?.items || data?.sessions || []
    notice.value = ''
    return historyList.value
  } catch (error) {
    if (version === listVersion) notice.value = apiError(error)
    return []
  }
}
watch(historyType, async () => {
  const version = ++contextVersion
  listVersion++
  currentChatId.value = ''
  historyList.value = []
  notice.value = ''
  const sessions = await loadHistory()
  if (version === contextVersion && !currentChatId.value && sessions.length) currentChatId.value = sessions[0].id
}, { immediate: true })
const handleUpdateChatId = id => { currentChatId.value = id }
const handleNewChat = async () => {
  if (creating.value) return
  creating.value = true
  const type = historyType.value, version = contextVersion
  try {
    await loadCurrentUser()
    const { data } = await createSession({ type })
    if (version !== contextVersion) return
    currentChatId.value = data.id
    await loadHistory()
  } catch (error) {
    if (version === contextVersion) notice.value = apiError(error)
  } finally { creating.value = false }
}
const handleSelectChat = chat => { currentChatId.value = chat.id }
const handleDeleteChat = async chat => {
  if (!chat?.id || !window.confirm(`确定删除对话“${chat.title || '未命名对话'}”吗？`)) return
  const type = historyType.value, version = contextVersion
  try {
    await deleteSession(chat.id, type)
    if (version !== contextVersion) return
    if (currentChatId.value === chat.id) currentChatId.value = ''
    const sessions = await loadHistory()
    if (version === contextVersion && !currentChatId.value && sessions.length) currentChatId.value = sessions[0].id
  } catch (error) { if (version === contextVersion) notice.value = apiError(error) }
}
const handleClearChat = () => {
  if (!currentChatId.value) return
  return handleDeleteChat(historyList.value.find(item => item.id === currentChatId.value) || { id: currentChatId.value, title: '当前对话' })
}
