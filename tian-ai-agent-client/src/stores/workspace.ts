import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import * as api from '../api/sandbox'

export const STANDALONE_WORKSPACE = 'standalone'

interface WorkspaceState {
  path: string
  sandbox: api.SandboxInfo | null
  pending: 'choose' | 'create' | 'stop' | 'exec' | 'release' | null
  error: string
  result: api.ExecResult | null
  revision: number
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const docker = ref<api.DockerStatus | null>(null)
  const checking = ref(false)
  const locations = new Map<string, WorkspaceState>()

  function at(id: string): WorkspaceState {
    const existing = locations.get(id)
    if (existing) return existing
    const state = reactive<WorkspaceState>({
      path: '', sandbox: null, pending: null, error: '', result: null, revision: 0,
    })
    locations.set(id, state)
    return state
  }

  async function check() {
    if (checking.value) return
    checking.value = true
    try {
      docker.value = await api.checkDocker()
    } catch {
      docker.value = { available: false, detail: '无法调用 Docker 检测。请在桌面窗口中重试。' }
    } finally {
      checking.value = false
    }
  }

  async function refreshSandbox(id: string) {
    const state = at(id)
    state.sandbox = await api.getActiveSandbox(id)
    if (state.sandbox && !state.sandbox.defaultWorkspace) state.path = state.sandbox.workspacePath
  }

  async function run(id: string, action: NonNullable<WorkspaceState['pending']>,
                     callback: (state: WorkspaceState) => Promise<void>) {
    const state = at(id)
    if (state.pending) return
    state.pending = action
    state.error = ''
    try {
      await callback(state)
    } catch (failure) {
      state.error = api.sandboxError(failure)
    } finally {
      state.pending = null
    }
  }

  const choose = (id: string, fromWorkspaceId?: string) => run(id, 'choose', async state => {
    const selection = await api.selectWorkspace(id, fromWorkspaceId)
    if (selection !== null) state.path = selection
  })

  const create = (id: string) => run(id, 'create', async state => {
    if (!state.path) return
    state.result = null
    try {
      state.sandbox = await api.createSandbox(id, state.path)
      state.revision++
    } catch (failure) {
      await refreshSandbox(id).catch(() => {})
      throw failure
    }
  })

  const stop = (id: string) => run(id, 'stop', async state => {
    if (!state.sandbox) return
    await api.stopSandbox(id, state.sandbox.id)
    state.sandbox = null
    state.result = null
  })

  const execute = (id: string, command: string) => run(id, 'exec', async state => {
    if (!state.sandbox || !command.trim()) return
    state.result = null
    state.result = await api.execInSandbox(id, state.sandbox.id, command)
    state.revision++
  })

  async function release(id: string) {
    const state = at(id)
    if (state.pending) throw new Error('当前会话的工作区操作尚未结束。')
    state.pending = 'release'
    try {
      await api.releaseWorkspace(id)
      locations.delete(id)
    } finally {
      state.pending = null
    }
  }

  return { docker, checking, at, check, choose, create, stop, execute, refreshSandbox, release }
})
