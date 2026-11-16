<script setup lang="ts">
import { onMounted } from 'vue'
import { useWorkspaceStore } from './stores/workspace'
import { primaryNavigation, settingsNavigation } from './router'
const workspace = useWorkspaceStore()
onMounted(() => { void workspace.check() })
</script>

<template>
  <div class="desktop-shell">
    <aside class="app-sidebar">
      <RouterLink class="wordmark" to="/agent">
        <span class="wordmark-icon" aria-hidden="true">T</span>
        <span>Tian AI Agent<small>知识工作台</small></span>
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
      <RouterView />
    </div>
  </div>
</template>

<style src="./styles.css"></style>
