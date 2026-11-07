export const taskIsRunning = status => ['PENDING', 'RUNNING', 'QUEUED', 'PROCESSING'].includes(String(status).toUpperCase())

export const parseWorkflow = value => {
  if (typeof value !== 'string') return value || {}
  try { return JSON.parse(value) } catch { return { executionResult: value } }
}

export const taskWorkflow = task => {
  const result = parseWorkflow(task.result)
  return result.workflow || result
}

export const taskAnswer = task => {
  const result = parseWorkflow(task.result)
  return result.answer ?? taskWorkflow(task).executionResult ?? ''
}

export const mergeAgentTasks = responses => {
  const records = responses.flatMap(response => {
    const data = response.data
    return Array.isArray(data) ? data : (data?.items || data?.tasks || [])
  })
  return [...new Map(records.map(task => [task.id, task])).values()]
    .sort((a, b) => (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0))
}

export const taskStepState = (task, index) => {
  const workflow = taskWorkflow(task)
  const status = String(workflow.plan?.steps?.[index]?.status || '').toUpperCase()
  if (status === 'FAILED') return 'failed'
  if (status === 'SKIPPED' || status === 'CANCELLED') return 'stopped'
  if (status === 'COMPLETED' || workflow.executions?.[index]) return 'done'
  if (taskIsRunning(task.status) && (status === 'RUNNING' || index === (workflow.executions?.length || 0))) return 'active'
  return 'pending'
}

export const applyTaskSnapshot = (message, task) => {
  const status = String(task.status || 'PENDING').toUpperCase()
  message.metadata = { ...message.metadata, taskId: task.id }
  if (taskIsRunning(status)) message.workflowProgress = { progress: task.progress || 0, stage: task.stage || '等待执行', status }
  else {
    delete message.workflowProgress
    delete message.metadata.workflowProgress
    delete message.trackingError
    message.streamPhase = status === 'COMPLETED' ? 'completed' : 'stopped'
    message.metadata.streamPhase = message.streamPhase
  }
  message.status = taskIsRunning(status) ? (status === 'PENDING' ? 'PENDING' : 'RUNNING') : status
  if (status === 'COMPLETED') {
    delete message.error
    delete message.incomplete
    const result = parseWorkflow(task.result)
    if (typeof result.answer === 'string') {
      message.content = result.answer
      message.trace = result.trace || message.trace || []
      message.streamPhase = 'completed'
      if (result.workflow) { message.workflow = result.workflow; message.workflowReview = result.workflow.review; message.metadata.workflow = result.workflow }
      return false
    }
    const workflow = result
    message.content = workflow.executionResult || '本次流程未返回有效结果。'
    message.workflow = workflow
    message.workflowReview = workflow.review
    message.metadata.workflow = workflow
  } else if (status === 'FAILED' || status === 'CANCELLED') {
    message.error = task.error || (status === 'FAILED' ? '任务执行失败，请重试。' : '')
    message.incomplete = true
  }
  return taskIsRunning(status)
}

export const waitForTaskPoll = signal => new Promise((resolve, reject) => {
  const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
  const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, 1500)
  if (signal.aborted) abort()
  else signal.addEventListener('abort', abort, { once: true })
})
