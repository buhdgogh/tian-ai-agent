import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as api from '../api/sandbox'

export const useWorkspaceStore = defineStore('workspace', () => {
  const docker = ref<api.DockerStatus | null>(null)
  const checking = ref(false)
  const path = ref('')
  const sandbox = ref<api.SandboxInfo | null>(null)
  const pending = ref<'choose' | 'create' | 'stop' | 'exec' | null>(null)
  const error = ref('')
  const result = ref<api.ExecResult | null>(null)
  const revision = ref(0)

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

  async function refreshSandbox() {
    sandbox.value = await api.getActiveSandbox()
    if (sandbox.value) path.value = sandbox.value.workspacePath
  }

  async function run(action: NonNullable<typeof pending.value>, callback: () => Promise<void>) {
    if (pending.value) return
    pending.value = action
    error.value = ''
    try {
      await callback()
    } catch (failure) {
      error.value = api.sandboxError(failure)
    } finally {
      pending.value = null
    }
  }

  const choose = () => run('choose', async () => {
    const selection = await api.selectWorkspace()
    if (selection !== null) path.value = selection
  })

  const create = () => run('create', async () => {
    if (!path.value) return
    result.value = null
    try {
      sandbox.value = await api.createSandbox(path.value)
      revision.value++
    } catch (failure) {
      await refreshSandbox().catch(() => {})
      throw failure
    }
  })

  const stop = () => run('stop', async () => {
    if (!sandbox.value) return
    await api.stopSandbox(sandbox.value.id)
    sandbox.value = null
    result.value = null
  })

  const execute = (command: string) => run('exec', async () => {
    if (!sandbox.value || !command.trim()) return
    result.value = null
    result.value = await api.execInSandbox(sandbox.value.id, command)
    revision.value++
  })

  return { docker, checking, path, sandbox, pending, error, result, revision, check, choose, create, stop, execute, refreshSandbox }
})
