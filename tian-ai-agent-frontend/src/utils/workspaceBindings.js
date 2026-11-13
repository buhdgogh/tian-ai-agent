import { API_BASE_URL, getApiToken } from '../api'

const STORE = 'bindings'
let database

function openDatabase() {
  database ||= new Promise((resolve, reject) => {
    const request = indexedDB.open('tian-local-workspaces', 1)
    request.onupgradeneeded = () => request.result.createObjectStore(STORE)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  }).catch(error => {
    database = undefined
    throw error
  })
  return database
}

async function accessBinding(chatId, spaceId, mode, operation) {
  const token = getApiToken()
  if (!chatId || !token || !globalThis.indexedDB || !globalThis.crypto?.subtle) return null
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token))
  const account = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
  const key = [API_BASE_URL, account, spaceId || '', chatId]
  const db = await openDatabase()
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, mode)
    const request = operation(transaction.objectStore(STORE), key)
    transaction.oncomplete = () => resolve(request.result ?? null)
    transaction.onabort = () => reject(request.error || transaction.error)
  })
}

export function getWorkspaceBinding(chatId, spaceId) {
  return accessBinding(chatId, spaceId, 'readonly', (store, key) => store.get(key))
}

export async function bindWorkspace(chatId, spaceId, directory) {
  try {
    const key = await accessBinding(chatId, spaceId, 'readwrite', (store, key) => store.add(directory, key))
    if (!key) throw new Error('无法保存工作区')
    return { directory, created: true }
  } catch (error) {
    if (error.name !== 'ConstraintError') throw error
    const existing = await getWorkspaceBinding(chatId, spaceId)
    if (!existing) throw error
    return { directory: existing, created: false }
  }
}

export function deleteWorkspaceBinding(chatId, spaceId) {
  return accessBinding(chatId, spaceId, 'readwrite', (store, key) => store.delete(key))
}
