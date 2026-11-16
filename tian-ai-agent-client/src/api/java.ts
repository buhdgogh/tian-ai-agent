import { invoke } from '@tauri-apps/api/core'

export type HealthStatus = 'available' | 'unavailable' | 'timeout'
export interface JavaHealth {
  status: HealthStatus
  endpoint: string | null
  detail: string
  httpStatus: number | null
}
export interface DesktopAccess { port: number; hasToken: boolean }
export interface Diagnostic {
  timestampMs: number
  layer: string
  operation: string
  outcome: string
  elapsedMs: number
  httpStatus: number | null
}

type Operation =
  | 'spaces' | 'createSpace' | 'deleteSpace' | 'files' | 'deleteFile' | 'uploadFile'
  | 'query' | 'retrieve' | 'wikiGraph' | 'wikiPage' | 'compileWiki' | 'ragEvaluation'
  | 'tasks' | 'task' | 'cancelTask' | 'workflow' | 'runs' | 'run' | 'toolAudits'
  | 'models' | 'saveModels' | 'skills' | 'prompts' | 'saveSkill' | 'savePrompt' | 'deleteSkill' | 'deletePrompt'
  | 'mcpServers' | 'discoverMcp' | 'saveMcp' | 'deleteMcp'
  | 'toolCredentials' | 'saveMineru' | 'saveSearch' | 'clearMineru' | 'clearSearch'

export function javaCall<T>(operation: Operation, args: {
  id?: string
  spaceId?: string
  body?: object
  upload?: { filename: string; bytes: number[]; metadata?: object }
} = {}): Promise<T> {
  return invoke<T>('java_call', { request: { operation, ...args } })
}
export function checkHealth(): Promise<JavaHealth> { return invoke('check_health') }
export function getDesktopAccess(): Promise<DesktopAccess> { return invoke('java_access') }
export function saveDesktopAccess(port: number, token: string, clear: boolean): Promise<DesktopAccess> {
  return invoke('configure_java', { input: { port, token, clear } })
}
export function getDiagnostics(): Promise<Diagnostic[]> { return invoke('desktop_diagnostics') }

export function errorText(error: unknown): string {
  if (error instanceof Error) return error.message
  if (typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string') {
    return error.message
  }
  return typeof error === 'string' ? error : '操作失败，请稍后重试。'
}
export function errorCode(error: unknown): string {
  return typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string' ? error.code : ''
}
