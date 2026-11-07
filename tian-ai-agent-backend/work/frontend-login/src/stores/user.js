import { ref } from 'vue'
import { getCurrentUser, login, logout } from '../api'

export const currentUser = ref(null)
let pending = null
let revision = 0

export const clearCurrentUser = () => {
  revision++
  currentUser.value = null
  pending = null
}

// All pages share the server-resolved identity and the same cookie-backed session.
export const loadCurrentUser = (force = false) => {
  if (!force && currentUser.value) return Promise.resolve(currentUser.value)
  if (pending) return pending
  const version = revision
  const request = getCurrentUser().then(({ data }) => {
    if (version !== revision) throw new Error('登录状态已改变，请重试。')
    if (!data?.userId) throw new Error('服务未返回有效的当前用户。')
    currentUser.value = data
    return data
  }).finally(() => { if (pending === request) pending = null })
  pending = request
  return request
}

export const signIn = async credentials => {
  const { data } = await login(credentials)
  clearCurrentUser()
  currentUser.value = data
  return data
}

export const signOut = async () => {
  await logout()
  clearCurrentUser()
}
