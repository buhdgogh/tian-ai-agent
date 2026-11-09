<template>
  <section class="ui-page mcp-settings">
    <header class="ui-page-heading">
      <div>
        <h1>MCP 设置</h1>
        <p>连接已列入后端白名单的 MCP 服务，选择允许 Agent 调用的工具。</p>
      </div>
      <button type="button" class="ui-button primary" :disabled="busy || rows.length >= 10" @click="add">添加服务</button>
    </header>
    <p v-if="error" class="ui-notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="!rows.length && !busy" class="ui-panel empty">暂无 MCP 服务。请添加 Streamable HTTP 端点。</p>

    <form v-for="row in rows" :key="row.key" class="ui-panel server" @submit.prevent="save(row)">
      <fieldset :disabled="busy">
        <div class="fields">
          <label>服务标识
            <input v-model.trim="row.id" class="ui-field" :readonly="row.saved" required maxlength="24"
              pattern="[a-z0-9][a-z0-9_\-]{0,23}" placeholder="例如 team-docs" />
          </label>
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
          <label class="check"><input v-model="row.enabled" type="checkbox" /> 启用</label>
          <label v-if="row.hasToken" class="check"><input v-model="row.clearToken" type="checkbox" /> 清除已保存的 Token</label>
          <button type="button" class="ui-button" :disabled="!row.id || !row.url" @click="discover(row)">发现工具</button>
        </div>

        <div v-if="row.tools.length" class="tools">
          <strong>允许调用的工具</strong>
          <label v-for="tool in row.tools" :key="tool.name" class="tool">
            <input v-model="row.selected" type="checkbox" :value="tool.name" />
            <span><code>{{ tool.name }}</code><small v-if="tool.description">{{ tool.description }}</small></span>
          </label>
        </div>
        <p v-else class="hint">发现工具后勾选需要授权的项目，再保存。</p>

        <div class="actions">
          <button type="submit" class="ui-button primary">保存</button>
          <button type="button" class="ui-button" @click="remove(row)">{{ row.saved ? '移除' : '取消' }}</button>
        </div>
      </fieldset>
    </form>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { deleteMcpServer, discoverMcpTools, getMcpServers, saveMcpServer } from '../api'
import { apiError } from '../utils/medical'

const rows = ref([])
const busy = ref(false)
const error = ref('')
const notice = ref('')
let nextKey = 0

function form(server = {}, key = ++nextKey) {
  const tools = server.tools || []
  return {
    key, id: server.id || '', url: server.url || '', enabled: server.enabled ?? true,
    saved: Boolean(server.id), hasToken: Boolean(server.hasToken), token: '', clearToken: false,
    tools, selected: tools.map(tool => tool.name)
  }
}

function add() {
  rows.value.push(form())
}

function resetTools(row) {
  row.tools = []
  row.selected = []
}

function connection(row) {
  if (rows.value.some(item => item.key !== row.key && item.id === row.id)) {
    throw new Error('服务标识已存在，请使用不同的标识。')
  }
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
    row.selected = row.selected.filter(name => data.some(tool => tool.name === name))
    notice.value = data.length ? '已发现工具，请勾选后保存。' : '服务未返回可用工具。'
  })
}

function save(row) {
  return request(async () => {
    const { data } = await saveMcpServer(row.id, { ...connection(row), enabled: row.enabled, tools: row.selected })
    rows.value = rows.value.map(item => item.key === row.key ? form(data, row.key) : item)
    notice.value = 'MCP 配置已保存。'
  })
}

function remove(row) {
  return request(async () => {
    if (row.saved) await deleteMcpServer(row.id)
    rows.value = rows.value.filter(item => item.key !== row.key)
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
fieldset { border: 0; min-width: 0; padding: 0; margin: 0; display: grid; gap: 12px; }
.fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.fields label { display: grid; gap: 5px; font-size: 13px; }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.check, .tool { display: flex; gap: 6px; align-items: flex-start; font-size: 13px; }
.tools { display: grid; gap: 8px; }
.tool code, .tool small { display: block; overflow-wrap: anywhere; }
.hint, .tool small { color: var(--muted); font-size: 12px; }
.empty { padding: 16px; }
@media (max-width: 700px) { .fields { grid-template-columns: 1fr; } }
</style>
