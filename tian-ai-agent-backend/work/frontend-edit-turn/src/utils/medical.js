export const newChatId = prefix => `${prefix}_${crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`

export const apiError = error => {
  if (error?.name === 'AbortError' || error?.code === 'ERR_CANCELED') return '请求已停止。'
  const status = error?.response?.status || error?.status
  const body = error?.response?.data
  const message = (typeof body?.error === 'string' ? body.error : body?.error?.message) || body?.message || body?.detail || error?.message
  if (status === 400) return `请求信息不完整或格式有误：${message || '请检查输入后重试。'}`
  if (status === 401) return '登录状态已失效，请重新登录或刷新页面。'
  if (status === 403) return '当前用户无权执行此操作，请确认登录用户。'
  if (status === 409) return '会话状态已变化，回答可能已完成或已有新提问，请刷新后重试。'
  if (status === 404) {
    const url = String(error?.config?.url || '')
    if (/\/auth\/logout(?:[?#]|$)/.test(url)) return '退出登录接口不存在（404）。请重新编译并重启后端，再点击“重试退出”。'
    if (/\/auth\/(?:login|me)(?:[?#]|$)/.test(url)) return '登录接口不存在（404），请检查后端版本及 API 地址。'
    if (/\/ai\/sessions(?:\/|[?#]|$)/.test(url)) return '会话或记录不存在，可能已被删除，请重新选择会话。'
    return '请求的接口或记录不存在（404），请检查后端服务及请求地址。'
  }
  if (status === 429) {
    const retry = error?.response?.headers?.['retry-after'] || error?.retryAfter
    return `请求过于频繁，请${retry ? `在 ${retry} 秒后` : '稍后'}重试。`
  }
  if (status >= 500) return '医疗服务暂时不可用，请稍后重试；如情况紧急，请直接拨打 120。'
  return message || '网络连接异常，请检查服务状态后重试。'
}

export const retryAfterSeconds = error => {
  const raw = error?.response?.headers?.['retry-after'] || error?.retryAfter
  const seconds = Number(raw)
  return Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds) : 0
}

export const normalizeSources = value => Array.isArray(value) ? value.map((source, index) => ({
  ...source,
  citation: source.citation || index + 1,
  title: source.title || source.documentTitle || source.fileName,
  fileName: source.fileName || source.documentName,
  quote: source.quote || source.snippet || source.text,
  page: source.page || source.pageNumber,
  publishedAt: source.publishedAt || source.publishDate
})) : []

const TOOL_LABELS = {
  queryApprovedMedicalKnowledge: '检索已审核医疗知识库',
  getKnowledgeChunkContext: '读取知识片段上下文',
  searchPubMed: '检索 PubMed 文献',
  getPubMedArticle: '读取 PubMed 文献详情',
  verifyPubMedCitation: '核验 PubMed 引用',
  searchClinicalTrials: '检索临床试验',
  searchWeb: '检索公开网页',
  scrapeWebPage: '读取网页内容',
  generatePDF: '生成报告文件',
  readToolResult: '读取完整工具结果'
}

export const toolLabel = name => TOOL_LABELS[name] || `调用工具 ${name}`

export const chunkText = chunk => {
  try {
    const data = JSON.parse(chunk)
    return {
      text: data.content || data.delta?.content || data.delta || data.answer || data.message || data.text || data.data?.content || '',
      sources: normalizeSources(data.sources),
      report: data.report || data.evidenceReport,
      done: data.done === true
    }
  } catch {
    return { text: chunk, sources: [], report: null, done: false }
  }
}

// Agent 后端目前按“每一步”推送文本。这里只公开执行阶段和工具名称，
// 不把模型的原始推理、工具参数或工具返回值暴露到页面。
export const parseAgentEvent = chunk => {
  const part = chunkText(chunk)
  const raw = String(part.text || '')
  const thought = raw.match(/🤔\s*思考[：:]\s*([\s\S]*?)(?=\n?🛠️|$)/)?.[1]?.trim() || ''
  const tools = [...raw.matchAll(/🛠️\s*正在使用工具\s+([\w-]+)\s+获取信息/g)].map(match => match[1])
  const realTools = tools.filter(name => name !== 'doTerminate')
  const trace = []

  if (/^(?:❌\s*错误|执行错误|步骤执行失败)/.test(raw.trim())) {
    return { ...part, text: raw.trim(), trace: [{ type: 'error', title: '执行未完成', status: 'failed' }] }
  }

  if (realTools.length) {
    trace.push({ type: 'thinking', title: '分析问题并选择下一步', detail: `决定调用 ${realTools.map(toolLabel).join('、')}`, status: 'completed' })
    realTools.forEach(name => trace.push({ type: 'tool', title: toolLabel(name), tool: name, detail: '调用已完成', status: 'completed' }))
  }
  if (tools.includes('doTerminate')) trace.push({ type: 'complete', title: '完成信息整理与回答', status: 'completed' })

  const isFinal = tools.length === 0 || tools.includes('doTerminate')
  const answer = isFinal
    ? (thought || raw.replace(/^🤔\s*思考[：:]\s*/, '').trim())
    : ''

  return { ...part, text: answer, trace }
}

export const emergencyMessage = value => {
  const text = typeof value === 'string' ? value : JSON.stringify(value || '')
  if (text.includes('拨打 120') || /"riskLevel"\s*:\s*"EMERGENCY"/i.test(text)) {
    return '检测到可能危及生命的危险信号。请立即拨打 120 或前往最近的急诊；不要等待本系统继续生成。'
  }
  return ''
}

// Agent history combines several SSE steps into one persisted reply. Parse each step separately
// so earlier tool-selection thoughts do not become the visible final answer.
export const parseAgentHistory = value => {
  const raw = String(value || '').replace(/(^|\n)Step\s+\d+:\s*/g, '$1')
  const chunks = raw.split(/(?=🤔\s*思考[：:])/u).filter(chunk => chunk.trim())
  const trace = [], answers = []
  chunks.forEach(chunk => {
    const parsed = parseAgentEvent(chunk)
    trace.push(...parsed.trace)
    if (parsed.text && !/^Terminated:/.test(parsed.text.trim())) answers.push(parsed.text)
  })
  return { content: answers.join('\n\n'), trace }
}

const reportFromContent = content => {
  try {
    const data = JSON.parse(content)
    return data && typeof data === 'object' && data.summary != null && (data.evidence || data.nextSteps || data.redFlags) ? data : null
  } catch { return null }
}

export const normalizeHistory = (data, { agent = false } = {}) => (Array.isArray(data) ? data : data?.messages || [])
  .map(item => {
    const role = String(item.role || item.messageType || item.type || '').toLowerCase()
    const toolCalls = Array.isArray(item.toolCalls) ? item.toolCalls : Array.isArray(item.tool_calls) ? item.tool_calls : []
    let trace = Array.isArray(item.trace) ? item.trace : toolCalls
      .map(call => call?.name || call?.function?.name)
      .filter(name => name && name !== 'doTerminate')
      .map(name => ({ type: 'tool', title: toolLabel(name), tool: name, detail: '历史调用记录', status: 'completed' }))
    const isUser = item.isUser ?? role === 'user'
    const status = String(item.status || 'COMPLETED').toUpperCase()
    let content = item.content ?? item.text ?? item.message ?? ''
    if ((agent || /(?:^|\n)(?:Step\s+\d+:\s*)?🤔\s*思考[：:]/u.test(String(content))) && !isUser && role !== 'system' && role !== 'tool') {
      const parsed = parseAgentHistory(content)
      content = parsed.content
      trace = [...trace, ...parsed.trace]
    }
    const report = item.report || item.evidenceReport || (!isUser && !agent ? reportFromContent(content) : null)
    return {
      id: item.id,
      turnId: item.turnId,
      content: report ? '' : content,
      isUser,
      role,
      status,
      incomplete: !isUser && ['FAILED', 'CANCELLED'].includes(status),
      error: item.metadata?.error || '',
      metadata: item.metadata || {},
      workflow: item.metadata?.workflow,
      workflowProgress: item.metadata?.workflowProgress,
      time: item.time || item.createdAt || Date.now(),
      sources: normalizeSources(item.sources),
      report,
      workflowReview: item.metadata?.workflow?.review,
      trace,
      ragMeta: item.ragMeta || (item.validation || item.retrieval || item.plan ? { validation: item.validation, retrieval: item.retrieval, plan: item.plan, riskLevel: item.riskLevel } : null)
    }
  })
  // Spring AI 会保存没有正文的工具调用消息，这类消息不应渲染为空白气泡。
  .filter(item => item.role !== 'system' && item.role !== 'tool')
  .filter(item => item.status !== 'SUPERSEDED' && !item.metadata.supersededBy)
  .filter(item => String(item.content || '').trim() || item.sources.length || item.report || item.ragMeta || item.trace.length
    || ['RUNNING', 'PENDING', 'FAILED', 'CANCELLED'].includes(item.status))
