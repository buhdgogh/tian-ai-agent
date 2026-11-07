export const taskIsRunning = status => ['PENDING', 'RUNNING', 'QUEUED', 'PROCESSING'].includes(String(status).toUpperCase())

export const parseWorkflow = value => {
  if (typeof value !== 'string') return value || {}
  try { return JSON.parse(value) } catch { return { executionResult: value } }
}

export const applyTaskSnapshot = (message, task) => {
  const status = String(task.status || 'PENDING').toUpperCase()
  message.metadata = { ...message.metadata, taskId: task.id }
  message.workflowProgress = { progress: task.progress || 0, stage: task.stage || '等待执行', status }
  message.status = taskIsRunning(status) ? (status === 'PENDING' ? 'PENDING' : 'RUNNING') : status
  if (status === 'COMPLETED') {
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
