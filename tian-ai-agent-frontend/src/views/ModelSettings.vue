<template>
  <section class="ui-page model-settings">
    <header class="ui-page-heading">
      <div>
        <h1>模型设置</h1>
        <p>配置自己的模型和 API Key。对话模型用于 Agent 问答和工作流，嵌入模型可在创建知识库时选择。</p>
      </div>
      <button class="ui-button" type="button" :disabled="busy" @click="reload">重新加载</button>
    </header>

    <div class="actions model-categories" role="group" aria-label="模型分类">
      <button v-for="category in modelCategories" :key="category.id" class="ui-button" type="button"
        :class="{ primary: activeCategory === category.id }" :aria-pressed="activeCategory === category.id"
        @click="activeCategory = category.id">{{ category.title }}</button>
    </div>

    <p v-if="error" class="ui-notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="busy && !version" class="ui-notice" role="status">正在加载配置…</p>

    <form v-if="version" class="settings-form" @submit.prevent="save" @invalid.capture="showInvalidCategory">
      <section v-for="group in modelGroups" v-show="activeCategory === group.id" :key="group.id"
        class="settings-form" :aria-label="group.title" :data-model-category="group.id">
      <div class="actions">
        <button v-for="button in group.buttons" :key="button.id" class="ui-button" type="button"
          :disabled="busy || (rows.length >= 20 && !findDraft(group.embedding))" @click="add(button.id)">{{ button.label }}</button>
      </div>
      <p v-if="!group.embedding" class="settings-hint">优先级数字越小越先尝试，相同优先级轮询。</p>
      <fieldset v-for="row in rows.filter(item => isEmbedding(item) === group.embedding)" :key="row.clientId" :id="`model-draft-${row.clientId}`" class="ui-panel provider" :disabled="busy">
        <div class="provider-heading">
          <h3>{{ row.model || '新模型' }}</h3>
          <div class="actions">
            <label class="toggle"><input v-model="row.enabled" type="checkbox" /> 启用</label>
            <button class="ui-button" type="button" @click="rows.splice(rows.indexOf(row), 1)">移除</button>
          </div>
        </div>

        <div class="fields">
          <label>提供商
            <select v-model="row.presetId" class="ui-field" @change="selectPreset(row)">
              <option v-for="preset in presets.filter(item => !item.protocol || item.protocol === row.protocol)" :key="preset.id" :value="preset.id">{{ preset.name }}</option>
            </select>
          </label>
          <label>模型名称
            <input v-model.trim="row.model" data-model-name class="ui-field" required maxlength="160" placeholder="填写厂商提供的模型 ID" />
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
                <option value="OPENAI_EMBEDDING">OpenAI 兼容嵌入</option>
                <option v-if="row.savedProtocol === 'DASHSCOPE'" value="DASHSCOPE">DashScope 原生</option>
              </select>
            </label>
            <label>Base URL
              <input v-model.trim="row.baseUrl" class="ui-field" type="url" required maxlength="500"
                placeholder="https://api.example.com/v1" :aria-describedby="isEmbedding(row) ? 'embedding-url-help-' + row.clientId : undefined"
                @input="row.presetId = 'custom'" @invalid="explainWorkspacePlaceholder" />
              <small v-if="isEmbedding(row)" :id="'embedding-url-help-' + row.clientId" class="settings-hint"
                :role="hasWorkspacePlaceholder(row.baseUrl) ? 'alert' : undefined">
                {{ hasWorkspacePlaceholder(row.baseUrl)
                  ? '请将占位符（含括号）替换为百炼控制台中的真实业务空间 ID。'
                  : '百炼北京专属地址可直接填写，保存时会将 /api/v1 转为 /compatible-mode/v1。' }}
              </small>
            </label>
            <label v-if="!isEmbedding(row)">优先级
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
      <p v-if="!rows.some(row => isEmbedding(row) === group.embedding)" class="ui-panel empty">暂无个人配置。</p>
      </section>

      <section v-show="activeCategory === 'free'" class="settings-form" aria-label="免费模型" data-model-category="free">
        <fieldset class="ui-panel provider" :disabled="busy">
          <div class="provider-heading"><h3>OpenCode</h3></div>
          <ul class="free-models"><li v-for="model in freeModels" :key="model"><code>{{ model }}</code></li></ul>
          <p class="settings-hint">以上模型共用一份 OpenCode API Key。保存后可在 Agent 问答中选择。</p>
          <div class="fields">
            <label>OpenCode API Key
              <input v-model.trim="openCodeApiKey" class="ui-field" type="password" autocomplete="new-password"
                maxlength="4096" :disabled="clearOpenCodeKey" :placeholder="hasOpenCodeKey ? '已保存，留空可沿用' : '输入 OpenCode API Key'" />
            </label>
            <label v-if="hasOpenCodeKey" class="toggle"><input v-model="clearOpenCodeKey" type="checkbox" /> 删除已保存的 Key</label>
          </div>
        </fieldset>
      </section>
      <div class="actions">
        <button class="ui-button primary" type="submit" :disabled="busy">{{ busy ? '处理中…' : '保存配置' }}</button>
      </div>
      <p class="settings-hint">常见提供商会自动填入地址和协议。其它地域或自建网关可在高级设置中调整，修改地址或协议时需要重新输入对应的 API Key。</p>
    </form>
  </section>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { getModelProviders, saveModelProviders } from '../api'
import { apiError } from '../utils/medical'

const presets = [
  { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com', protocol: 'OPENAI_COMPATIBLE' },
  { id: 'qwen', name: 'Qwen（阿里云百炼·北京）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', protocol: 'OPENAI_COMPATIBLE' },
  { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', protocol: 'OPENAI_COMPATIBLE' },
  { id: 'claude', name: 'Claude（Anthropic）', baseUrl: 'https://api.anthropic.com', protocol: 'ANTHROPIC' },
  { id: 'dashscope', name: '阿里云百炼（原生）', baseUrl: 'https://dashscope.aliyuncs.com', protocol: 'DASHSCOPE' },
  { id: 'openai-embedding', name: 'OpenAI 嵌入', baseUrl: 'https://api.openai.com/v1', protocol: 'OPENAI_EMBEDDING' },
  { id: 'qwen-embedding', name: '阿里云百炼嵌入（北京）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', protocol: 'OPENAI_EMBEDDING' },
  { id: 'custom', name: '自定义' }
]

const detectPreset = row => presets.find(preset => preset.protocol === row.protocol
  && preset.baseUrl === row.baseUrl?.replace(/\/+$/, ''))?.id || 'custom'
const rows = ref([])
const modelGroups = [
  { id: 'llm', title: 'LLM 模型', embedding: false, buttons: [{ id: 'openai', label: '添加 OpenAI 兼容模型' }, { id: 'claude', label: '添加 Anthropic 模型' }] },
  { id: 'embedding', title: 'Embedding 模型', embedding: true, buttons: [{ id: 'openai-embedding', label: '添加向量嵌入模型' }] }
]
const modelCategories = [...modelGroups, { id: 'free', title: '免费模型' }]
const activeCategory = ref('llm')
const freeModels = ref([])
const hasOpenCodeKey = ref(false)
const openCodeApiKey = ref('')
const clearOpenCodeKey = ref(false)
const version = ref('')
const busy = ref(false)
const notice = ref('')
const error = ref('')
let clientId = 0
let invalidCategoryPending = false

const apply = data => {
  version.value = data.version
  freeModels.value = data.openCodeFree?.models ?? []
  hasOpenCodeKey.value = data.openCodeFree?.hasApiKey ?? false
  openCodeApiKey.value = ''
  clearOpenCodeKey.value = false
  rows.value = data.providers.map(row => ({
    ...row, clientId: ++clientId, presetId: detectPreset(row), apiKey: '', savedProtocol: row.protocol, savedBaseUrl: row.baseUrl
  }))
}

const isEmbedding = row => row.protocol === 'OPENAI_EMBEDDING'
const hasWorkspacePlaceholder = value => /\[workspace-?id\]|\{workspace-?id\}/i.test(value)

function explainWorkspacePlaceholder(event) {
  if (!event.target.hasAttribute('aria-describedby') || !hasWorkspacePlaceholder(event.target.value)) return
  event.preventDefault()
  event.target.focus()
}

function showInvalidCategory(event) {
  const field = event.target
  const category = field.closest('[data-model-category]')?.dataset.modelCategory
  if (!category || category === activeCategory.value) return
  event.preventDefault()
  if (invalidCategoryPending) return
  invalidCategoryPending = true
  nextTick(() => {
    activeCategory.value = category
    nextTick(() => {
      invalidCategoryPending = false
      field.reportValidity()
    })
  })
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
  row.presetId = { OPENAI_COMPATIBLE: 'openai', ANTHROPIC: 'claude', DASHSCOPE: 'dashscope', OPENAI_EMBEDDING: 'openai-embedding' }[protocol]
  selectPreset(row)
}

function findDraft(embedding) {
  return rows.value.find(row => row.savedProtocol === undefined && isEmbedding(row) === embedding
    && (!row.model || !row.apiKey || !row.baseUrl || !row.id))
}

function revealDraft(row) {
  nextTick(() => {
    const form = document.getElementById('model-draft-' + row.clientId)
    form?.scrollIntoView({ block: 'nearest' })
    form?.querySelector('[data-model-name]')?.focus({ preventScroll: true })
  })
}

function add(presetId) {
  const preset = presets.find(item => item.id === presetId)
  if (!preset || busy.value) return

  const draft = findDraft(preset.protocol === 'OPENAI_EMBEDDING')
  if (draft) {
    if (!draft.model && !draft.apiKey && draft.presetId !== 'custom' && draft.presetId !== preset.id) {
      draft.presetId = preset.id
      selectPreset(draft)
    }
    revealDraft(draft)
    return
  }

  if (rows.value.length >= 20) return
  const row = {
    clientId: ++clientId, id: providerId(preset.id, clientId), presetId: preset.id,
    protocol: preset.protocol, baseUrl: preset.baseUrl, model: '', apiKey: '', enabled: true, hasApiKey: false,
    priority: Math.min(2147483647, Math.max(-1, ...rows.value.map(row => row.priority)) + 1),
    streamUsage: true, reasoningEffort: '', workspaceId: ''
  }
  rows.value.push(row)
  revealDraft(row)
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
    const openCodeFree = { apiKey: clearOpenCodeKey.value ? undefined : openCodeApiKey.value, clear: clearOpenCodeKey.value }
    const { data } = await saveModelProviders({ version: version.value, providers, openCodeFree })
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
.settings-form { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; }
.settings-hint { margin: 0; color: var(--muted); font-size: 13px; }
.ui-notice { margin: 0 0 14px; }
.provider { display: grid; grid-template-columns: minmax(0, 1fr); min-inline-size: 0; gap: 14px; margin: 0; padding: 16px; }
.provider-heading, .actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.provider-heading { justify-content: space-between; }
.model-categories { margin-bottom: 16px; }
.provider-heading h3 { margin: 0; font-size: 16px; overflow-wrap: anywhere; }
.free-models { margin: 0; padding-inline-start: 20px; overflow-wrap: anywhere; }
.fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 14px; }
.fields label { display: grid; align-content: start; gap: 5px; }
label, summary { color: var(--muted); font-size: 13px; }
.fields label.toggle, label.toggle { display: flex; gap: 6px; align-items: center; }
.endpoint code { margin-left: 5px; overflow-wrap: anywhere; }
.extra { margin-top: 10px; }
.empty { margin: 0; padding: 16px; color: var(--muted); }
@media (max-width: 600px) { .fields { grid-template-columns: minmax(0, 1fr); } }
</style>
