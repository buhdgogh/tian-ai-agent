<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as api from '../api/settings'
import { errorText } from '../api/java'
import { newId } from '../api/workbench'
import PageHeading from '../components/PageHeading.vue'

interface Row extends api.McpServer {
  key: string
  token: string
  clearToken: boolean
  note: string
  error: string
}
const rows = ref<Row[]>([])
const loadError = ref('')
function rowFrom(server?: api.McpServer): Row {
  return { id: '', url: '', enabled: true, hasToken: false, tools: [], ...server, key: server?.id ?? newId(), token: '', clearToken: false, note: '', error: '' }
}
function add() { rows.value.unshift(rowFrom()) }
async function load() { loadError.value = ""; try { rows.value = (await api.getMcpServers()).map(rowFrom) } catch (failure) { loadError.value = errorText(failure) } }
async function act(row: Row, work: () => Promise<void>) {
  row.error = ''; row.note = ''
  try { await work() } catch (error) { row.error = errorText(error) }
}
function discover(row: Row) {
  return act(row, async () => {
    row.tools = await api.discoverMcpTools(row.url, row.tools, row.token, row.id || undefined)
    row.note = '已发现工具，可逐项授权。'
  })
}
function save(row: Row) {
  return act(row, async () => {
    const url = new URL(row.url)
    if (!['https:', 'http:'].includes(url.protocol)) throw new Error('请输入 HTTP 或 HTTPS 端点。')
    const input: api.McpServerUpdate = { url: row.url, enabled: row.enabled, token: row.token, clearToken: row.clearToken, tools: row.tools }
    const saved = row.id ? await api.saveMcpServer(row.id, input) : await api.importMcpServer(input)
    Object.assign(row, saved, { token: '', clearToken: false, note: 'MCP 配置已保存。' })
  })
}
async function remove(row: Row) {
  if (row.id && !window.confirm('删除此 MCP 服务？')) return
  if (row.id) { try { await api.deleteMcpServer(row.id) } catch (failure) { row.error = errorText(failure); return } }
  rows.value = rows.value.filter(item => item.key !== row.key)
}
onMounted(load)
</script>

<template>
  <main class="page stack">
    <PageHeading title="MCP 设置" detail="维护远程端点并选择每个服务允许使用的工具。">
      <div class="row"><button type="button" @click="load">重新加载</button><button type="button" class="primary" @click="add">导入服务</button></div>
    </PageHeading>
    <p v-if="loadError" class="error" role="alert">{{ loadError }}</p><p v-else-if="!rows.length" class="empty">暂无 MCP 服务。</p>
    <details v-for="row in rows" :key="row.key" class="panel" :open="!row.id">
      <summary>{{ row.url || '新 MCP 服务' }} <span class="badge">{{ row.enabled ? '启用' : '停用' }}</span></summary>
      <form class="form-stack" @submit.prevent="save(row)">
        <div class="form-grid">
          <label class="field">MCP 端点<input v-model.trim="row.url" type="url" placeholder="https://example.com/mcp" required /></label>
          <label class="field">Bearer Token<input v-model="row.token" type="password" autocomplete="off" :disabled="row.clearToken" :placeholder="row.hasToken ? 'Token 已保存' : '可选'" /></label>
        </div>
        <div class="row">
          <label class="field inline"><input v-model="row.enabled" type="checkbox" :disabled="!row.id" />启用</label>
          <label v-if="row.hasToken" class="field inline"><input v-model="row.clearToken" type="checkbox" />清除 Token</label>
          <button type="button" :disabled="!row.url" @click="discover(row)">预览工具</button>
        </div>
        <fieldset v-if="row.tools.length" class="stack">
          <legend>工具授权</legend>
          <label v-for="tool in row.tools" :key="tool.name" class="field inline">
            <input v-model="tool.enabled" type="checkbox" /><span><strong>{{ tool.name }}</strong> · {{ tool.description }}</span>
          </label>
        </fieldset>
        <div class="row"><button type="submit" class="primary">保存</button><button type="button" class="danger" @click="remove(row)">{{ row.id ? '删除配置' : '取消' }}</button></div>
        <p v-if="row.note" class="notice" role="status">{{ row.note }}</p>
        <p v-if="row.error" class="error" role="alert">{{ row.error }}</p>
      </form>
    </details>
  </main>
</template>
