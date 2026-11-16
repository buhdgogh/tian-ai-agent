import { defineStore } from 'pinia'
import { ref } from 'vue'
import { checkHealth, type JavaHealth } from '../api/java'

export const useHealthStore = defineStore('java-health', () => {
  const health = ref<JavaHealth | null>(null)
  const pending = ref(false)

  async function refresh(): Promise<void> {
    if (pending.value) return
    pending.value = true
    try {
      health.value = await checkHealth()
    } catch {
      health.value = {
        status: 'unavailable',
        endpoint: null,
        detail: '无法调用桌面应用的健康检查。请在 Tauri 窗口中重试。',
        httpStatus: null,
      }
    } finally {
      pending.value = false
    }
  }

  return { health, pending, refresh }
})
