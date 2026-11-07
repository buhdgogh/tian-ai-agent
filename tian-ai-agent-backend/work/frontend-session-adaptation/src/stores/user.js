import { ref } from 'vue'
import { getCurrentUser, login } from '../api'

export const currentUser = ref(null)
let pending = null

// All pages share the server-resolved identity and the same cookie-backed session.
export const loadCurrentUser = (force = false) => {
  if (!force && currentUser.value) return Promise.resolve(currentUser.value)
  if (pending) return pending
  pending = getCurrentUser().then(({ data }) => {
    if (!data?.userId) throw new Error('服务未返回有效的当前用户。')
    currentUser.value = data
    return data
  }).finally(() => { pending = null })
  return pending
}

export const signIn = async credentials => {
  const { data } = await login(credentials)
  currentUser.value = data
  return data
}
