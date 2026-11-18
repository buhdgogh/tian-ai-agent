import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { deleteSharedSession, deleteWorkbenchSession, getSharedMessages, getSharedSessions, loadWorkbench, newId, saveWorkbenchSession, type ChatMessage, type Session } from '../api/workbench'
import { errorText } from '../api/java'
import { STANDALONE_WORKSPACE, useWorkspaceStore } from './workspace'

import { reconcileHistory } from './sessionHistory'

interface SessionRun { id: string; polling: AbortController; local: boolean; phase: string }

export const useSessionStore = defineStore('chat-sessions', () => {
  const sessions = ref<Session[]>([])
  const runs = reactive(new Map<string, SessionRun>())
  const history = ref<Record<string, ChatMessage[]>>({})
  const workspace = useWorkspaceStore()
  const hydrated = ref(false)
  const loading = ref(false)
  const storageError = ref('')
  const backend = ref<'sqlite' | 'mysql'>()
  const historyError = ref('')
  const syncing = ref(false)
  const opening = reactive(new Set<string>())
  let scope = ''
  const queued = new Map<string, Promise<void>>()
  const dirty = new Set<string>()
  const timers = new Map<string, ReturnType<typeof setTimeout>>()

  async function load() {
    if (loading.value) return
    loading.value = true
    storageError.value = ''
    try {
      if (scope) { await flush(); return }
      const saved = await loadWorkbench()
      scope = saved.scope
      backend.value = saved.backend
      const restored: Record<string, ChatMessage[]> = {}
      for (const { session, messages } of saved.sessions) {
        workspace.at(session.id).path = session.workspacePath || ''
        restored[session.id] = messages.map(message => message.role === 'assistant' && !message.outcome
          ? { ...message, outcome: 'incomplete', outcomeDetail: '应用退出前未收到本轮最终结果，已保留保存的内容。' }
          : message)
      }
      history.value = restored
      sessions.value = saved.sessions.map(row => row.session)
      workspace.at(STANDALONE_WORKSPACE).path = saved.standalonePath || ''
    } catch (failure) {
      storageError.value = errorText(failure)
    } finally {
      hydrated.value = true
      loading.value = false
    }
  }

  async function refreshShared(spaceIds: string[]) {
    if (!scope || syncing.value) return
    const currentScope = scope
    syncing.value = true
    historyError.value = ''
    try {
      for (let offset = 0; offset < spaceIds.length; offset += 6) {
        const batch = spaceIds.slice(offset, offset + 6)
        const results = await Promise.allSettled(batch.map(spaceId => getSharedSessions(spaceId)))
        if (scope !== currentScope) return
        const discovered: string[] = []
        for (const [index, result] of results.entries()) {
          if (result.status === 'rejected') {
            historyError.value = errorText(result.reason)
            continue
          }
          const spaceId = batch[index]
          for (const remote of [...result.value].reverse()) {
            const existing = sessions.value.find(item => (item.sharedChatId || item.id) === remote.id && item.spaceId === spaceId)
            if (existing?.sharedChatId || (existing && runs.has(existing.id))) continue
            const session = existing ?? ensure({ id: newId(), title: remote.title || '未命名会话',
              spaceId, knowledgeOnly: Boolean(spaceId) })
            session.sharedChatId = remote.id
            discovered.push(session.id)
          }
        }
        for (const id of discovered) {
          if (scope !== currentScope) return
          await save(id).catch(() => {})
        }
      }
    } catch (failure) {
      historyError.value = errorText(failure)
    } finally {
      syncing.value = false
    }
  }

  async function openShared(session: Session) {
    if (!session.sharedChatId || opening.has(session.id) || runs.has(session.id)) return
    const currentScope = scope
    opening.add(session.id)
    historyError.value = ''
    try {
      const received = await getSharedMessages(session.sharedChatId, session.spaceId)
      if (scope !== currentScope || !sessions.value.some(row => row.id === session.id) || runs.has(session.id)) return
      history.value[session.id] = reconcileHistory(messages(session.id), received)
      await save(session.id).catch(() => {})
    } catch (failure) {
      historyError.value = errorText(failure)
    } finally {
      opening.delete(session.id)
    }
  }

  function canSave(): boolean { return Boolean(scope) }

  async function save(id: string) {
    clearTimeout(timers.get(id))
    timers.delete(id)
    const session = sessions.value.find(item => item.id === id)
    if (!session || !scope) return
    const currentScope = scope
    const snapshot = JSON.parse(JSON.stringify({ session, messages: messages(id) }))
    dirty.add(id)
    const write = (queued.get(id) ?? Promise.resolve()).catch(() => {}).then(() => saveWorkbenchSession(currentScope, snapshot))
    queued.set(id, write)
    try {
      await write
      if (queued.get(id) === write && !timers.has(id)) dirty.delete(id)
      if (!dirty.size) storageError.value = ''
    } catch (failure) {
      storageError.value = errorText(failure)
      throw failure
    } finally {
      if (queued.get(id) === write) queued.delete(id)
    }
  }

  function schedule(id: string) {
    dirty.add(id)
    if (!timers.has(id)) timers.set(id, setTimeout(() => { void save(id).catch(() => {}) }, 800))
  }

  async function flush() {
    await Promise.all(Array.from(dirty, id => save(id)))
  }

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
  async function remove(id: string) {
    clearTimeout(timers.get(id))
    timers.delete(id)
    await queued.get(id)?.catch(() => {})
    const session = sessions.value.find(item => item.id === id)
    if (session?.sharedChatId) {
      await deleteSharedSession(session.sharedChatId, session.spaceId)
    }
    await deleteWorkbenchSession(scope, id)
    dirty.delete(id)
    sessions.value = sessions.value.filter(session => session.id !== id)
    delete history.value[id]
  }
  function reset() {
    scope = ''
    historyError.value = ''
    opening.clear()
    for (const timer of timers.values()) clearTimeout(timer)
    timers.clear()
    dirty.clear()
    sessions.value = []
    history.value = {}
    runs.clear()
  }
  return { sessions, runs, hydrated, loading, storageError, backend, historyError, syncing, opening, refreshShared, openShared, canSave, load, save, schedule, flush, messages, ensure, remove, reset }
})
