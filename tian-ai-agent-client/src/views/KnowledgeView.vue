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
const fileInput = ref<HTMLInputElement>()
const createDialog = ref<HTMLDialogElement>()
const medicalDialog = ref<HTMLDialogElement>()
const medicalFileInput = ref<HTMLInputElement>()
const creation = reactive({ name: '', medical: false, embeddingProviderId: '' })
const metadata = reactive<api.DocumentMetadata>({ issuer: '', version: '', publishedAt: '', audience: '', documentType: '指南' })
const approved = ref(false)
const accountName = ref('')
const creating = ref(false)
const uploading = ref(false)
const loading = ref(false)
const notice = ref('')
const error = ref('')
const creationError = ref('')
const uploadError = ref('')
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
  files.value = []
  approved.value = false
  accountName.value = ''
  if (fileInput.value) fileInput.value.value = ''
  void refreshFiles()
})
function openCreation() {
  Object.assign(creation, { name: '', medical: false, embeddingProviderId: '' })
  creationError.value = ''
  createDialog.value?.showModal()
}
async function create() {
  if (creating.value || !creation.name.trim() || !creation.embeddingProviderId) return
  creating.value = true
  creationError.value = ''
  try {
    const item = await api.createKnowledgeSpace({ ...creation })
    spaces.value.push(item)
    spaceId.value = item.id
    createDialog.value?.close()
    error.value = ''
    notice.value = '已创建知识库。'
  } catch (failure) {
    creationError.value = errorText(failure)
  } finally {
    creating.value = false
  }
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
function startImport() {
  if (!space.value || uploading.value) return
  notice.value = ''
  error.value = ''
  uploadError.value = ''
  if (space.value.medical) medicalDialog.value?.showModal()
  else fileInput.value?.click()
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const selectedFiles = Array.from(input.files ?? [])
  input.value = ''
  const target = space.value
  if (!selectedFiles.length || !target || uploading.value || (target.medical && !approved.value)) return
  const documentMetadata = target.medical ? { ...metadata } : undefined
  uploading.value = true
  uploadError.value = ''
  let submitted = 0
  const failures: string[] = []
  try {
    for (const file of selectedFiles) {
      try {
        await api.uploadKnowledgeFile(target.id, file, documentMetadata)
        submitted++
      } catch (failure) {
        failures.push(file.name + ' · ' + errorText(failure))
      }
    }
    if (submitted) {
      notice.value = '已提交 ' + submitted + ' 个文件，可刷新查看索引进度。'
      await refreshFiles()
    }
    if (failures.length) {
      uploadError.value = failures.join('\n')
    } else {
      medicalDialog.value?.close()
      approved.value = false
    }
  } finally {
    uploading.value = false
  }
}
function removeFile(file: api.KnowledgeFile) {
  return perform(async () => {
    if (!window.confirm('删除此文档？')) return
    await api.deleteKnowledgeFile(spaceId.value, file.id)
    await refreshFiles()
    notice.value = '已删除文档。'
  })
}
async function loadCatalog() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
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
  } finally {
    loading.value = false
  }
}
onMounted(loadCatalog)
</script>

<template>
  <main class="page stack">
    <PageHeading title="知识库" detail="管理知识库、嵌入模型和文档。">
      <div class="row">
        <button type="button" :disabled="loading || uploading" @click="loadCatalog">重新加载</button>
        <button type="button" class="primary" :disabled="uploading" @click="openCreation">创建知识库</button>
      </div>
    </PageHeading>

    <input ref="fileInput" type="file" multiple hidden @change="upload" />
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="uploadError && !space?.medical" class="error import-feedback" role="alert">{{ uploadError }}</p>
    <section class="panel stack">
      <div class="row spread">
        <label class="field">当前知识库
          <select v-model="spaceId" :disabled="uploading">
            <option value="">请选择知识库</option>
            <option v-for="item in spaces" :key="item.id" :value="item.id" :disabled="item.deleting">{{ item.name }}</option>
          </select>
        </label>
        <div v-if="space" class="row">
          <RouterLink v-if="!space.deleting" class="button" :to="{ path: '/knowledge-chat', query: { spaceId } }">知识库问答</RouterLink>
          <button type="button" class="primary" :disabled="uploading || space.deleting" @click="startImport">{{ uploading ? '正在导入…' : '导入文档' }}</button>
        </div>
      </div>
      <template v-if="space">
        <div class="row spread"><h2>文档 <span class="badge">{{ files.length }}</span></h2><button type="button" @click="refreshFiles">刷新</button></div>
        <p class="muted">向量模型：{{ space.embeddingModel || '服务端默认' }}</p>
        <div v-if="files.length" class="table-wrap">
          <table>
            <thead><tr><th>文档</th><th>大小</th><th>索引</th><th>操作</th></tr></thead>
            <tbody><tr v-for="file in files" :key="file.id"><td>{{ file.name }}</td><td>{{ Math.ceil(file.size / 1024) }} KB</td><td>{{ file.status === 'INDEXED' ? '已完成' : file.status }}</td><td><button type="button" class="danger" :disabled="uploading" @click="removeFile(file)">删除</button></td></tr></tbody>
          </table>
        </div>
        <p v-else class="empty">当前知识库没有文档。</p>
      </template>
    </section>
    <details v-if="space && space.id !== 'medical'" class="panel">
      <summary>删除知识库</summary>
      <form class="form-stack" @submit.prevent="removeSpace">
        <label class="field">管理员账号名<input v-model.trim="accountName" required /></label>
        <div><button type="submit" class="danger" :disabled="uploading">确认删除</button></div>
      </form>
    </details>

    <dialog ref="createDialog" class="panel knowledge-dialog" aria-labelledby="create-space-title"
      @cancel="creating && $event.preventDefault()">
      <form class="form-stack" @submit.prevent="create">
        <h2 id="create-space-title">创建知识库</h2>
        <label class="field">名称<input v-model.trim="creation.name" required maxlength="80" autofocus /></label>
        <label class="field">知识库类型<select v-model="creation.medical"><option :value="false">通用</option><option :value="true">医疗</option></select></label>
        <label class="field">向量嵌入模型
          <select v-model="creation.embeddingProviderId" required>
            <option value="" disabled>请选择模型</option>
            <option v-for="model in embeddings" :key="model.id" :value="model.id">{{ model.model }}</option>
          </select>
        </label>
        <RouterLink v-if="!loading && !embeddings.length" to="/model-settings">去配置嵌入模型</RouterLink>
        <p v-if="creationError" class="error" role="alert">{{ creationError }}</p>
        <div class="row dialog-actions">
          <button type="button" :disabled="creating" @click="createDialog?.close()">取消</button>
          <button type="submit" class="primary" :disabled="creating || !embeddings.length">{{ creating ? '创建中…' : '创建' }}</button>
        </div>
      </form>
    </dialog>
    <dialog ref="medicalDialog" class="panel knowledge-dialog" aria-labelledby="import-medical-title"
      @cancel="uploading && $event.preventDefault()">
      <form class="form-stack" @submit.prevent="medicalFileInput?.click()">
        <input ref="medicalFileInput" type="file" multiple hidden @change="upload" />
        <h2 id="import-medical-title">导入医疗资料</h2>
        <div class="form-grid">
          <label class="field">发布机构<input v-model="metadata.issuer" required /></label>
          <label class="field">版本<input v-model="metadata.version" required /></label>
          <label class="field">发布日期<input v-model="metadata.publishedAt" type="date" required /></label>
          <label class="field">适用人群<input v-model="metadata.audience" required /></label>
          <label class="field">文档类型<select v-model="metadata.documentType"><option>指南</option><option>研究</option><option>其他</option></select></label>
        </div>
        <label class="field inline"><input v-model="approved" type="checkbox" required />已确认资料生效并通过审核</label>
        <p v-if="uploadError" class="error import-feedback" role="alert">{{ uploadError }}</p>
        <div class="row dialog-actions">
          <button type="button" :disabled="uploading" @click="medicalDialog?.close()">关闭</button>
          <button type="submit" class="primary" :disabled="uploading">{{ uploading ? '正在导入…' : '选择文件并导入' }}</button>
        </div>
      </form>
    </dialog>
  </main>
</template>

<style scoped>
.knowledge-dialog { width: min(30rem, calc(100% - 2rem)); max-height: calc(100% - 2rem); color: var(--ink); }
.knowledge-dialog::backdrop { background: #172a355e; }
.dialog-actions { justify-content: flex-end; }
.import-feedback { white-space: pre-line; overflow-wrap: anywhere; }
</style>
