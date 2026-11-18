import { Channel, invoke } from '@tauri-apps/api/core'
import { errorCode, javaCall } from './java'

export interface Space { id: string; name: string; medical: boolean; deleting?: boolean; embeddingModel?: string | null }
export interface CreateSpace { name: string; medical: boolean; embeddingProviderId: string }
export interface KnowledgeFile { id: string; name: string; size: number; status?: string; taskId?: string; error?: string }
export interface DocumentMetadata { issuer: string; version: string; publishedAt: string; audience: string; documentType: string }
export interface Evidence { id: string; source: string; excerpt: string }
export interface KnowledgeSource { reference: string; source: string; excerpt: string }
export interface WikiNode { id: string; title: string; kind: string }
export interface WikiPage extends WikiNode { content: string; sources: Evidence[] }
export interface WikiGraph { nodes: WikiNode[]; edges: { source: string; target: string; label: string }[]; stale?: boolean; truncated?: boolean }
export interface Session { id: string; title: string; spaceId: string; workspacePath?: string | null; knowledgeOnly?: boolean; modelProviderId?: string | null; sharedChatId?: string | null }
export interface SessionSnapshot { session: Session; messages: ChatMessage[] }
export interface WorkbenchSnapshot {
  scope: string
  backend: 'sqlite' | 'mysql'
  sessions: SessionSnapshot[]
  standalonePath: string | null
}
export const loadWorkbench = (): Promise<WorkbenchSnapshot> => invoke('load_workbench')
export const saveWorkbenchSession = (scope: string, snapshot: SessionSnapshot): Promise<void> =>
  invoke('save_workbench_session', { scope, snapshot })
export const deleteWorkbenchSession = (scope: string, sessionId: string): Promise<void> =>
  invoke('delete_workbench_session', { scope, sessionId })
export type ChatOutcome = 'completed' | 'failed' | 'stopped' | 'incomplete'
export interface WorkspaceReceipt {
  operation: string
  path: string
  ok: boolean
  exitCode: number | null
  stdout: string | null
  stderr: string | null
  error: string | null
}
export interface WorkspaceAccess { write: boolean; commands: boolean }
export interface WorkspaceTargetRequest { workspaceId?: string; access: WorkspaceAccess }
export interface WorkspaceExecution extends WorkspaceAccess {
  requestId: string
  chatId: string
  path: string
  containerId: string
}
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  serverHistory?: boolean
  localOnly?: boolean
  content: string
  route?: string
  workspace?: boolean
  receipts?: WorkspaceReceipt[]
  targetWorkspace?: WorkspaceExecution
  outputDirectory?: string
  cleanupWarning?: string
  outcome?: ChatOutcome
  outcomeDetail?: string
  steps?: string[]
  sources?: KnowledgeSource[]
  runId?: string
  taskId?: string
}
export interface ChatRequest {
  requestId: string
  chatId: string
  sharedChatId?: string
  message: string
  spaceId?: string
  modelProviderId?: string
  skillIds: string[]
  knowledgeOnly?: boolean
  targetWorkspace?: WorkspaceTargetRequest
  recentRequests?: string[]
}
export type AgentEvent =
  | { event: 'workspace_ready'; data: import('./sandbox').SandboxInfo }
  | { event: 'workspace_receipt'; data: WorkspaceReceipt }
  | { event: 'target_workspace_ready'; data: WorkspaceExecution }
  | { event: 'workspace_cleanup_failed'; data: { message: string } }
  | { event: 'route'; data: { strategy?: string; requiresWorkflow?: boolean; workspaceRoles?: string[] } }
  | { event: 'run'; data: { runId: string } }
  | { event: 'thinking'; data: { step: number; name?: string } }
  | { event: 'tools'; data: { names: string[] } }
  | { event: 'delta'; data: { text: string } }
  | { event: 'task'; data: TaskRecord }
  | { event: 'done'; data: { sources?: KnowledgeSource[]; status?: 'COMPLETED' | 'FAILED' } }

export type RunEventKind = '阶段' | '模型' | '工具' | '缓存' | '其他'
export interface RunEvent {
  kind: RunEventKind
  name: string
  status: string
  elapsedMs: number
  inputTokens?: number
  outputTokens?: number
  reason?: string
}
export interface AgentRunSummary {
  id: string
  kind: string
  status: string
  startedAt: string
  elapsedMs: number
  eventCount: number
  traceId?: string
  parentRunId?: string
  inputTokens?: number | null
  outputTokens?: number | null
  totalTokens?: number | null
  taskId?: string
}
export interface AgentRun extends AgentRunSummary { events: RunEvent[] }
export interface TaskRecord {
  id: string
  type: string
  status: string
  progress?: number
  stage?: string
  result?: Record<string, unknown> | string
  error?: string
}
interface SharedSession { id: string; title: string; updatedAt: string }
interface SharedMessage {
  role?: string
  messageType?: string
  type?: string
  content?: string
  text?: string
  message?: string
  toolCalls?: { name?: string }[]
  sources?: KnowledgeSource[]
}
export const getSharedSessions = (spaceId: string): Promise<SharedSession[]> =>
  javaCall('sessions', { spaceId: spaceId || undefined })
export const deleteSharedSession = (id: string, spaceId: string): Promise<boolean> =>
  javaCall('deleteSession', { id, spaceId: spaceId || undefined })
export async function getSharedMessages(id: string, spaceId: string): Promise<ChatMessage[]> {
  const rows = await javaCall<SharedMessage[]>('sessionMessages', { id, spaceId: spaceId || undefined })
  if (!Array.isArray(rows)) throw new Error('Java 返回了无效的会话历史。')
  const result: ChatMessage[] = []
  let steps: string[] = []
  for (const row of rows) {
    const role = (row.role ?? row.messageType ?? row.type ?? '').toLowerCase()
    if (role === 'user') steps = []
    if (role !== 'user' && role !== 'assistant') continue
    if (role === 'assistant') {
      for (const call of row.toolCalls ?? []) if (call.name && call.name !== 'doTerminate') steps.push(call.name)
    }
    const content = row.content ?? row.text ?? row.message ?? ''
    if (!content.trim() && !row.sources?.length) continue
    result.push({ id: newId(), role, content, serverHistory: true, sources: row.sources,
      ...(role === 'assistant' ? { outcome: 'completed', steps: [...new Set(steps)] } : {}) })
    steps = []
  }
  return result
}
export function newId(): string { return crypto.randomUUID() }
export const getKnowledgeSpaces = (): Promise<Space[]> => javaCall('spaces')
export const createKnowledgeSpace = (body: CreateSpace): Promise<Space> => javaCall('createSpace', { body })
export const deleteKnowledgeSpace = (id: string, accountName: string): Promise<void> => javaCall('deleteSpace', { id, body: { accountName } })
export const getKnowledgeFiles = (spaceId: string): Promise<KnowledgeFile[]> => javaCall('files', { spaceId })
export async function uploadKnowledgeFile(spaceId: string, file: File, metadata?: DocumentMetadata): Promise<unknown> {
  if (file.size > 12 * 1024 * 1024) throw new Error('桌面端单次上传最大为 12 MiB。')
  const bytes = Array.from(new Uint8Array(await file.arrayBuffer()))
  return javaCall('uploadFile', { spaceId, upload: { filename: file.name, bytes, metadata } })
}
export const deleteKnowledgeFile = (spaceId: string, id: string): Promise<unknown> => javaCall('deleteFile', { id, spaceId })
function wikiKind(kind: string): string {
  return ({ ENTITY: '实体', CONCEPT: '概念', SYNTHESIS: '综合' } as Record<string, string>)[kind.toUpperCase()] ?? kind
}
export async function getWikiGraph(spaceId: string): Promise<WikiGraph> {
  const graph = await javaCall<WikiGraph>('wikiGraph', { spaceId })
  return {
    stale: graph.stale, truncated: graph.truncated,
    nodes: graph.nodes.map(node => ({ ...node, kind: wikiKind(node.kind) })),
    edges: graph.edges.map(edge => ({ ...edge, label: '引用' })),
  }
}
export async function getWikiPage(spaceId: string, id: string): Promise<WikiPage> {
  const page = await javaCall<WikiNode & { markdown: string; sources: { id: string; title: string }[] }>('wikiPage', { spaceId, id })
  return { ...page, kind: wikiKind(page.kind), content: page.markdown, sources: page.sources.map(source => ({
    id: source.id, source: source.title, excerpt: '',
  })) }
}
export const compileWiki = (spaceId: string): Promise<TaskRecord> => javaCall('compileWiki', { spaceId })
export const createRagEvaluation = (body: object): Promise<TaskRecord> => javaCall('ragEvaluation', { body })
export const createWorkflow = (body: object): Promise<TaskRecord> => javaCall('workflow', { body })
export const getTasks = (): Promise<TaskRecord[]> => javaCall('tasks')
export const getTask = (id: string): Promise<TaskRecord> => javaCall('task', { id })
export const cancelTask = (id: string): Promise<unknown> => javaCall('cancelTask', { id })
export const getAgentRuns = (): Promise<AgentRunSummary[]> => javaCall('runs')
export const getAgentTrace = (id: string): Promise<AgentRunSummary[]> => javaCall('trace', { id })

export async function getAgentRun(id: string): Promise<AgentRun | null> {
  try {
    const result = await javaCall<{ summary: AgentRunSummary; events: (Omit<RunEvent, 'kind'> & { kind: string; model?: string })[] }>('run', { id })
    const kinds: Record<string, RunEventKind> = { STAGE: '阶段', MODEL: '模型', TOOL: '工具', CACHE: '缓存' }
    return { ...result.summary, events: result.events.map(item => ({
      ...item, kind: kinds[item.kind] ?? '其他', name: item.model || item.name,
      status: ({ HIT: '命中', MISS: '未命中' } as Record<string, string>)[item.reason ?? '']
        ?? ({ HIT: '命中', MISS: '未命中' } as Record<string, string>)[item.status] ?? item.status,
    })) }
  } catch (error) {
    if (errorCode(error) === 'java_not_found') return null
    throw error
  }
}
export const getToolAudits = (): Promise<unknown[]> => javaCall('toolAudits')

export async function streamAgentDispatch(request: ChatRequest, receive: (event: AgentEvent) => void): Promise<void> {
  const events = new Channel<AgentEvent>()
  let done = false
  let finish: (() => void) | undefined
  const delivered = new Promise<void>(resolve => { finish = resolve })
  events.onmessage = event => {
    receive(event)
    if (event.event === 'done') {
      done = true
      finish?.()
    }
  }
  try {
    await invoke<void>('run_agent', { request, events })
  } catch (error) {
    if (!done) throw error
  }
  if (done) return

  // Tauri may fetch a large channel payload separately before delivering the remaining events.
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    await Promise.race([
      delivered,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject({ code: 'agent_delivery_incomplete', message: '未能收到完整的会话结果。' }), 5000)
      }),
    ])
  } finally {
    clearTimeout(timer)
  }
}
export const cancelAgent = (requestId: string): Promise<void> => invoke('cancel_agent', { requestId })

export function taskDone(task: TaskRecord): boolean {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(task.status)
}
export function taskText(task: TaskRecord): string {
  if (task.error) return task.error
  if (typeof task.result === 'string') return task.result
  const output = task.result
  if (typeof output?.executionResult === 'string') return output.executionResult
  if (typeof output?.pageCount === 'number') return '已生成 ' + output.pageCount + ' 个 Wiki 页面。'
  return task.stage || task.status
}
export async function waitForTask(initial: TaskRecord, signal: AbortSignal, update: (task: TaskRecord) => void): Promise<TaskRecord> {
  let task = initial
  for (let attempt = 0; attempt < 60 && !taskDone(task); attempt++) {
    await pollDelay(signal)
    task = await getTask(task.id)
    signal.throwIfAborted()
    update(task)
  }
  return task
}
function pollDelay(signal: AbortSignal): Promise<void> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(signal.reason) }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, 1500)
    signal.addEventListener('abort', abort, { once: true })
  })
}
