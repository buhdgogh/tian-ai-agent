<script setup lang="ts">
import { onMounted } from 'vue'
import { useWorkspaceStore } from './stores/workspace'
import { useSessionStore } from './stores/sessions'
import { primaryNavigation, settingsNavigation } from './router'
const workspace = useWorkspaceStore()
const sessions = useSessionStore()
onMounted(() => { void workspace.check(); void sessions.load() })
</script>

<template>
  <div class="desktop-shell">
    <aside class="app-sidebar">
      <RouterLink class="wordmark" to="/agent">
        <span class="wordmark-icon" aria-hidden="true">T</span>
        <span>Tian<small>Agent 工作台</small></span>
      </RouterLink>
      <nav aria-label="功能导航">
        <RouterLink v-for="item in primaryNavigation" :key="item.path" :to="item.path">{{ item.label }}</RouterLink>
      </nav>
      <nav aria-label="设置导航">
        <p>设置</p>
        <RouterLink v-for="item in settingsNavigation" :key="item.path" :to="item.path">{{ item.label }}</RouterLink>
      </nav>
      <RouterLink class="service-link" to="/health">Java 服务状态</RouterLink>
    </aside>
    <div class="app-main">
      <div v-if="sessions.storageError" class="notice row" role="alert">
        <span>{{ sessions.storageError }}</span>
        <button type="button" :disabled="sessions.loading" @click="sessions.load">重试存储</button>
      </div>
      <p v-if="!sessions.hydrated" class="notice" role="status">正在读取桌面数据…</p>
      <RouterView v-else :key="$route.name" />
    </div>
  </div>
</template>

<style src="./styles.css"></style>
