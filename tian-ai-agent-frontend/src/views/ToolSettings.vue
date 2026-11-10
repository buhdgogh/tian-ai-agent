<template>
  <section class="ui-page tool-settings">
    <header class="ui-page-heading">
      <div>
        <h1>配置中心</h1>
        <p>为当前账号配置文档解析和网页搜索凭据。</p>
      </div>
      <button class="ui-button" type="button" :disabled="busy" @click="reload">重新加载</button>
    </header>
    <p v-if="error" class="ui-notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <div v-if="settings" class="settings-grid">
      <form v-for="tool in tools" :key="tool.id" class="ui-panel tool-card" @submit.prevent="save(tool)">
        <div class="tool-heading">
          <h2>{{ tool.name }}</h2>
          <span class="ui-pill" :data-tone="settings[tool.id] === 'PERSONAL' ? 'positive' : 'neutral'">
            {{ labels[settings[tool.id]] }}
          </span>
        </div>
        <p>{{ tool.description }}</p>
        <label :for="'tool-' + tool.id">{{ tool.field }}</label>
        <input :id="'tool-' + tool.id" v-model="inputs[tool.id]" class="ui-field" type="password"
          autocomplete="new-password" maxlength="4096" placeholder="输入新的凭据" :disabled="busy" />
        <div class="actions">
          <button v-if="settings[tool.id] === 'PERSONAL'" type="button" class="ui-button" :disabled="busy" @click="clear(tool)">
            清除个人凭据
          </button>
          <button type="submit" class="ui-button primary" :disabled="busy || !inputs[tool.id].trim()">保存</button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getToolSettings, saveToolCredential, clearToolCredential } from '../api'
import { apiError } from '../utils/medical'

const tools = [
  { id: 'mineru', name: 'MinerU 文档解析', field: 'MinerU Token', description: '上传文档时优先使用 MinerU。缺少 Token 或解析失败时，使用本地 Tika。' },
  { id: 'search', name: 'SearchAPI 网页搜索', field: 'SearchAPI Key', description: 'Agent 调用网页搜索工具时使用当前账号的 Key。' }
]
const labels = { PERSONAL: '个人凭据已保存', DEVELOPMENT: '使用本机开发配置', UNCONFIGURED: '未配置' }
const inputs = reactive({ mineru: '', search: '' })
const settings = ref(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')

async function update(action, success = '') {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    const { data } = await getToolSettings()
    settings.value = data
    notice.value = success
  } catch (failure) {
    error.value = apiError(failure)
  } finally {
    busy.value = false
  }
}

const reload = () => update(async () => {})
const save = tool => update(async () => {
  await saveToolCredential(tool.id, inputs[tool.id].trim())
  inputs[tool.id] = ''
}, '已保存 ' + tool.field + '。')
const clear = tool => update(async () => {
  await clearToolCredential(tool.id)
  inputs[tool.id] = ''
}, '已清除个人 ' + tool.field + '。')

onMounted(reload)
</script>

<style scoped>
.tool-settings { max-width: 980px; }
.settings-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 290px), 1fr)); gap: 14px; }
.tool-card { display: grid; gap: 10px; padding: 16px; }
.tool-heading, .actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.tool-card h2 { margin: 0; font-size: 16px; }
.tool-card p, .tool-card label { margin: 0; color: var(--muted); font-size: 13px; }
.actions { justify-content: flex-end; }
</style>
