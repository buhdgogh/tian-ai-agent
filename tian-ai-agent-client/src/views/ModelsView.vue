<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import * as api from '../api/settings'
import { errorText } from '../api/java'
import { newId } from '../api/workbench'
import PageHeading from '../components/PageHeading.vue'

type Category = 'llm' | 'embedding' | 'free'
interface Row extends api.ModelProvider { key: string; apiKey: string; draft?: true }
const tabs: { id: Category; label: string }[] = [
  { id: 'llm', label: 'LLM 模型' }, { id: 'embedding', label: 'Embedding 模型' }, { id: 'free', label: '免费模型' },
]
const category = ref<Category>('llm')
const rows = ref<Row[]>([])
const freeModels = ref<string[]>([])
const hasOpenCodeKey = ref(false)
const openCodeKey = ref('')
const clearOpenCodeKey = ref(false)
const notice = ref('')
const error = ref('')
const version = ref('')
const saving = ref(false)
const visible = computed(() => rows.value.filter(row => (row.protocol === 'OPENAI_EMBEDDING') === (category.value === 'embedding')))

function apply(settings: api.ModelSettings) {
  version.value = settings.version
  rows.value = settings.providers.map(row => ({ ...row, key: row.id, apiKey: '' }))
  freeModels.value = settings.openCodeFree.models
  hasOpenCodeKey.value = settings.openCodeFree.hasApiKey
  openCodeKey.value = ''
  clearOpenCodeKey.value = false
}
async function load() {
  notice.value = ''; error.value = ''
  try { apply(await api.getModelProviders()) } catch (failure) { version.value = ""; error.value = errorText(failure) }
}
function add() {
  const existing = visible.value.find(row => row.draft
    && (!row.model.trim() || !row.baseUrl.trim() || !row.apiKey.trim() || !row.id.trim()))
  if (existing) {
    revealDraft(existing)
    return
  }

  const embedding = category.value === 'embedding'
  const id = (embedding ? 'embed-' : 'chat-') + newId().slice(0, 8)
  const row: Row = {
    id, key: id, model: '', baseUrl: '', enabled: true, apiKey: '', hasApiKey: false, priority: 0, draft: true,
    protocol: embedding ? 'OPENAI_EMBEDDING' : 'OPENAI_COMPATIBLE',
  }
  rows.value.push(row)
  revealDraft(row)
}

function revealDraft(row: Row) {
  nextTick(() => {
    const form = document.getElementById('model-draft-' + row.key)
    form?.scrollIntoView({ block: 'nearest' })
    form?.querySelector<HTMLElement>('[data-model-name]')?.focus({ preventScroll: true })
  })
}
async function save() {
  notice.value = ''; error.value = ''
  const ids = new Set<string>()
  for (const { id, model, baseUrl } of rows.value) {
    if (!id.trim() || !model.trim() || !baseUrl.trim()) { error.value = '请补全所有分类中新增模型的标识、名称和 Base URL。'; return }
    if (ids.has(id)) { error.value = '模型标识不能重复。'; return }
    ids.add(id)
  }
  if (!version.value || saving.value) return
  saving.value = true
  try {
    apply(await api.saveModelProviders({
      version: version.value,
      providers: rows.value.map(({ key, draft, ...provider }) => provider),
      openCodeFree: { apiKey: openCodeKey.value, clear: clearOpenCodeKey.value },
    }))
    notice.value = '模型配置已保存。'
  } catch (failure) {
    error.value = errorText(failure)
  } finally {
    saving.value = false
  }
}
watch(category, () => { notice.value = '' })
onMounted(load)
</script>

<template>
  <main class="page stack">
    <PageHeading title="模型设置" detail="配置聊天、向量嵌入以及 OpenCode 免费模型。">
      <button type="button" @click="load">重新加载</button>
    </PageHeading>
    <div class="tabs" aria-label="模型分类">
      <button v-for="tab in tabs" :key="tab.id" type="button" :aria-pressed="tab.id === category" @click="category = tab.id">{{ tab.label }}</button>
    </div>
    <form class="form-stack" @submit.prevent="save">
      <template v-if="category !== 'free'">
        <div><button type="button" @click="add">{{ category === 'llm' ? '添加聊天模型' : '添加向量嵌入模型' }}</button></div>
        <p v-if="!visible.length" class="empty">此分类暂无模型。</p>
        <section v-for="row in visible" :key="row.key" :id="`model-draft-${row.key}`" class="panel stack">
          <div class="row spread"><h2>{{ row.model || '新模型' }}</h2><button type="button" class="danger" @click="rows = rows.filter(item => item.key !== row.key)">移除</button></div>
          <div class="form-grid">
            <label class="field">模型名称<input v-model.trim="row.model" data-model-name required /></label>
            <label class="field">Base URL<input v-model.trim="row.baseUrl" type="text" required placeholder="https://example.com/v1" /></label>
            <label class="field">API Key<input v-model="row.apiKey" type="password" autocomplete="off" :placeholder="row.hasApiKey ? '密钥已保存' : '输入 API Key'" /></label>
          </div>
          <label class="field inline"><input v-model="row.enabled" type="checkbox" />启用</label>
          <details>
            <summary>高级设置</summary>
            <div class="form-grid">
              <label class="field">提供商标识<input v-model.trim="row.id" required pattern="[A-Za-z0-9_-]+" maxlength="48" /></label>
              <label class="field">接口协议
                <select v-model="row.protocol">
                  <option v-if="category === 'embedding'" value="OPENAI_EMBEDDING">OpenAI 兼容嵌入</option>
                  <template v-else>
                    <option value="OPENAI_COMPATIBLE">OpenAI 兼容</option><option value="ANTHROPIC">Anthropic</option><option value="DASHSCOPE">DashScope 原生</option>
                  </template>
                </select>
              </label>
              <label v-if="category === 'llm'" class="field">优先级<input v-model.number="row.priority" type="number" min="0" /></label>
              <label v-if="row.protocol === 'DASHSCOPE'" class="field">Workspace ID<input v-model.trim="row.workspaceId" /></label>
              <label v-if="row.protocol === 'OPENAI_COMPATIBLE'" class="field">Reasoning effort<input v-model.trim="row.reasoningEffort" /></label>
              <label v-if="row.protocol === 'OPENAI_COMPATIBLE'" class="field inline"><input v-model="row.streamUsage" type="checkbox" />请求流式 Token 用量</label>
            </div>
          </details>
        </section>
      </template>
      <section v-else class="panel stack">
        <h2>OpenCode</h2>
        <div class="row"><span v-for="model in freeModels" :key="model" class="badge">{{ model }}</span></div>
        <label class="field">共用 OpenCode API Key<input v-model="openCodeKey" type="password" autocomplete="off" :disabled="clearOpenCodeKey" :placeholder="hasOpenCodeKey ? '密钥已保存' : '输入 API Key'" /></label>
        <label v-if="hasOpenCodeKey" class="field inline"><input v-model="clearOpenCodeKey" type="checkbox" />清除已保存的 Key</label>
      </section>
      <div class="row"><button type="submit" class="primary" :disabled="!version || saving">保存配置</button><span v-if="notice" class="notice" role="status">{{ notice }}</span></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </form>
  </main>
</template>
