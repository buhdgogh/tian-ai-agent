<template>
  <section class="ui-page mcp-settings">
    <header class="ui-page-heading">
      <div>
        <h1>MCP 设置</h1>
        <p>导入已列入后端白名单的 MCP 地址，保存时自动发现并注册工具。</p>
      </div>
      <button type="button" class="ui-button primary" :disabled="busy || rows.length >= 10" @click="add">导入服务</button>
    </header>
    <p v-if="error" class="ui-notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="!rows.length && !busy" class="ui-panel empty">暂无 MCP 服务。请导入 Streamable HTTP 端点。</p>

    <form v-for="row in rows" :key="row.key" class="ui-panel server" @submit.prevent="save(row)">
      <div class="row-header">
        <h2>{{ row.url || '新 MCP 服务' }}</h2>
        <span v-if="row.saved" class="ui-pill" :data-tone="row.enabled ? 'positive' : 'neutral'">{{ row.enabled ? '已启用' : '已停用' }}</span>
        <button type="button" class="ui-button" :aria-expanded="row.expanded" :aria-controls="'mcp-fields-' + row.key"
          @click="row.expanded = !row.expanded">{{ row.expanded ? '折叠' : '展开' }}</button>
      </div>
      <fieldset v-show="row.expanded" :id="'mcp-fields-' + row.key" :disabled="busy || !row.expanded">
        <div class="fields">
          <label>MCP 端点
            <input v-model.trim="row.url" class="ui-field" type="url" maxlength="500" required
              placeholder="https://tools.example.com/mcp" @input="resetTools(row)" />
          </label>
          <label>Bearer Token
            <input v-model.trim="row.token" class="ui-field" type="password" maxlength="4096" autocomplete="new-password"
              :placeholder="row.hasToken ? '留空沿用已保存的 Token' : '服务需要鉴权时填写'" :disabled="row.clearToken" />
          </label>
        </div>

        <div class="actions">
          <label v-if="row.saved" class="check"><input v-model="row.enabled" type="checkbox" /> 启用</label>
          <label v-if="row.hasToken" class="check"><input v-model="row.clearToken" type="checkbox" /> 清除已保存的 Token</label>
          <button type="button" class="ui-button" :disabled="!row.url" @click="discover(row)">预览工具</button>
        </div>

        <div v-if="row.tools.length" class="tools">
          <strong>允许调用的工具</strong>
          <label v-for="tool in row.tools" :key="tool.name" class="tool">
            <input v-model="row.selected" type="checkbox" :value="tool.name" />
            <span><code>{{ tool.name }}</code><small v-if="tool.description">{{ tool.description }}</small></span>
          </label>
        </div>
        <p v-else-if="!row.selectionReady" class="hint">保存时注册该服务返回的可用工具。也可以先预览并选择要授权的工具。</p>

        <div class="actions">
          <button type="submit" class="ui-button primary">{{ !row.selectionReady && row.enabled ? '保存并注册' : '保存' }}</button>
          <button type="button" class="ui-button" @click="remove(row)">{{ row.saved ? '删除配置' : '取消' }}</button>
        </div>
      </fieldset>
    </form>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { deleteMcpServer, discoverMcpTools, getMcpServers, importMcpServer, saveMcpServer } from '../api'
import { apiError } from '../utils/medical'

const rows = ref([])
const busy = ref(false)
const error = ref('')
const notice = ref('')
let nextKey = 0

function form(server = {}, key = ++nextKey, expanded = !server.id) {
  const tools = server.tools || []
  return {
    key, expanded, id: server.id || '', url: server.url || '', enabled: server.enabled ?? true,
    saved: Boolean(server.id), hasToken: Boolean(server.hasToken), token: '', clearToken: false,
    tools, selected: tools.map(tool => tool.name), selectionReady: Boolean(server.id)
  }
}

function add() {
  rows.value.push(form())
}

function resetTools(row) {
  row.tools = []
  row.selected = []
  row.selectionReady = false
}

function connection(row) {
  return { url: row.url, token: row.clearToken ? '' : row.token || undefined }
}

async function request(work) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await work()
  } catch (failure) {
    error.value = failure.response?.data?.message || apiError(failure)
  } finally {
    busy.value = false
  }
}

function discover(row) {
  return request(async () => {
    const { data } = await discoverMcpTools(row.id, connection(row))
    row.tools = data
    row.selectionReady = true
    row.selected = row.selected.filter(name => data.some(tool => tool.name === name))
    notice.value = data.length ? '已发现工具，请勾选后保存。' : '服务未返回可用工具。'
  })
}

function save(row) {
  return request(async () => {
    const body = { ...connection(row), enabled: row.enabled, tools: row.selectionReady ? row.selected : undefined }
    const { data } = await (row.saved ? saveMcpServer(row.id, body) : importMcpServer(body))
    rows.value = rows.value.map(item => item.key === row.key ? form(data, row.key, row.expanded) : item)
    notice.value = data.enabled ? 'MCP 配置已保存，已注册 ' + data.tools.length + ' 个工具。' : 'MCP 配置已停用。'
  })
}

function remove(row) {
  return request(async () => {
    if (row.saved) await deleteMcpServer(row.id)
    rows.value = rows.value.filter(item => item.key !== row.key)
    if (row.saved) notice.value = 'MCP 配置及注册的工具已删除。'
  })
}

onMounted(() => request(async () => {
  const { data } = await getMcpServers()
  rows.value = data.map(server => form(server))
}))
</script>

<style scoped>
.mcp-settings { max-width: 980px; }
.server { margin-top: 12px; padding: 16px; }
.row-header { display: flex; align-items: center; gap: 8px; }
.row-header h2 { flex: 1; min-width: 0; margin: 0; font-size: 16px; overflow-wrap: anywhere; }
.row-header button { flex: none; }
fieldset { border: 0; min-width: 0; padding: 0; margin: 12px 0 0; display: grid; gap: 12px; }
.fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.fields label { display: grid; gap: 5px; font-size: 13px; }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.check, .tool { display: flex; gap: 6px; align-items: flex-start; font-size: 13px; }
.tools { display: grid; gap: 8px; }
.tool code, .tool small { display: block; overflow-wrap: anywhere; }
.hint, .tool small { color: var(--muted); font-size: 12px; }
.empty { padding: 16px; }
@media (max-width: 700px) { .fields { grid-template-columns: 1fr; } }
</style>
