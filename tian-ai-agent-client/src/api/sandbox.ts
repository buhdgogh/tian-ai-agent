import { invoke } from '@tauri-apps/api/core'

export interface DockerStatus { available: boolean; detail: string }
export interface SandboxInfo {
  id: string
  workspacePath: string
  image: string
  status: 'running' | 'cleanup_required'
}
export interface FileEntry { name: string; path: string; kind: 'directory' | 'file' | 'symlink' | 'other' }
export interface ExecResult { stdout: string; stderr: string; exitCode: number; truncated: boolean }

export const checkDocker = (): Promise<DockerStatus> => invoke('check_docker')
export const selectWorkspace = (): Promise<string | null> => invoke('select_workspace')
export const getActiveSandbox = (): Promise<SandboxInfo | null> => invoke('get_active_sandbox')
export const createSandbox = (workspacePath: string): Promise<SandboxInfo> =>
  invoke('create_sandbox', { workspacePath })
export const stopSandbox = (sandboxId: string): Promise<void> => invoke('stop_sandbox', { sandboxId })
export const listSandboxFiles = (sandboxId: string, path: string): Promise<FileEntry[]> =>
  invoke('list_sandbox_files', { sandboxId, path })
export const execInSandbox = (sandboxId: string, cmd: string): Promise<ExecResult> =>
  invoke('exec_in_sandbox', { sandboxId, cmd })

export function sandboxError(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string') {
    return error.message
  }
  return typeof error === 'string' ? error : '工作区操作失败，请重试。'
}
