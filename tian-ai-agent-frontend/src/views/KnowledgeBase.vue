<template>
  <section class="ui-page import-page">
    <header class="ui-page-heading">
      <div><span class="ui-kicker">Documents</span><h1>知识库导入</h1><p>将资料导入选定空间，跟踪解析与索引。</p></div>
      <div class="space-controls">
        <label for="import-space">目标知识库</label>
        <select id="import-space" v-model="selected" class="ui-field" :disabled="busy">
          <option value="">请选择知识库</option>
          <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}{{ space.deleting ? ' · 删除待完成' : '' }}</option>
        </select>
        <RouterLink v-if="canUpload" class="ui-button" :to="{ path: '/wiki', query: { spaceId: selected } }">
          <WorkspaceIcon name="wiki" /> 查看图谱
        </RouterLink>
        <button v-if="selectedSpace && selected !== 'medical'" class="ui-button danger" type="button"
          :disabled="busy" @click="openDeleteSpace">{{ selectedSpace.deleting ? '重试删除' : '删除知识库' }}</button>
      </div>
    </header>

    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="ui-notice error" role="alert">{{ error }}</p>

    <dialog ref="deleteDialog" class="delete-dialog" aria-labelledby="delete-space-title"
      @cancel.prevent="closeDeleteSpace" @close="clearDeleteConfirmation">
      <form @submit.prevent="confirmDeleteSpace">
        <h2 id="delete-space-title">删除知识库</h2>
        <p>将永久删除「{{ deletionTarget?.name }}」中的文档、索引和 Wiki。请输入当前管理员账号名 <strong>admin</strong> 确认。</p>
        <label for="delete-account">管理员账号名</label>
        <input id="delete-account" v-model="accountName" class="ui-field" maxlength="32"
          autocomplete="off" :disabled="busy" required autofocus />
        <p v-if="deleteError" class="ui-notice error" role="alert">{{ deleteError }}</p>
        <div class="delete-actions">
          <button class="ui-button" type="button" :disabled="busy" @click="closeDeleteSpace">取消</button>
          <button class="ui-button danger" type="submit" :disabled="busy || !accountName.trim()">
            {{ busy ? '正在删除…' : '永久删除' }}
          </button>
        </div>
      </form>
    </dialog>

    <div class="import-grid">
      <div class="import-actions">
        <form class="ui-panel upload" @submit.prevent="upload">
          <h2>导入文档</h2>
          <label class="drop-zone" :class="{ dragging, disabled: busy || !canUpload }"
            @dragover.prevent="dragging = canUpload && !busy"
            @dragleave.prevent="dragging = false" @drop.prevent="dropFile">
            <input ref="input" type="file" multiple accept=".pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.pptx" :disabled="busy || !canUpload"
              aria-label="选择导入文档" @change="selectFiles($event.target.files)" />
            <span class="upload-mark"><WorkspaceIcon name="upload" /></span>
            <strong>{{ chosenFiles.length ? '已选择 ' + chosenFiles.length + ' 个文件' : '拖放多个文件，或点击选择' }}</strong>
            <small>{{ canUpload ? 'PDF、Word、Markdown、TXT 等' : selectedSpace?.deleting ? '该知识库正在删除' : '请先选择目标知识库' }}</small>
          </label>
          <ul v-if="chosenFiles.length" class="selected-files">
            <li v-for="(file, index) in chosenFiles" :key="index">
              <span>{{ file.name }}</span>
              <button class="delete-button" type="button" :disabled="busy" @click="chosenFiles.splice(index, 1)">移除</button>
            </li>
          </ul>
          <ul v-if="uploadFailures.length" class="upload-failures">
            <li v-for="(failure, index) in uploadFailures" :key="index">{{ failure.name }} · {{ failure.message }}</li>
          </ul>
          <fieldset v-if="isMedical">
            <legend>医疗文档元数据（适用于本批所有文件）</legend>
            <label>发布机构<input v-model.trim="metadata.issuer" class="ui-field" required /></label>
            <label>版本<input v-model.trim="metadata.version" class="ui-field" required /></label>
            <label>发布日期<input v-model="metadata.publishedAt" class="ui-field" type="date" required /></label>
            <label>适用人群<input v-model.trim="metadata.audience" class="ui-field" required /></label>
            <label>文档类型<select v-model="metadata.documentType" class="ui-field"><option v-for="type in documentTypes" :key="type">{{ type }}</option></select></label>
            <label class="approved"><input v-model="approved" type="checkbox" required /> 已确认资料生效并通过审核</label>
          </fieldset>
          <button class="ui-button primary" :disabled="busy || !canUpload || !chosenFiles.length">
            <WorkspaceIcon name="upload" /> {{ uploading ? '上传中 ' + progress + '%' : '导入' }}
          </button>
          <p class="hint">索引完成后，可在 Wiki 图谱页面编译实体、概念与综合页。</p>
        </form>

        <details class="ui-panel create">
          <summary><WorkspaceIcon name="plus" /> 创建知识库</summary>
          <form @submit.prevent="create">
            <input v-model.trim="name" class="ui-field" aria-label="知识库名称" placeholder="知识库名称" maxlength="100" required />
            <select v-model="medicalSpace" class="ui-field" aria-label="知识库类型">
              <option :value="false">通用</option><option :value="true">医疗</option>
            </select>
            <button class="ui-button" :disabled="busy">创建</button>
          </form>
        </details>
      </div>

      <section class="ui-panel library">
        <div class="library-header">
          <div><h2>空间文档</h2><span>{{ files.length }} 个文件</span></div>
          <button class="ui-button" type="button" :disabled="!canUpload || busy" @click="loadFiles">
            <WorkspaceIcon name="refresh" /> 刷新
          </button>
        </div>
        <input v-if="files.length" v-model.trim="query" class="ui-field search" aria-label="搜索文档" placeholder="按文件名搜索" />
        <p v-if="!visibleFiles.length" class="empty">
          {{ selectedSpace?.deleting ? '删除尚未完成，可以重试删除。' : !selected ? '请选择一个知识库' : files.length ? '没有匹配的文档' : '暂无文档' }}
        </p>
        <ul v-else class="documents">
          <li v-for="file in visibleFiles" :key="file.id">
            <span class="file-icon"><WorkspaceIcon name="documents" /></span>
            <div class="file-name"><strong>{{ file.name }}</strong><small v-if="file.error">{{ file.error }}</small></div>
            <span class="ui-pill" :data-tone="statusTone(file.status)">{{ statusName(file.status) }}</span>
            <button type="button" class="delete-button" :disabled="busy" :aria-label="'删除' + file.name" @click="remove(file)">删除</button>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { createKnowledgeSpace, deleteKnowledgeFile, deleteKnowledgeSpace, getKnowledgeFiles, getKnowledgeSpaces, uploadKnowledgeFile } from '../api'
import WorkspaceIcon from '../components/WorkspaceIcon.vue'
import { apiError } from '../utils/medical'

const props = defineProps({ spaceId: String })
const emit = defineEmits(['space-created', 'space-deleted'])
const spaces = ref([])
const selected = ref(props.spaceId || '')
const selectedSpace = computed(() => spaces.value.find(space => space.id === selected.value))
const canUpload = computed(() => Boolean(selectedSpace.value && !selectedSpace.value.deleting))
const deleteDialog = ref(null)
const deletionTarget = ref(null)
const accountName = ref('')
const deleteError = ref('')
const files = ref([])
const busy = ref(false)
const name = ref('')
const medicalSpace = ref(false)
const approved = ref(false)
const error = ref('')
const notice = ref('')
const input = ref(null)
const chosenFiles = ref([])
const uploadFailures = ref([])
const dragging = ref(false)
const uploading = ref(false)
const progress = ref(0)
const query = ref('')
const visibleFiles = computed(() => files.value.filter(file => file.name.toLowerCase().includes(query.value.toLowerCase())))
const documentTypes = ['GUIDELINE', 'DRUG_LABEL', 'SAFETY_NOTICE', 'SYSTEMATIC_REVIEW', 'CLINICAL_STUDY', 'MEDICAL_EDUCATION']
const metadata = reactive({ issuer: '', version: '', publishedAt: '', audience: '', documentType: 'GUIDELINE' })
const isMedical = computed(() => spaces.value.some(space => space.id === selected.value && space.medical))
const pending = new Set(['QUEUED', 'PENDING', 'PROCESSING', 'PARSING', 'PARSING_INDEXING', 'INDEXING', 'RUNNING'])
const statusName = status => ({ QUEUED: '排队中', PENDING: '排队中', PROCESSING: '处理中', PARSING: '解析中',
  PARSING_INDEXING: '解析与索引中', INDEXING: '索引中', INDEXED: '已索引', INDEXED_DEGRADED: '已索引（降级）', FAILED: '失败', CANCELLED: '已取消' })[status] || status
const statusTone = status => pending.has(status) ? 'active'
  : ['INDEXED', 'INDEXED_DEGRADED'].includes(status) ? 'positive'
    : ['FAILED', 'CANCELLED'].includes(status) ? 'negative' : 'neutral'
let timer
let loadVersion = 0

async function loadFiles() {
  const scope = selected.value
  const version = ++loadVersion
  if (!scope || selectedSpace.value?.deleting) return
  try {
    const { data } = await getKnowledgeFiles(scope)
    if (version === loadVersion) files.value = data
  } catch (failure) {
    if (version === loadVersion) error.value = apiError(failure)
  }
}

async function create() {
  if (busy.value || !name.value) return
  busy.value = true
  error.value = ''
  try {
    const { data } = await createKnowledgeSpace(name.value, medicalSpace.value)
    spaces.value.push(data)
    emit('space-created', data)
    selected.value = data.id
    name.value = ''
  } catch (failure) {
    error.value = apiError(failure)
  } finally {
    busy.value = false
  }
}

function selectFiles(list) {
  chosenFiles.value = Array.from(list || [])
  uploadFailures.value = []
  if (input.value) input.value.value = ''
}

function dropFile(event) {
  dragging.value = false
  if (busy.value || !canUpload.value) return
  selectFiles(event.dataTransfer?.files)
}

async function upload() {
  const batch = [...chosenFiles.value]
  const spaceId = selected.value
  if (busy.value || !batch.length || !canUpload.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  uploading.value = true
  progress.value = 0
  uploadFailures.value = []
  const fields = isMedical.value ? { ...metadata, effectiveStatus: 'ACTIVE', reviewStatus: 'APPROVED' } : {}
  const percentages = batch.map(() => 0)
  const failed = new Map()
  let next = 0
  const updateProgress = (index, percent) => {
    percentages[index] = percent
    progress.value = Math.round(percentages.reduce((sum, value) => sum + value, 0) / batch.length)
  }
  const worker = async () => {
    while (next < batch.length) {
      const index = next++
      const file = batch[index]
      try {
        await uploadKnowledgeFile(file, { ...fields, spaceId }, percent => updateProgress(index, percent))
      } catch (failure) {
        failed.set(index, { name: file.name, message: apiError(failure) })
      } finally {
        updateProgress(index, 100)
      }
    }
  }
  try {
    await Promise.all(Array.from({ length: Math.min(3, batch.length) }, worker))
    chosenFiles.value = batch.filter((_, index) => failed.has(index))
    uploadFailures.value = [...failed.values()]
    const submitted = batch.length - failed.size
    if (submitted) notice.value = submitted + ' 个文件已提交，正在后台解析与索引。'
    if (failed.size) error.value = failed.size + ' 个文件提交失败，已保留在选择列表中。'
    await loadFiles()
  } finally {
    uploading.value = false
    busy.value = false
  }
}

function openDeleteSpace() {
  if (busy.value || !selectedSpace.value || selected.value === 'medical') return
  deletionTarget.value = selectedSpace.value
  accountName.value = ''
  deleteError.value = ''
  deleteDialog.value.showModal()
}

function closeDeleteSpace() {
  if (!busy.value) deleteDialog.value?.close()
}

function clearDeleteConfirmation() {
  accountName.value = ''
  deleteError.value = ''
  deletionTarget.value = null
}

async function confirmDeleteSpace() {
  const target = deletionTarget.value
  if (busy.value || !target || !accountName.value.trim()) return
  busy.value = true
  deleteError.value = ''
  try {
    await deleteKnowledgeSpace(target.id, accountName.value.trim())
    spaces.value = spaces.value.filter(space => space.id !== target.id)
    emit('space-deleted', target.id)
    if (selected.value === target.id) selected.value = ''
    await nextTick()
    notice.value = '已删除知识库「' + target.name + '」。'
    deleteDialog.value?.close()
  } catch (failure) {
    deleteError.value = failure.response?.data?.message || apiError(failure)
    if (failure.response?.status === 503) {
      try {
        const { data } = await getKnowledgeSpaces()
        spaces.value = data
        if (selectedSpace.value?.deleting) {
          loadVersion++
          files.value = []
        }
      } catch {
        // 保留原错误，允许在弹窗重试。
      }
    }
  } finally {
    busy.value = false
  }
}

async function remove(file) {
  if (busy.value || !window.confirm('删除文档「' + file.name + '」？')) return
  busy.value = true
  error.value = ''
  try {
    await deleteKnowledgeFile(file.id, selected.value)
    await loadFiles()
  } catch (failure) {
    error.value = apiError(failure)
  } finally {
    busy.value = false
  }
}

watch(selected, () => {
  files.value = []
  error.value = ''
  notice.value = ''
  approved.value = false
  query.value = ''
  chosenFiles.value = []
  uploadFailures.value = []
  if (input.value) input.value.value = ''
  loadFiles()
}, { immediate: true })

onMounted(async () => {
  timer = setInterval(() => { if (files.value.some(file => pending.has(file.status))) loadFiles() }, 2500)
  try {
    const { data } = await getKnowledgeSpaces()
    spaces.value = data
  } catch (failure) {
    error.value = apiError(failure)
  }
})
onBeforeUnmount(() => { loadVersion++; clearInterval(timer) })
</script>

<style scoped>
.space-controls,.library-header,.library-header > div,.create summary { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.space-controls { color:var(--muted); font-size:13px; }
.space-controls select { max-width:min(220px, 72vw); }
.space-controls svg,.library-header svg,.create svg { width:16px; height:16px; }
.import-grid { display:grid; grid-template-columns:minmax(260px, 360px) minmax(0, 1fr); gap:15px; align-items:start; }
.import-actions { display:grid; min-width:0; gap:12px; }
.ui-notice { margin-bottom:12px; }
h2 { font-size:15px; font-weight:750; }
.upload,.create,.library { padding:15px; }
.upload { display:grid; gap:12px; }
.upload .primary { justify-self:start; }
.drop-zone {
  position:relative;
  display:grid;
  justify-items:center;
  gap:7px;
  padding:23px 10px;
  border:1px dashed #aab6d6;
  border-radius:12px;
  color:var(--ink);
  background:#f8f9fe;
  text-align:center;
  overflow-wrap:anywhere;
  cursor:pointer;
}
.drop-zone input { position:absolute; inset:0; width:100%; opacity:0; cursor:inherit; }
.drop-zone:focus-within { outline:2px solid var(--accent); outline-offset:2px; }
.drop-zone:not(.disabled):hover,.drop-zone.dragging { border-color:var(--accent); background:var(--accent-soft); }
.drop-zone.disabled { cursor:not-allowed; opacity:.6; }
.upload-mark,.file-icon { display:grid; place-items:center; border-radius:8px; color:var(--accent-ink); background:var(--accent-soft); }
.upload-mark { width:36px; height:36px; }
.selected-files,.upload-failures { list-style:none; display:grid; gap:4px; padding:0; margin:0; font-size:12px; overflow-wrap:anywhere; }
.selected-files li { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.upload-failures { color:var(--danger); }
.drop-zone small,.hint,.library-header span { color:var(--muted); font-size:12px; }
fieldset { display:grid; grid-template-columns:repeat(2, minmax(0,1fr)); gap:8px; padding:8px; margin:0; border:1px solid var(--line); border-radius:8px; font-size:12px; }
fieldset label { display:grid; gap:3px; }
fieldset .approved { grid-column:1 / -1; display:flex; align-items:center; gap:6px; }
.create summary { color:var(--accent-ink); font-weight:650; }
.create form { display:flex; flex-wrap:wrap; gap:6px; margin-top:10px; }
.create form input { flex:1 1 130px; }
.library-header { justify-content:space-between; }
.search { width:100%; margin-top:12px; }
.empty { padding:45px 10px; color:var(--muted); text-align:center; }
.documents { list-style:none; margin-top:10px; }
.documents li { display:grid; grid-template-columns:32px minmax(0,1fr) auto auto; align-items:center; gap:10px; min-height:52px; border-top:1px solid var(--line); }
.file-icon { width:30px; height:30px; }
.file-icon svg { width:16px; height:16px; }
.file-name { min-width:0; overflow-wrap:anywhere; }
.file-name strong { font-size:13px; }
.file-name small { display:block; color:var(--danger); font-size:11px; }
.delete-button { padding:5px; border:0; border-radius:6px; background:transparent; color:var(--danger); font-size:12px; }
.delete-button:hover:not(:disabled) { background:var(--danger-soft); }
.delete-dialog {
  width:min(440px, calc(100vw - 32px));
  max-height:calc(100vh - 32px);
  padding:20px;
  border:1px solid var(--line);
  border-radius:12px;
  color:var(--ink);
  background:var(--surface);
}
.delete-dialog::backdrop { background:#16203870; }
.delete-dialog form { display:grid; gap:10px; font-size:13px; }
.delete-dialog form > p { color:var(--muted); overflow-wrap:anywhere; }
.delete-dialog form > .error { color:var(--danger); margin-bottom:0; }
.delete-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:4px; }
.danger { color:var(--danger); }
.delete-actions .danger { background:var(--danger); border-color:var(--danger); color:white; }
.delete-actions .danger:hover:not(:disabled) { background:#a6212b; border-color:#a6212b; }
@media (max-width:760px) { .import-grid { grid-template-columns:1fr; } }
</style>
