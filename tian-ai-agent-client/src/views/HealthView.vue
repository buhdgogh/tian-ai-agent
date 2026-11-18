<script setup lang="ts">
import { onMounted } from 'vue'
import type { HealthStatus } from '../api/java'
import ServiceStatus from '../components/ServiceStatus.vue'
import { useHealthStore } from '../stores/health'

const store = useHealthStore()
const labels: Record<HealthStatus, string> = {
  available: '可用',
  unavailable: '不可用',
  timeout: '超时',
}

onMounted(() => { void store.refresh() })
</script>

<template>
  <main>
    <header>
      <h1>Tian Agent 工作台</h1>
      <p>桌面端 · 服务连接</p>
    </header>

    <section aria-labelledby="java-heading">
      <div class="service-title">
        <h2 id="java-heading">Java 服务</h2>
        <button type="button" :disabled="store.pending" @click="store.refresh()">重新检测</button>
      </div>
      <div role="status" aria-live="polite">
        <p v-if="store.pending">正在检测…</p>
        <template v-else-if="store.health">
          <ServiceStatus :state="store.health.status">{{ labels[store.health.status] }}</ServiceStatus>
          <p>{{ store.health.detail }}</p>
          <p v-if="store.health.endpoint" class="endpoint"><code>{{ store.health.endpoint }}</code></p>
        </template>
      </div>
    </section>
  </main>
</template>

<style scoped>
main { max-width: 42rem; margin: 0 auto; padding: 3rem 1.5rem; }
h1 { margin: 0; font-size: 2rem; }
p { margin: 0.625rem 0 0; color: #425569; }
section { margin-top: 1.75rem; padding: 1.25rem; border: 1px solid #d6dfe8; border-radius: 0.75rem; background: #fff; }
.service-title { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 1rem; }
h2 { margin: 0; font-size: 1.125rem; }
button { padding: 0.45rem 0.75rem; color: #174778; background: #edf4fc; border: 1px solid #bed1e4; border-radius: 0.4rem; cursor: pointer; }
button:disabled { opacity: 0.65; cursor: wait; }
.endpoint { overflow-wrap: anywhere; }
</style>
