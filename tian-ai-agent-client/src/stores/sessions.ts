import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatMessage, Session } from '../api/workbench'

export const useSessionStore = defineStore('chat-sessions', () => {
  const sessions = ref<Session[]>([])
  const history = ref<Record<string, ChatMessage[]>>({})
  function ensure(session: Session): Session {
    const saved = sessions.value.find(item => item.id === session.id)
    if (saved) return saved
    sessions.value.unshift(session)
    return sessions.value[0]
  }
  function messages(id: string): ChatMessage[] {
    if (!history.value[id]) history.value[id] = []
    return history.value[id]
  }
  function remove(id: string) {
    sessions.value = sessions.value.filter(session => session.id !== id)
    delete history.value[id]
  }
  function reset() {
    sessions.value = []
    history.value = {}
  }
  return { sessions, messages, ensure, remove, reset }
})
