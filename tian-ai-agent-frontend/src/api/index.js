import axios from 'axios'

export const desktopClient = import.meta.env.MODE === 'desktop' || window.isTauri === true
export const desktopApiSuggestion = import.meta.env.VITE_DESKTOP_API_BASE_URL?.trim() || ''
const ENDPOINT_KEY = 'tian-desktop-api-url'
const SESSION_KEY = 'tian-desktop-session'
export const savedDesktopApi = desktopClient ? localStorage.getItem(ENDPOINT_KEY) || '' : ''
export const API_BASE_URL = desktopClient ? savedDesktopApi : import.meta.env.VITE_API_BASE_URL || '/api'

const request = axios.create({ baseURL: API_BASE_URL, timeout: 120000 })
const TOKEN_KEY = 'tian-api-token'
export const getApiToken = () => {
  if (!desktopClient) return sessionStorage.getItem(TOKEN_KEY) || ''
  try {
    const session = JSON.parse(sessionStorage.getItem(SESSION_KEY))
    return session?.apiUrl === savedDesktopApi ? session.token : ''
  } catch {
    return ''
  }
}

function normalizeDesktopApi(address) {
  const value = address.trim()
  try {
    const url = new URL(value)
    if (/^https?:\/\//i.test(value) && !url.username && !url.password && !url.search && !url.hash) {
      return url.origin + (url.pathname.replace(/\/+$/, '') || '/api')
    }
  } catch {
    // 下面统一显示地址格式提示。
  }
  throw new Error('请输入有效的 HTTP 或 HTTPS 后端地址，例如 https://agent.example.com/api。')
}

export async function connectDesktop(address, token) {
  const apiUrl = normalizeDesktopApi(address)
  const credential = token.trim() || (apiUrl === savedDesktopApi ? getApiToken() : '')
  if (!/^[\x21-\x7e]{24,}$/.test(credential)) {
    throw new Error('请输入后端管理员分配的访问令牌。')
  }

  let response
  try {
    response = await fetch(apiUrl + '/model-providers', {
      headers: { Authorization: 'Bearer ' + credential },
      cache: 'no-store',
      redirect: 'error',
      signal: AbortSignal.timeout(10000)
    })
  } catch {
    throw new Error('无法连接后端。请确认地址可达、HTTPS 证书有效，并且服务允许桌面端的跨域请求。')
  }
  if (response.status === 401 || response.status === 403) {
    throw new Error('访问令牌无效或没有工作台权限，请向后端管理员确认。')
  }
  if (!response.ok) throw new Error('后端返回 HTTP ' + response.status + '，请核对 API 地址。')
  const data = await response.json().catch(() => null)
  if (typeof data?.version !== 'string' || !Array.isArray(data.providers)) {
    throw new Error('该地址未返回工作台模型配置，请核对后端 API 地址。')
  }

  // 同一窗口内的令牌只用于通过验证的后端地址。
  sessionStorage.setItem(SESSION_KEY, JSON.stringify({ apiUrl, token: credential }))
  localStorage.setItem(ENDPOINT_KEY, apiUrl)
  return { needsModel: !data.providers.some(provider => provider?.enabled && provider.hasApiKey) }
}

export const setApiToken = token => {
  if (token.trim()) sessionStorage.setItem(TOKEN_KEY, token.trim())
  else sessionStorage.removeItem(TOKEN_KEY)
}
const authorization = () => {
  const token = getApiToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}
request.interceptors.request.use(config => {
  Object.assign(config.headers, authorization())
  return config
})

const parseErrorBody = async response => {
  let message
  try {
    const data = await response.json()
    message = data?.error?.message || data?.message
  } catch {
    message = ''
  }
  const error = new Error(message || `HTTP ${response.status}`)
  error.status = response.status
  error.retryAfter = response.headers.get('Retry-After')
  return error
}

// POST SSE：健康问题始终放在请求体内，绝不进入 URL。
export const streamMedicalChat = async (path, body, onChunk, signal) => {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authorization() },
    body: JSON.stringify(body),
    signal
  })
  if (!response.ok || !response.body) throw await parseErrorBody(response)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let sawDone = false
  const consume = event => {
    const lines = event.split(/\r?\n/)
    const type = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
    const data = lines
      .filter(line => line.startsWith('data:'))
      // Spring 紧接 data: 写入正文。开头的空格可能属于模型分片。
      .map(line => line.slice(5))
      .join('\n')
    if (data === '[DONE]') sawDone = true
    else if (data) {
      if (type === 'done') sawDone = true
      onChunk?.(data, type)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split(/\r?\n\r?\n/)
    buffer = events.pop() || ''
    events.forEach(consume)
  }
  buffer += decoder.decode()
  if (buffer.trim()) consume(buffer)
  return { sawDone, completed: true }
}

export const streamKnowledgeChat = (body, onChunk, signal) => streamMedicalChat('/ai/chat/stream', body, onChunk, signal)
export const streamKnowledgeAgent = (body, onChunk, signal) => streamMedicalChat('/ai/agent/stream', body, onChunk, signal)
export const dispatchAgent = (body, signal) => request.post('/ai/dispatch', body, { signal })
export const streamAgentDispatch = (body, onEvent, signal) => streamMedicalChat(
  '/ai/dispatch/stream', body, (data, type) => onEvent(type, JSON.parse(data)), signal
)
export const replyWorkspaceRead = (runId, requestId, result, signal) => request.post(
  '/ai/local-workspace/' + encodeURIComponent(runId) + '/results/' + encodeURIComponent(requestId),
  result, { signal, timeout: 10000 }
)
export const chatWithTools = (body, signal) => request.post('/ai/chat/tools', body, { signal })
export const chatWithKnowledge = (body, signal) => request.post('/ai/chat/rag', body, { signal })
export const createMedicalEvidenceReport = (body, signal) => request.post('/ai/medical/evidence-report', body, { signal })

export const getSessions = (type, spaceId) => request.get('/ai/sessions', { params: { type, spaceId, workspace: true } })
export const getSessionMessages = (chatId, type, spaceId) => request.get(`/ai/sessions/${chatId}/messages`, { params: { type, spaceId, workspace: true } })
export const deleteSession = (chatId, type, spaceId) => request.delete(`/ai/sessions/${chatId}`, { params: { type, spaceId, workspace: true } })

export const uploadKnowledgeFile = (file, metadata, onProgress) => {
  const formData = new FormData()
  formData.append('file', file)
  Object.entries(metadata || {}).forEach(([key, value]) => formData.append(key, value))
  return request.post('/knowledge-base/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: event => onProgress?.(event.total ? Math.round(event.loaded * 100 / event.total) : 0),
    validateStatus: status => status >= 200 && status < 300
  })
}
export const getWikiGraph = spaceId => request.get('/knowledge-base/wiki-graph', { params: { spaceId } })
export const compileWiki = spaceId => request.post('/knowledge-base/wiki-compile', null, { params: { spaceId } })
export const getWikiPage = (spaceId, pageId) => request.get('/knowledge-base/wiki-pages/' + encodeURIComponent(pageId), { params: { spaceId } })
export const getKnowledgeSpaces = () => request.get('/knowledge-base/spaces')
export const createKnowledgeSpace = (name, medical, embeddingProviderId) => request.post('/knowledge-base/spaces', { name, medical, embeddingProviderId })
export const deleteKnowledgeSpace = (id, accountName) => request.delete('/knowledge-base/spaces/' + encodeURIComponent(id), { data: { accountName } })
export const getKnowledgeFiles = spaceId => request.get('/knowledge-base/files', { params: { spaceId } })
export const deleteKnowledgeFile = (id, spaceId) => request.delete(`/knowledge-base/files/${id}`, { params: { spaceId } })
export const queryKnowledgeBase = (spaceId, question, topK = 8) => request.post('/knowledge-base/query-with-sources', { spaceId, question, topK })
export const retrieveKnowledgeBase = (spaceId, question, topK = 8) => request.post('/knowledge-base/retrieve', { spaceId, question, topK })

export const createRagEvaluation = payload => request.post('/rag-evaluations', payload)
export const getTask = taskId => request.get(`/tasks/${taskId}`)
export const getTasks = (params = {}) => request.get('/tasks', { params })
export const cancelTask = taskId => request.delete(`/tasks/${taskId}`)
export const createWorkflow = payload => request.post('/ai/workflows', payload)
export const getHealth = () => request.get('/health')
export const getAgentRuns = () => request.get('/agent-runs')
export const getAgentRun = id => request.get(`/agent-runs/${id}`)

export const getMcpServers = () => request.get('/mcp-servers')
export const importMcpServer = body => request.post('/mcp-servers', body)
export const discoverMcpTools = (id, body) => request.post(
  id ? '/mcp-servers/' + encodeURIComponent(id) + '/discover' : '/mcp-servers/discover', body
)
export const saveMcpServer = (id, body) => request.put('/mcp-servers/' + encodeURIComponent(id), body)
export const deleteMcpServer = id => request.delete('/mcp-servers/' + encodeURIComponent(id))

export const getAgentPresets = collection => request.get('/' + collection)
export const saveAgentPreset = (collection, id, body) => id
  ? request.put('/' + collection + '/' + encodeURIComponent(id), body)
  : request.post('/' + collection, body)
export const deleteAgentPreset = (collection, id) => request.delete('/' + collection + '/' + encodeURIComponent(id))

export const getModelProviders = () => request.get('/model-providers')
export const saveModelProviders = settings => request.put('/model-providers', settings)

export const getToolSettings = () => request.get('/tool-settings')
export const saveToolCredential = (name, value) => request.put('/tool-settings/' + name, { value })
export const clearToolCredential = name => request.delete('/tool-settings/' + name)

export default request
