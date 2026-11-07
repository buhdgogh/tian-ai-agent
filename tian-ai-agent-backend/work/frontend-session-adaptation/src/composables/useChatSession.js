import { onBeforeUnmount, ref, unref, watch } from 'vue'
import { createSession, getSessionMessages } from '../api'
import { loadCurrentUser } from '../stores/user'
import { apiError, newChatId, normalizeHistory } from '../utils/medical'

export const useChatSession = (props, emit, type, agent = false) => {
  const messages = ref([]), status = ref('disconnected'), emergency = ref('')
  let controller = null, localId = '', revision = 0, disposed = false
  const interrupt = () => {
    revision++
    controller?.abort()
    controller = null
    status.value = 'disconnected'
  }
  const loadHistory = async () => {
    if (status.value === 'connecting') return
    const id = props.chatIdProp, sessionType = unref(type), version = ++revision
    if (!id) return
    status.value = 'loading'
    try {
      const { data } = await getSessionMessages(id, sessionType)
      if (!disposed && version === revision) messages.value = normalizeHistory(data, { agent })
    } catch (error) {
      if (!disposed && version === revision) messages.value = [{ content: apiError(error), isUser: false, time: Date.now(), status: 'FAILED' }]
    } finally {
      if (!disposed && version === revision) status.value = 'disconnected'
    }
  }
  const begin = () => {
    if (['connecting', 'loading'].includes(status.value)) return null
    const version = ++revision, sessionType = unref(type)
    controller = new AbortController()
    const activeController = controller
    emergency.value = ''
    status.value = 'connecting'
    return {
      signal: activeController.signal,
      active: () => !disposed && version === revision && !activeController.signal.aborted,
      ensureId: async () => {
        if (props.chatIdProp) return props.chatIdProp
        await loadCurrentUser()
        if (activeController.signal.aborted) throw new DOMException('Aborted', 'AbortError')
        const { data } = await createSession({ chatId: newChatId(sessionType), type: sessionType }, { signal: activeController.signal })
        if (activeController.signal.aborted || version !== revision || disposed) throw new DOMException('Aborted', 'AbortError')
        localId = data.id
        emit('update-chat-id', data.id)
        return data.id
      },
      finish: () => {
        if (!disposed) emit('update-history')
        if (version === revision) {
          controller = null
          if (status.value !== 'emergency') status.value = 'disconnected'
        }
      }
    }
  }
  const stop = () => {
    const last = messages.value.at(-1)
    if (last && !last.isUser && status.value === 'connecting') {
      last.incomplete = true
      last.status = 'CANCELLED'
    }
    interrupt()
  }
  const markEmergency = text => {
    emergency.value = text
    controller?.abort()
    status.value = 'emergency'
  }
  watch([() => props.chatIdProp, () => unref(type)], ([id, sessionType], previous) => {
    if (id && id === localId && (!previous || previous[1] === sessionType)) { localId = ''; return }
    localId = ''
    interrupt()
    messages.value = []
    emergency.value = ''
    loadHistory()
  }, { immediate: true })
  onBeforeUnmount(() => { disposed = true; interrupt() })
  return { messages, status, emergency, begin, stop, markEmergency, loadHistory }
}
