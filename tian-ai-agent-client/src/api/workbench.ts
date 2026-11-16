import { Channel, invoke } from '@tauri-apps/api/core'
import { errorCode, javaCall } from './java'

export interface Space { id: string; name: string; medical: boolean; deleting?: boolean; embeddingModel?: string | null }
export interface CreateSpace { name: string; medical: boolean; embeddingProviderId: string }
export interface KnowledgeFile { id: string; name: string; size: number; status?: string; taskId?: string; error?: string }
export interface DocumentMetadata { issuer: string; version: string; publishedAt: string; audience: string; documentType: string }
export interface Evidence { id: string; source: string; excerpt: string }
export interface WikiNode { id: string; title: string; kind: string }
export interface WikiPage extends WikiNode { content: string; sources: Evidence[] }
export interface WikiGraph { nodes: WikiNode[]; edges: { source: string; target: string; label: string }[]; stale?: boolean }
export interface Session { id: string; title: string; spaceId: string; workspacePath?: string }
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  route?: string
  steps?: string[]
  runId?: string
  taskId?: string
}
export interface ChatRequest {
  requestId: string
  chatId: string
  message: string
  spaceId?: string
  modelProviderId?: string
  skillIds: string[]
  sandboxId?: string
}
export type AgentEvent =
  | { event: 'route'; data: { strategy?: string; requiresWorkflow?: boolean } }
  | { event: 'run'; data: { runId: string } }
  | { event: 'thinking'; data: { step: number; name?: string } }
  | { event: 'tools'; data: { names: string[] } }
  | { event: 'delta'; data: { text: string } }
  | { event: 'task'; data: TaskRecord }
  | { event: 'done'; data: object }

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
interface JavaEvidence { chunkId?: string; documentId?: string; fileName?: string; title?: string; text?: string }

export function newId(): string { return crypto.randomUUID() }
function mapEvidence(raw: JavaEvidence[]): Evidence[] {
  return raw.map((item, index) => ({
    id: item.chunkId ?? item.documentId ?? String(index),
    source: item.fileName || item.title || item.documentId || '来源 ' + (index + 1),
    excerpt: item.text ?? '',
  }))
}
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
export async function retrieveKnowledgeBase(spaceId: string, question: string, topK = 8): Promise<Evidence[]> {
  const result = await javaCall<{ sources: JavaEvidence[] }>('retrieve', { body: { spaceId, question, topK } })
  return mapEvidence(result.sources ?? [])
}
export async function queryKnowledgeBase(spaceId: string, question: string, topK = 8): Promise<{
  answer: string; sources: Evidence[]; retrievalMode?: string; evidenceReason?: string
}> {
  const result = await javaCall<{ answer: string; sources: JavaEvidence[]; retrieval_mode?: string; evidence_reason?: string }>(
    'query', { body: { spaceId, question, topK } },
  )
  return { answer: result.answer, sources: mapEvidence(result.sources ?? []), retrievalMode: result.retrieval_mode, evidenceReason: result.evidence_reason }
}
function wikiKind(kind: string): string {
  return ({ ENTITY: '实体', CONCEPT: '概念', SYNTHESIS: '综合' } as Record<string, string>)[kind.toUpperCase()] ?? kind
}
export async function getWikiGraph(spaceId: string): Promise<WikiGraph> {
  const graph = await javaCall<WikiGraph>('wikiGraph', { spaceId })
  return {
    stale: graph.stale,
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
  await invoke<void>('run_agent', { request, events })
  if (done) return

  // Tauri may fetch a large channel payload separately before delivering the remaining events.
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    await Promise.race([
      delivered,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error('未能收到完整的会话结果。')), 5000)
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
