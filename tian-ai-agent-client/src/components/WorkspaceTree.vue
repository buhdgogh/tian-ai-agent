<script setup lang="ts">
import { ref, watch } from 'vue'
import { listSandboxFiles, sandboxError, type FileEntry } from '../api/sandbox'

const props = defineProps<{ workspaceId: string; sandboxId: string; path: string; revision: number }>()
const entries = ref<FileEntry[]>([])
const expanded = ref<string[]>([])
const loading = ref(false)
const error = ref('')
let generation = 0

watch(() => [props.workspaceId, props.sandboxId, props.path, props.revision], async () => {
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const files = await listSandboxFiles(props.workspaceId, props.sandboxId, props.path)
    if (ticket === generation) {
      entries.value = files
      expanded.value = expanded.value.filter(path => files.some(file => file.path === path && file.kind === 'directory'))
    }
  } catch (failure) {
    if (ticket === generation) {
      entries.value = []
      error.value = sandboxError(failure)
    }
  } finally {
    if (ticket === generation) loading.value = false
  }
}, { immediate: true })

function toggle(path: string) {
  expanded.value = expanded.value.includes(path)
    ? expanded.value.filter(value => value !== path)
    : [...expanded.value, path]
}
</script>

<template>
  <p v-if="loading" class="muted" role="status">正在读取目录…</p>
  <p v-else-if="error" class="error" role="alert">{{ error }}</p>
  <p v-else-if="!entries.length" class="muted">空目录</p>
  <template v-else>
    <ul class="tree-list">
      <li v-for="entry in entries" :key="entry.path">
        <template v-if="entry.kind === 'directory'">
          <button type="button" class="tree-folder" :aria-expanded="expanded.includes(entry.path)" @click="toggle(entry.path)">
            <span aria-hidden="true">{{ expanded.includes(entry.path) ? '▾' : '▸' }}</span> {{ entry.name }}
          </button>
          <WorkspaceTree v-if="expanded.includes(entry.path)" :workspace-id="workspaceId" :sandbox-id="sandboxId" :path="entry.path" :revision="revision" />
        </template>
        <span v-else class="tree-file">{{ entry.name }}<small v-if="entry.kind === 'symlink'"> · 链接</small></span>
      </li>
    </ul>
    <p v-if="entries.length === 200" class="muted">每级最多显示 200 项。</p>
  </template>
</template>

<style scoped>
.tree-list { list-style: none; margin: .15rem 0; padding-left: .85rem; border-left: 1px solid var(--line); }
.tree-folder { border: 0; text-align: left; padding: .22rem .3rem; background: transparent; }
.tree-file { display: block; padding: .22rem .3rem .22rem 1.15rem; }
.tree-folder, .tree-file { overflow-wrap: anywhere; }
</style>
