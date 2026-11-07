export const editableLastQuestion = messages => {
  const active = messages.filter(message => message.status !== 'SUPERSEDED' && !message.metadata?.supersededBy)
  const user = active.findLast(message => message.isUser)
  if (!user?.turnId) return null
  const reply = active.findLast(message => !message.isUser && message.turnId === user.turnId)
  return reply && ['CANCELLED', 'FAILED'].includes(reply.status) ? user : null
}
