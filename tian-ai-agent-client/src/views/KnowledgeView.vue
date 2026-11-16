<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as api from '../api/workbench'
import { errorText } from '../api/java'
import { getModelProviders, type ModelProvider } from '../api/settings'
import PageHeading from '../components/PageHeading.vue'

const route = useRoute()
const spaces = ref<api.Space[]>([])
const embeddings = ref<ModelProvider[]>([])
const spaceId = ref('')
const files = ref<api.KnowledgeFile[]>([])
const chosen = ref<File[]>([])
const fileInput = ref<HTMLInputElement>()
const creation = reactive({ name: '', medical: false, embeddingProviderId: '' })
const metadata = reactive<api.DocumentMetadata>({ issuer: '', version: '', publishedAt: '', audience: '', documentType: '指南' })
const approved = ref(false)
const accountName = ref('')
const question = ref('')
const result = ref<Awaited<ReturnType<typeof api.queryKnowledgeBase>>>()
const notice = ref('')
const error = ref('')
const space = computed(() => spaces.value.find(item => item.id === spaceId.value))

async function perform(action: () => Promise<void>) {
  notice.value = ''
  error.value = ''
  try { await action() } catch (failure) { error.value = errorText(failure) }
}
async function refreshFiles() {
  const id = spaceId.value
  try {
    const rows = id ? await api.getKnowledgeFiles(id) : []
    if (id === spaceId.value) files.value = rows
  } catch (failure) {
    if (id === spaceId.value) { files.value = []; error.value = errorText(failure) }
  }
}
watch(spaceId, () => {
  chosen.value = []
  approved.value = false
  accountName.value = ''
  result.value = undefined
  if (fileInput.value) fileInput.value.value = ''
  void refreshFiles()
})
function create() {
  return perform(async () => {
    if (!creation.name.trim() || !creation.embeddingProviderId) return
    const item = await api.createKnowledgeSpace({ ...creation })
    spaces.value = await api.getKnowledgeSpaces()
    spaceId.value = item.id
    creation.name = ''
    notice.value = '已创建知识库。'
  })
}
function removeSpace() {
  return perform(async () => {
    if (!space.value || !window.confirm('删除此知识库及其文档？')) return
    await api.deleteKnowledgeSpace(spaceId.value, accountName.value)
    spaces.value = await api.getKnowledgeSpaces()
    spaceId.value = spaces.value[0]?.id ?? ''
    notice.value = '已删除知识库。'
  })
}
function choose(event: Event) {
  chosen.value = Array.from((event.target as HTMLInputElement).files ?? [])
}
function upload() {
  return perform(async () => {
    const selected = space.value
    if (!selected || (selected.medical && !approved.value)) return
    for (const file of chosen.value) {
      await api.uploadKnowledgeFile(selected.id, file, selected.medical ? { ...metadata } : undefined)
    }
    chosen.value = []
    if (fileInput.value) fileInput.value.value = ''
    await refreshFiles()
    notice.value = '文件已提交，索引进度可刷新查看。'
  })
}
function removeFile(file: api.KnowledgeFile) {
  return perform(async () => {
    if (!window.confirm('删除此文档？')) return
    await api.deleteKnowledgeFile(spaceId.value, file.id)
    await refreshFiles()
    notice.value = '已删除文档。'
  })
}
function query() {
  return perform(async () => { result.value = await api.queryKnowledgeBase(spaceId.value, question.value) })
}
async function loadCatalog() {
  error.value = ''
  const [knowledge, models] = await Promise.allSettled([api.getKnowledgeSpaces(), getModelProviders()])
  if (knowledge.status === 'fulfilled') {
    spaces.value = knowledge.value
    const requested = typeof route.query.spaceId === 'string' ? route.query.spaceId : ''
    if (requested && requested !== spaceId.value && spaces.value.some(item => item.id === requested)) spaceId.value = requested
    else if (!spaces.value.some(item => item.id === spaceId.value)) spaceId.value = spaces.value[0]?.id ?? ''
    else await refreshFiles()
  } else {
    error.value = errorText(knowledge.reason)
  }
  if (models.status === 'fulfilled') {
    embeddings.value = models.value.providers.filter(item => item.protocol === 'OPENAI_EMBEDDING' && item.enabled && item.hasApiKey)
  } else if (!error.value) {
    error.value = errorText(models.reason)
  }
}
onMounted(loadCatalog)
</script>

<template>
  <main class="page stack">
    <PageHeading title="知识库导入" detail="创建知识库、选择嵌入模型并管理空间文档。">
      <button type="button" @click="loadCatalog">重新加载</button><label class="field">当前知识库
        <select v-model="spaceId">
          <option value="">请选择知识库</option>
          <option v-for="item in spaces" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
    </PageHeading>
    <div class="columns">
      <div class="stack">
        <details class="panel">
          <summary>创建知识库</summary>
          <form class="form-stack" @submit.prevent="create">
            <label class="field">名称<input v-model.trim="creation.name" required maxlength="80" /></label>
            <label class="field">知识库类型<select v-model="creation.medical"><option :value="false">通用</option><option :value="true">医疗</option></select></label>
            <label class="field">向量嵌入模型
              <select v-model="creation.embeddingProviderId" required>
                <option value="" disabled>请选择模型</option>
                <option v-for="model in embeddings" :key="model.id" :value="model.id">{{ model.model }}</option>
              </select>
            </label>
            <RouterLink v-if="!embeddings.length" to="/model-settings">去配置嵌入模型</RouterLink>
            <div><button type="submit" class="primary" :disabled="!embeddings.length">创建</button></div>
          </form>
        </details>
        <form class="panel form-stack" @submit.prevent="upload">
          <h2>导入文档</h2>
          <label class="field">本地文件 <input ref="fileInput" type="file" multiple :disabled="!space" @change="choose" /></label>
          <p v-if="chosen.length" class="muted">已选择 {{ chosen.length }} 个文件</p>
          <fieldset v-if="space?.medical" class="form-stack">
            <legend>医疗资料来源</legend>
            <div class="form-grid">
              <label class="field">发布机构<input v-model="metadata.issuer" required /></label>
              <label class="field">版本<input v-model="metadata.version" required /></label>
              <label class="field">发布日期<input v-model="metadata.publishedAt" type="date" required /></label>
              <label class="field">适用人群<input v-model="metadata.audience" required /></label>
              <label class="field">文档类型<select v-model="metadata.documentType"><option>指南</option><option>研究</option><option>其他</option></select></label>
            </div>
            <label class="field inline"><input v-model="approved" type="checkbox" required />已确认资料生效并通过审核</label>
          </fieldset>
          <div><button type="submit" class="primary" :disabled="!space || !chosen.length">提交导入</button></div>
        </form>
        <details v-if="space && space.id !== 'medical'" class="panel">
          <summary>删除知识库</summary>
          <form class="form-stack" @submit.prevent="removeSpace">
            <label class="field">管理员账号名<input v-model.trim="accountName" required /></label>
            <div><button type="submit" class="danger">确认删除</button></div>
          </form>
        </details>
      </div>
      <div class="stack">
        <section class="panel stack">
          <div class="row spread"><h2>空间文档 <span class="badge">{{ files.length }}</span></h2><button type="button" @click="refreshFiles">刷新</button></div>
          <p v-if="space" class="muted">向量模型：{{ space.embeddingModel || "服务端默认" }}</p>
          <div v-if="files.length" class="table-wrap">
            <table>
              <thead><tr><th>文档</th><th>大小</th><th>索引</th><th>操作</th></tr></thead>
              <tbody><tr v-for="file in files" :key="file.id"><td>{{ file.name }}</td><td>{{ Math.ceil(file.size / 1024) }} KB</td><td>{{ file.status === 'INDEXED' ? '已完成' : file.status }}</td><td><button type="button" class="danger" @click="removeFile(file)">删除</button></td></tr></tbody>
            </table>
          </div>
          <p v-else class="empty">当前空间没有文档。</p>
        </section>
        <form class="panel form-stack" @submit.prevent="query">
          <h2>检索预览</h2>
          <label class="field">问题<input id="knowledge-question" v-model.trim="question" required /></label>
          <div><button type="submit" :disabled="!space">检索</button></div>
          <div v-if="result" class="stack" aria-live="polite">
            <p>{{ result.answer }}</p><p v-if="result.evidenceReason" class="muted">{{ result.evidenceReason }}</p>
            <blockquote v-for="source in result.sources" :key="source.id">{{ source.source }} · {{ source.excerpt }}</blockquote>
          </div>
        </form>
      </div>
    </div>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </main>
</template>
