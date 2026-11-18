<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import * as api from '../api/settings'
import { errorText } from '../api/java'
import { newId } from '../api/workbench'
import PageHeading from '../components/PageHeading.vue'

interface Row extends api.AgentPreset { key: string; note: string }
const collection = ref<api.PresetCollection>('skills')
const tabs: { id: api.PresetCollection; label: string }[] = [
  { id: 'skills', label: 'Skills' }, { id: 'prompt-templates', label: 'Prompt 模板' },
]
const rows = ref<Row[]>([])
const error = ref('')
let loadVersion = 0
async function load(id: api.PresetCollection) {
  const version = ++loadVersion
  if (id !== collection.value) rows.value = []
  collection.value = id
  error.value = ''
  try {
    const presets = await api.getAgentPresets(id)
    if (version === loadVersion) {
      rows.value = [...rows.value.filter(row => !row.id), ...presets.map(item => ({ ...item, key: item.id, note: '' }))]
    }
  } catch (failure) {
    if (version === loadVersion) error.value = errorText(failure)
  }
}
function add() {
  let row = rows.value.find(item => !item.id)
  if (!row) {
    row = { id: '', key: newId(), name: '', description: '', content: '', enabled: true, note: '' }
    rows.value.unshift(row)
  }
  const key = row.key
  nextTick(() => {
    const form = document.getElementById('preset-draft-' + key) as HTMLDetailsElement | null
    if (form) form.open = true
    form?.scrollIntoView({ block: 'nearest' })
    form?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true })
  })
}
async function save(row: Row) {
  if (!row.name.trim() || !row.content.trim()) return
  const { name, description, content, enabled } = row
  error.value = ""; try {
  const saved = await api.saveAgentPreset(collection.value, { name, description, content, enabled }, row.id || undefined)
  Object.assign(row, saved, { note: '配置已保存。' })
  } catch (failure) { error.value = errorText(failure) }
}
async function remove(row: Row) {
  if (row.id && !window.confirm('删除此配置？')) return
  if (row.id) { try { await api.deleteAgentPreset(collection.value, row.id) } catch (failure) { error.value = errorText(failure); return } }
  rows.value = rows.value.filter(item => item.key !== row.key)
}
onMounted(() => load(collection.value))
</script>

<template>
  <main class="page stack">
    <PageHeading title="Skills 与 Prompt 模板" detail="维护 Agent 可选择的技能和可插入的提示模板。">
      <button type="button" class="primary" @click="add">{{ collection === 'skills' ? '添加 Skill' : '添加 Prompt 模板' }}</button>
    </PageHeading>
    <div class="tabs" aria-label="配置分类">
      <button v-for="tab in tabs" :key="tab.id" type="button" :aria-pressed="tab.id === collection" @click="load(tab.id)">{{ tab.label }}</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-else-if="!rows.length" class="empty">当前分类暂无配置。</p>
    <details v-for="row in rows" :key="row.key" :id="'preset-draft-' + row.key" class="panel" :open="!row.id">
      <summary>{{ row.name || '新配置' }} <span class="badge">{{ row.enabled ? '启用' : '停用' }}</span></summary>
      <form class="form-stack" @submit.prevent="save(row)">
        <label class="field">名称<input v-model.trim="row.name" required maxlength="80" /></label>
        <label class="field">简介<input v-model.trim="row.description" /></label>
        <label class="field">{{ collection === 'skills' ? 'Skill 指令' : '模板正文' }}<textarea v-model="row.content" required rows="5" /></label>
        <label class="field inline"><input v-model="row.enabled" type="checkbox" />启用</label>
        <div class="row">
          <button type="submit" class="primary">保存</button><button type="button" class="danger" @click="remove(row)">{{ row.id ? '删除配置' : '取消' }}</button>
          <span v-if="row.note" class="notice" role="status">{{ row.note }}</span>
        </div>
      </form>
    </details>
  </main>
</template>
