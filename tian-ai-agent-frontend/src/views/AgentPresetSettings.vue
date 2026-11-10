<template>
  <section class="ui-page">
    <header class="ui-page-heading">
      <div>
        <h1>Skills 与 Prompt 模板</h1>
        <p>管理当前账号的配置。Skills 可在 Agent 问答中按需勾选，Prompt 模板可插入输入框后编辑。</p>
      </div>
      <button type="button" class="ui-button primary" :disabled="busy || !loaded" @click="add">
        新增{{ collection === 'skills' ? ' Skill' : '模板' }}
      </button>
    </header>

    <nav class="actions tabs" aria-label="配置类型">
      <button v-for="tab in tabs" :key="tab.id" type="button" class="ui-button"
        :aria-pressed="collection === tab.id" :class="{ primary: collection === tab.id }"
        :disabled="busy" @click="open(tab.id)">{{ tab.label }}</button>
    </nav>
    <p v-if="error" class="ui-notice error" role="alert">
      {{ error }}
      <button v-if="!loaded" type="button" class="ui-button" @click="open(collection)">重新加载</button>
    </p>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="loaded && !rows.length" class="ui-panel empty">当前账号还没有此类配置。</p>

    <form v-for="row in rows" :key="row.key" class="ui-panel preset" @submit.prevent="save(row)">
      <div class="row-header">
        <h2>{{ row.name || (collection === 'skills' ? '新 Skill' : '新 Prompt 模板') }}</h2>
        <span v-if="row.id" class="ui-pill" :data-tone="row.enabled ? 'positive' : 'neutral'">{{ row.enabled ? '已启用' : '已停用' }}</span>
        <button type="button" class="ui-button" :aria-expanded="row.expanded" :aria-controls="'preset-fields-' + row.key"
          @click="row.expanded = !row.expanded">{{ row.expanded ? '折叠' : '展开' }}</button>
      </div>
      <fieldset v-show="row.expanded" :id="'preset-fields-' + row.key" :disabled="busy || !row.expanded">
        <label>名称
          <input v-model.trim="row.name" class="ui-field" maxlength="100" required />
        </label>
        <label>简介
          <input v-model.trim="row.description" class="ui-field" maxlength="500" />
        </label>
        <label>{{ collection === 'skills' ? 'Skill 指令（支持 Markdown）' : '模板正文' }}
          <textarea v-model="row.content" class="ui-field" rows="6" maxlength="8000" required
            :placeholder="collection === 'skills' ? '描述适用范围和执行步骤' : '输入可重复使用的提示词'" />
        </label>
        <div class="actions">
          <label class="check"><input v-model="row.enabled" type="checkbox" /> 启用</label>
          <button type="submit" class="ui-button primary" :disabled="!row.name || !row.content.trim()">保存</button>
          <button type="button" class="ui-button" @click="remove(row)">{{ row.id ? '删除配置' : '取消' }}</button>
        </div>
      </fieldset>
    </form>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { deleteAgentPreset, getAgentPresets, saveAgentPreset } from '../api'
import { apiError } from '../utils/medical'

const tabs = [
  { id: 'skills', label: 'Skills' },
  { id: 'prompt-templates', label: 'Prompt 模板' }
]
const collection = ref(tabs[0].id)
const rows = ref([])
const busy = ref(false)
const loaded = ref(false)
const error = ref('')
const notice = ref('')
let nextKey = 0

function add() {
  rows.value.unshift({ key: ++nextKey, expanded: true, name: '', description: '', content: '', enabled: true })
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

function open(id) {
  if (loaded.value && collection.value === id) return
  collection.value = id
  rows.value = []
  loaded.value = false
  return request(async () => {
    const { data } = await getAgentPresets(id)
    rows.value = data.map(item => ({ ...item, key: ++nextKey, expanded: false }))
    loaded.value = true
  })
}

function save(row) {
  return request(async () => {
    const { name, description, content, enabled } = row
    const { data } = await saveAgentPreset(collection.value, row.id, { name, description, content, enabled })
    Object.assign(row, data)
    notice.value = '配置已保存。'
  })
}

function remove(row) {
  return request(async () => {
    if (row.id) await deleteAgentPreset(collection.value, row.id)
    rows.value = rows.value.filter(item => item.key !== row.key)
    if (row.id) notice.value = '配置已删除。'
  })
}

onMounted(() => open(collection.value))
</script>

<style scoped>
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.tabs { margin-bottom: 16px; }
.preset, .empty { margin-bottom: 14px; padding: 16px; }
.row-header { display: flex; align-items: center; gap: 8px; }
.row-header h2 { flex: 1; min-width: 0; margin: 0; font-size: 16px; overflow-wrap: anywhere; }
.row-header button { flex: none; }
fieldset { display: grid; gap: 12px; border: 0; margin: 12px 0 0; padding: 0; min-width: 0; }
fieldset > label { display: grid; gap: 6px; }
textarea { resize: vertical; }
.check { display: flex; gap: 6px; margin-right: auto; }
</style>
