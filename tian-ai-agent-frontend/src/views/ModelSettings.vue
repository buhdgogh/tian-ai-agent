<template>
  <section class="ui-page model-settings">
    <header class="ui-page-heading">
      <div>
        <h1>模型设置</h1>
        <p>配置自己的模型和 API Key，保存后用于当前账号的 Agent 问答和工作流。</p>
      </div>
      <button class="ui-button" type="button" :disabled="busy" @click="reload">重新加载</button>
    </header>

    <p v-if="error" class="ui-notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="busy && !version" class="ui-notice" role="status">正在加载配置…</p>

    <form v-if="version" class="settings-form" @submit.prevent="save">
      <p class="settings-hint">优先级数字越小越先尝试，相同优先级轮询。清空列表后保存，可恢复平台默认模型（如已配置）。</p>
      <fieldset v-for="(row, index) in rows" :key="row.clientId" class="ui-panel provider" :disabled="busy">
        <div class="provider-heading">
          <h2>{{ row.model || '模型 ' + (index + 1) }}</h2>
          <div class="actions">
            <label class="toggle"><input v-model="row.enabled" type="checkbox" /> 启用</label>
            <button class="ui-button" type="button" @click="rows.splice(index, 1)">移除</button>
          </div>
        </div>

        <div class="fields">
          <label>提供商
            <select v-model="row.presetId" class="ui-field" @change="selectPreset(row)">
              <option v-for="preset in presets.filter(item => !item.protocol || item.protocol === row.protocol)" :key="preset.id" :value="preset.id">{{ preset.name }}</option>
            </select>
          </label>
          <label>模型名称
            <input v-model.trim="row.model" class="ui-field" required maxlength="160" placeholder="填写厂商提供的模型 ID" />
          </label>
          <label>API Key
            <input v-model.trim="row.apiKey" class="ui-field" type="password" autocomplete="new-password"
              :required="needsKey(row)" maxlength="4096" :placeholder="needsKey(row) ? '输入你的厂商 API Key' : '已保存，留空可沿用'" />
          </label>
        </div>
        <p v-if="row.presetId !== 'custom'" class="settings-hint endpoint">
          Base URL <code>{{ row.baseUrl }}</code>
        </p>
        <details :open="row.presetId === 'custom' ? true : undefined">
          <summary>高级设置</summary>
          <div class="fields extra">
            <label>接口协议
              <select :value="row.protocol" class="ui-field" @change="selectProtocol(row, $event.target.value)">
                <option value="OPENAI_COMPATIBLE">OpenAI 兼容</option>
                <option value="ANTHROPIC">Anthropic</option>
                <option v-if="row.savedProtocol === 'DASHSCOPE'" value="DASHSCOPE">DashScope 原生</option>
              </select>
            </label>
            <label>Base URL
              <input v-model.trim="row.baseUrl" class="ui-field" type="url" required maxlength="500"
                placeholder="https://api.example.com/v1" @input="row.presetId = 'custom'" />
            </label>
            <label>优先级
              <input v-model.number="row.priority" class="ui-field" type="number" required min="-2147483648" max="2147483647" step="1" />
            </label>
            <label>提供商标识
              <input v-model.trim="row.id" class="ui-field" :readonly="row.hasApiKey" required maxlength="48" pattern="[A-Za-z0-9_\-]+" />
            </label>
            <template v-if="row.protocol === 'OPENAI_COMPATIBLE'">
              <label>Reasoning effort
                <input v-model.trim="row.reasoningEffort" class="ui-field" maxlength="30" placeholder="使用厂商默认值" />
              </label>
              <label class="toggle"><input v-model="row.streamUsage" type="checkbox" /> 请求流式 Token 用量</label>
            </template>
            <label v-else-if="row.protocol === 'DASHSCOPE'">DashScope Workspace ID
              <input v-model.trim="row.workspaceId" class="ui-field" maxlength="160" placeholder="选填" />
            </label>
          </div>
        </details>
      </fieldset>

      <p v-if="rows.length === 0" class="ui-panel empty">暂无个人模型。添加后可选择提供商。</p>
      <div class="actions form-actions">
        <button class="ui-button" type="button" :disabled="busy || rows.length >= 20" @click="add('openai')">添加 OpenAI 兼容模型</button>
        <button class="ui-button" type="button" :disabled="busy || rows.length >= 20" @click="add('claude')">添加 Anthropic 模型</button>
        <button class="ui-button primary" type="submit" :disabled="busy">{{ busy ? '处理中…' : '保存配置' }}</button>
      </div>
      <p class="settings-hint">常见提供商会自动填入地址和协议。其它地域或自建网关可在高级设置中调整，修改地址或协议时需要重新输入对应的 API Key。</p>
    </form>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { getModelProviders, saveModelProviders } from '../api'
import { apiError } from '../utils/medical'

const presets = [
  { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com', protocol: 'OPENAI_COMPATIBLE' },
  { id: 'qwen', name: 'Qwen（阿里云百炼·北京）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', protocol: 'OPENAI_COMPATIBLE' },
  { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', protocol: 'OPENAI_COMPATIBLE' },
  { id: 'claude', name: 'Claude（Anthropic）', baseUrl: 'https://api.anthropic.com', protocol: 'ANTHROPIC' },
  { id: 'dashscope', name: '阿里云百炼（原生）', baseUrl: 'https://dashscope.aliyuncs.com', protocol: 'DASHSCOPE' },
  { id: 'custom', name: '自定义' }
]

const detectPreset = row => presets.find(preset => preset.protocol === row.protocol
  && preset.baseUrl === row.baseUrl?.replace(/\/+$/, ''))?.id || 'custom'
const rows = ref([])
const version = ref('')
const busy = ref(false)
const notice = ref('')
const error = ref('')
let clientId = 0

const apply = data => {
  version.value = data.version
  rows.value = data.providers.map(row => ({
    ...row, clientId: ++clientId, presetId: detectPreset(row), apiKey: '', savedProtocol: row.protocol, savedBaseUrl: row.baseUrl
  }))
}

const needsKey = row => !row.hasApiKey || row.savedProtocol !== row.protocol || row.savedBaseUrl !== row.baseUrl

function providerId(presetId, clientId) {
  let suffix = 1
  while (rows.value.some(row => row.clientId !== clientId && row.id === presetId + '-' + suffix)) suffix++
  return presetId + '-' + suffix
}

function selectPreset(row) {
  const preset = presets.find(item => item.id === row.presetId)
  if (!preset?.baseUrl) return
  if (row.protocol !== preset.protocol || row.baseUrl !== preset.baseUrl) {
    row.apiKey = ''
    row.workspaceId = ''
    row.reasoningEffort = ''
    row.streamUsage = true
  }
  row.protocol = preset.protocol
  row.baseUrl = preset.baseUrl
  if (!row.hasApiKey) row.id = providerId(preset.id, row.clientId)
}

function selectProtocol(row, protocol) {
  row.presetId = { OPENAI_COMPATIBLE: 'openai', ANTHROPIC: 'claude', DASHSCOPE: 'dashscope' }[protocol]
  selectPreset(row)
}

function add(presetId) {
  const preset = presets.find(item => item.id === presetId)
  rows.value.push({
    clientId: ++clientId, id: providerId(preset.id, clientId), presetId: preset.id,
    protocol: preset.protocol, baseUrl: preset.baseUrl, model: '', apiKey: '', enabled: true, hasApiKey: false,
    priority: Math.min(2147483647, Math.max(-1, ...rows.value.map(row => row.priority)) + 1),
    streamUsage: true, reasoningEffort: '', workspaceId: ''
  })
}

const reload = async () => {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const { data } = await getModelProviders()
    apply(data)
  } catch (failure) {
    error.value = apiError(failure)
  } finally {
    busy.value = false
  }
}

const save = async () => {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const providers = rows.value.map(({ id, protocol, baseUrl, model, apiKey, priority, enabled, streamUsage, reasoningEffort, workspaceId }) => ({
      id, protocol, baseUrl, model, apiKey, priority, enabled, streamUsage,
      reasoningEffort: protocol === 'OPENAI_COMPATIBLE' ? reasoningEffort : undefined,
      workspaceId: protocol === 'DASHSCOPE' ? workspaceId : undefined
    }))
    const { data } = await saveModelProviders({ version: version.value, providers })
    apply(data)
    notice.value = '已保存，对后续请求生效。'
  } catch (failure) {
    error.value = failure.response?.data?.message || apiError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.model-settings { max-width: 980px; }
.settings-form { display: grid; gap: 14px; }
.settings-hint { margin: 0; color: var(--muted); font-size: 13px; }
.ui-notice { margin: 0 0 14px; }
.provider { display: grid; gap: 14px; margin: 0; padding: 16px; }
.provider-heading, .actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.provider-heading { justify-content: space-between; }
.provider-heading h2 { margin: 0; font-size: 16px; overflow-wrap: anywhere; }
.fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 14px; }
.fields label { display: grid; align-content: start; gap: 5px; }
label, summary { color: var(--muted); font-size: 13px; }
.fields label.toggle, label.toggle { display: flex; gap: 6px; align-items: center; }
.endpoint code { margin-left: 5px; overflow-wrap: anywhere; }
.extra { margin-top: 10px; }
.empty { margin: 0; padding: 16px; color: var(--muted); }
@media (max-width: 600px) { .fields { grid-template-columns: minmax(0, 1fr); } }
</style>
