import type { ChatMessage } from '../api/workbench'

function turns(messages: ChatMessage[]): ChatMessage[][] {
  const result: ChatMessage[][] = []
  for (const message of messages) {
    if (message.role === 'user' || !result.length) result.push([message])
    else result[result.length - 1].push(message)
  }
  return result
}

export function reconcileHistory(cached: ChatMessage[], received: ChatMessage[]): ChatMessage[] {
  const remote = turns(received)
  const localBefore: ChatMessage[][] = Array.from({ length: remote.length + 1 }, () => [])
  let limit = remote.length
  for (const turn of turns(cached).reverse()) {
    const first = turn[0]
    let match = -1
    if (first.role === 'user' && !turn.some(message => message.localOnly || message.workspace)) {
      for (let index = limit - 1; index >= 0; index--) {
        if (remote[index][0].role === 'user' && remote[index][0].content.trim() === first.content.trim()) {
          match = index
          break
        }
      }
    }
    if (match === -1) {
      // Retain desktop-only turns and replies that did not reach Java. Old server copies are replaced.
      localBefore[limit].unshift(...turn.filter(message => !message.serverHistory))
      continue
    }
    limit = match
    const serverTurn = remote[match]
    const localAnswer = turn.length > 1 ? turn.at(-1) : undefined
    const serverAnswer = serverTurn.length > 1 ? serverTurn.at(-1) : undefined
    serverTurn[0].id = first.id
    if (serverAnswer && localAnswer && localAnswer.content.trim() === serverAnswer.content.trim()) {
      Object.assign(serverAnswer, {
        id: localAnswer.id,
        sources: serverAnswer.sources?.length ? serverAnswer.sources : localAnswer.sources,
        steps: localAnswer.steps?.length ? localAnswer.steps : serverAnswer.steps,
        route: localAnswer.route,
        runId: localAnswer.runId,
        taskId: localAnswer.taskId,
      })
    } else if (!serverAnswer && localAnswer && !localAnswer.serverHistory) {
      serverTurn.push(localAnswer)
    }
  }
  return [...remote.flatMap((turn, index) => [...localBefore[index], ...turn]), ...localBefore[remote.length]]
}
