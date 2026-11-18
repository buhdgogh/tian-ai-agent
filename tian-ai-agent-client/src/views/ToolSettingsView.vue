<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import * as api from '../api/settings'
import { errorText } from '../api/java'
import PageHeading from '../components/PageHeading.vue'
import { useSessionStore } from '../stores/sessions'
import { STANDALONE_WORKSPACE, useWorkspaceStore } from '../stores/workspace'

const sessions = useSessionStore()
const workspaces = useWorkspaceStore()
let configuredPort = 8080
const tools: { id: api.ToolCredential; title: string; label: string; detail: string }[] = [
  { id: 'mineru', title: 'MinerU 文档解析', label: 'MinerU Token', detail: '未配置时可走本地 Tika 解析。' },
  { id: 'search', title: 'SearchAPI 网页搜索', label: 'SearchAPI Key', detail: '网页搜索使用当前账号配置的 Key。' },
]
const labels: Record<api.CredentialStatus, string> = { PERSONAL: '个人凭据已保存', DEVELOPMENT: '开发配置', UNCONFIGURED: '未配置' }
const status = ref<Record<api.ToolCredential, api.CredentialStatus>>()
const inputs = reactive({ mineru: '', search: '' })
const port = ref(8080)
const token = ref('')
const clearToken = ref(false)
const hasToken = ref(false)
const notice = ref('')
const accessNotice = ref('')
const toolError = ref('')
const accessError = ref('')
const saving = ref(false)
const savingAccess = ref(false)

async function loadTools() {
  toolError.value = ''
  try { status.value = await api.getToolSettings() }
  catch (failure) { status.value = undefined; toolError.value = errorText(failure) }
}
async function load() {
  accessError.value = ''
  try {
    const settings = await api.getDesktopAccess()
    configuredPort = settings.port
    port.value = settings.port
    hasToken.value = settings.hasToken
  } catch (failure) { accessError.value = errorText(failure) }
  await loadTools()
}
async function update(id: api.ToolCredential, clear: boolean) {
  if (saving.value) return
  saving.value = true
  notice.value = ''; toolError.value = ''
  try {
    if (clear) await api.clearToolCredential(id)
    else await api.saveToolCredential(id, inputs[id])
    inputs[id] = ''
    notice.value = clear ? '个人凭据已清除。' : '凭据已保存。'
    await loadTools()
  } catch (failure) { toolError.value = errorText(failure) }
  finally { saving.value = false }
}
async function saveAccess() {
  if (savingAccess.value) return
  savingAccess.value = true
  accessNotice.value = ''; accessError.value = ''
  try {
    const switching = port.value !== configuredPort || Boolean(token.value.trim()) || clearToken.value
    if (switching) {
      if (sessions.runs.size) throw new Error('请等待当前会话执行结束后再更改访问配置。')
      await sessions.flush()
      await Promise.all([...sessions.sessions.map(session => workspaces.release(session.id)), workspaces.release(STANDALONE_WORKSPACE)])
    }
    const settings = await api.saveDesktopAccess(port.value, token.value, clearToken.value)
    configuredPort = settings.port
    if (switching) {
      sessions.reset()
      await sessions.load()
    }
    hasToken.value = settings.hasToken
    token.value = ''
    clearToken.value = false
    accessNotice.value = '访问配置已保存到本机。'
    await loadTools()
  } catch (failure) { accessError.value = errorText(failure) }
  finally { savingAccess.value = false }
}
onMounted(load)
</script>

<template>
  <main class="page stack">
    <PageHeading title="配置中心" detail="管理可选工具凭据与桌面访问配置。"><button type="button" @click="load">重新加载</button></PageHeading>
    <div class="columns">
      <form v-for="tool in tools" :key="tool.id" class="panel form-stack" @submit.prevent="update(tool.id, false)">
        <div class="row spread"><h2>{{ tool.title }}</h2><span v-if="status" class="badge">{{ labels[status[tool.id]] }}</span></div>
        <p class="muted">{{ tool.detail }}</p>
        <label class="field">{{ tool.label }}<input v-model="inputs[tool.id]" type="password" autocomplete="off" required /></label>
        <div class="row">
          <button type="submit" class="primary" :disabled="saving || !status || !inputs[tool.id].trim()">保存</button>
          <button v-if="status?.[tool.id] === 'PERSONAL'" type="button" @click="update(tool.id, true)">清除</button>
        </div>
      </form>
    </div>
    <p v-if="toolError" class="error" role="alert">{{ toolError }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <section class="panel row spread" aria-label="桌面数据存储">
      <strong>桌面数据存储</strong>
      <span v-if="sessions.backend" class="badge">{{ sessions.backend === 'mysql' ? 'MySQL' : '本机 SQLite' }}</span>
      <span v-else class="muted">会话库待连接</span>
    </section>
    <form class="panel form-stack" @submit.prevent="saveAccess">
      <div class="row spread"><h2>访问配置</h2><RouterLink to="/health">查看实际服务状态</RouterLink></div>
      <p class="muted">连接当前主机的 Java 服务。此设置保存在本机，重启后自动恢复。</p>
      <div class="form-grid">
        <label class="field">Java 服务端口<input v-model.number="port" type="number" min="1" max="65535" required /></label>
        <label class="field">访问令牌<input v-model="token" type="password" autocomplete="off" :disabled="clearToken" :placeholder="hasToken ? '已配置令牌' : '输入 Java 访问令牌'" /></label>
      </div>
      <label v-if="hasToken" class="field inline"><input v-model="clearToken" type="checkbox" />清除令牌</label>
      <p v-if="accessError" class="error" role="alert">{{ accessError }}</p><div class="row"><button type="submit" class="primary" :disabled="savingAccess">保存访问配置</button><span v-if="accessNotice" class="notice" role="status">{{ accessNotice }}</span></div>
    </form>
  </main>
</template>
