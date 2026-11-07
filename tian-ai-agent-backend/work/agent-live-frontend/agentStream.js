// Pure event reducer: transport, visible execution summaries and final answer text stay separate.
export const createSseParser = onData => {
  let buffer = ''
  const consume = frame => {
    const lines = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
    if (lines.length) onData(lines.map(line => line.slice(5).replace(/^ /, '')).join('\n'))
  }
  return {
    push(text) { buffer += text; const frames = buffer.split(/\r?\n\r?\n/); buffer = frames.pop() || ''; frames.forEach(consume) },
    finish() { if (buffer.trim()) consume(buffer); buffer = '' }
  }
}

export const applyAgentStreamEvent = (message, event) => {
  if (!event || typeof event !== 'object') return false
  if (event.seq && event.seq <= (message.lastEventSeq || 0)) return false
  if (event.seq) message.lastEventSeq = event.seq
  if (event.type === 'task') {
    message.metadata = { ...message.metadata, taskId: event.taskId }
    message.status = 'RUNNING'
  } else if (['progress', 'tool_start', 'tool_end'].includes(event.type)) {
    if (typeof event.progress === 'number') message.workflowProgress = { progress: event.progress, stage: event.stage || event.title, status: 'RUNNING' }
    message.trace ||= []
    const id = event.id || `stage-${event.seq}`
    const item = { id, type: event.type.startsWith('tool_') ? 'tool' : 'thinking', title: event.title || '正在处理',
      detail: event.detail || '', tool: event.tool, status: event.status || (event.type === 'tool_start' ? 'running' : 'completed'), durationMs: event.durationMs }
    const index = message.trace.findIndex(step => step.id === id)
    if (index >= 0) message.trace[index] = item
    else message.trace.push(item)
    if (message.trace.length > 100) message.trace.shift()
  } else if (event.type === 'review') {
    message.workflowReview = event.review
    message.workflow = { ...message.workflow, plan: event.plan, review: event.review }
  } else if (event.type === 'answer_start') {
    message.streamPhase = 'answer'
  } else if (event.type === 'answer_delta') {
    message.streamPhase = 'answer'
    message.content = (message.content || '') + (event.delta || '')
  } else if (event.type === 'done') {
    delete message.workflowProgress
    message.status = event.status || 'COMPLETED'
    message.streamPhase = message.status === 'COMPLETED' ? 'completed' : 'stopped'
    if (typeof event.answer === 'string') message.content = event.answer
    if (event.trace) message.trace = event.trace
    if (event.workflow) { message.workflow = event.workflow; message.workflowReview = event.workflow.review }
    if (event.error) message.error = event.error
    message.incomplete = message.status !== 'COMPLETED'
    return true
  }
  return false
}

export const newTurnId = () => (globalThis.crypto?.randomUUID?.().replaceAll('-', '') || `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`).slice(0, 32)
