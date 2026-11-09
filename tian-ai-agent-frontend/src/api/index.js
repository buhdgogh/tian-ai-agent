import axios from 'axios'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

const request = axios.create({ baseURL: API_BASE_URL, timeout: 120000 })
const TOKEN_KEY = 'tian-api-token'
export const getApiToken = () => sessionStorage.getItem(TOKEN_KEY) || ''
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
export const createKnowledgeSpace = (name, medical) => request.post('/knowledge-base/spaces', { name, medical })
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
export const discoverMcpTools = (id, body) => request.post('/mcp-servers/' + encodeURIComponent(id) + '/discover', body)
export const saveMcpServer = (id, body) => request.put('/mcp-servers/' + encodeURIComponent(id), body)
export const deleteMcpServer = id => request.delete('/mcp-servers/' + encodeURIComponent(id))

export const getModelProviders = () => request.get('/model-providers')
export const saveModelProviders = settings => request.put('/model-providers', settings)

export default request
