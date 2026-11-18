import { invoke } from '@tauri-apps/api/core'

export interface DockerStatus { available: boolean; detail: string }
export interface SandboxInfo {
  id: string
  workspacePath: string
  defaultWorkspace?: boolean
  image: string
  status: 'running' | 'cleanup_required'
}
export interface FileEntry { name: string; path: string; kind: 'directory' | 'file' | 'symlink' | 'other' }
export interface ExecResult { stdout: string; stderr: string; exitCode: number; truncated: boolean }

export const checkDocker = (): Promise<DockerStatus> => invoke('check_docker')
export const selectWorkspace = (workspaceId: string, fromWorkspaceId?: string): Promise<string | null> =>
  invoke('select_workspace', { workspaceId, fromWorkspaceId })
export const getActiveSandbox = (workspaceId: string): Promise<SandboxInfo | null> => invoke('get_active_sandbox', { workspaceId })
export const createSandbox = (workspaceId: string, workspacePath: string): Promise<SandboxInfo> =>
  invoke('create_sandbox', { workspaceId, workspacePath })
export const stopSandbox = (workspaceId: string, sandboxId: string): Promise<void> => invoke('stop_sandbox', { workspaceId, sandboxId })
export const releaseWorkspace = (workspaceId: string): Promise<void> => invoke('release_workspace', { workspaceId })
export const listSandboxFiles = (workspaceId: string, sandboxId: string, path: string): Promise<FileEntry[]> =>
  invoke('list_sandbox_files', { workspaceId, sandboxId, path })
export const execInSandbox = (workspaceId: string, sandboxId: string, cmd: string): Promise<ExecResult> =>
  invoke('exec_in_sandbox', { workspaceId, sandboxId, cmd })

export function sandboxError(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string') {
    return error.message
  }
  return typeof error === 'string' ? error : '工作区操作失败，请重试。'
}
