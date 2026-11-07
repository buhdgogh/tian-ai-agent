import axios from 'axios'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

const request = axios.create({ baseURL: API_BASE_URL, timeout: 120000, withCredentials: true })

const parseErrorBody = async response => {
  let message
  try {
    const data = await response.json()
    message = (typeof data?.error === 'string' ? data.error : data?.error?.message) || data?.message || data?.detail
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
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal
  })
  if (!response.ok || !response.body) throw await parseErrorBody(response)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let sawDone = false
  const consume = event => {
    const data = event.split(/\r?\n/)
      .filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trimStart())
      .join('\n')
    if (data === '[DONE]') sawDone = true
    else if (data) onChunk?.(data)
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

export const streamMedicalAssistant = (body, onChunk, signal) => streamMedicalChat('/ai/medical/chat/stream', body, onChunk, signal)
export const streamMedicalAgent = (body, onChunk, signal) => streamMedicalChat('/ai/medical-agent/chat', body, onChunk, signal)
export const chatMedical = body => request.post('/ai/medical/chat', body)
export const chatMedicalRag = (body, config = {}) => request.post('/ai/medical/rag-chat', body, config)
export const createMedicalEvidenceReport = (body, config = {}) => request.post('/ai/medical/evidence-report', body, config)

export const getSessions = type => request.get('/ai/sessions', { params: { type } })
export const createSession = (body, config = {}) => request.post('/ai/sessions', body, config)
export const getSessionMessages = (chatId, type) => request.get(`/ai/sessions/${chatId}/messages`, { params: { type } })
export const deleteSession = (chatId, type) => request.delete(`/ai/sessions/${chatId}`, { params: { type } })
export const getCurrentUser = () => request.get('/auth/me')
export const login = body => request.post('/auth/login', body)

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
export const getKnowledgeFiles = () => request.get('/knowledge-base/files')
export const deleteKnowledgeFile = id => request.delete(`/knowledge-base/files/${id}`)
export const queryKnowledgeBase = (question, topK = 8) => request.post('/knowledge-base/query-with-sources', { question, topK })
export const retrieveKnowledgeBase = (question, topK = 8) => request.post('/knowledge-base/retrieve', { question, topK })

export const getTask = taskId => request.get(`/tasks/${taskId}`)
export const getTasks = (params = {}) => request.get('/tasks', { params })
export const cancelTask = taskId => request.delete(`/tasks/${taskId}`)
export const createMedicalWorkflow = payload => request.post('/ai/workflows', payload)
export const getHealth = () => request.get('/health')

export default request
