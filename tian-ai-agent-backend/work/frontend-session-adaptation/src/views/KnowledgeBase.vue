<template>
  <div class="kb-container" @click="handleCitationClick">
    <!-- 顶部导航 -->
    <header class="kb-header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="kb-title">⚕ 医疗循证知识库</h1>
      <div class="header-right">
        <span v-if="fileList.length" class="header-stats">
          {{ fileList.length }} 个文件 <template v-if="indexedCount">· {{ indexedCount }} 已索引</template>
        </span>
      </div>
    </header>

    <aside class="policy-notice">
      <strong>证据准入提示</strong>
      <span>仅建议上传可核验的指南、共识、药品说明书与健康教育资料。上传信息不等于临床认可，使用前仍需核对适用人群、时效性和审核状态。</span>
    </aside>
    <div v-if="emergency" class="kb-emergency" role="alert"><strong>紧急提示</strong><span>{{ emergency }}</span></div>

    <section class="document-manager top-document-manager">
      <div class="document-manager-head">
        <div>
          <div class="document-manager-title">
            <span class="document-manager-icon">▤</span>
            <div><h2>知识库文档</h2><p>查看已上传文档、索引状态和证据元数据</p></div>
            <span class="document-total">{{ fileList.length }}</span>
          </div>
        </div>
        <div class="document-actions">
          <div class="document-search"><span>⌕</span><input v-model.trim="documentQuery" placeholder="搜索文档名称或发布机构"/></div>
          <button class="refresh-files-btn" :disabled="isLoadingFiles" @click="loadFileList">
            <span :class="{ spinning: isLoadingFiles }">↻</span>{{ isLoadingFiles ? '刷新中' : '刷新' }}
          </button>
          <button v-if="fileList.length" class="select-all-btn" :disabled="isBatchDeleting" @click="toggleSelectAll">{{ allFilesSelected ? '取消全选' : '全选' }}</button>
          <button v-if="fileList.length" class="batch-delete-btn" :disabled="!selectedCount || isBatchDeleting || deletingId !== null" @click="handleBatchDelete">
            {{ isBatchDeleting ? `删除中 ${batchDeleteCompleted}/${batchDeleteTotal}` : `删除所选（${selectedCount}）` }}
          </button>
        </div>
      </div>

      <div v-if="isLoadingFiles && !fileList.length" class="document-loading">
        <i v-for="i in 3" :key="i"></i>
      </div>
      <div v-else-if="filteredFiles.length" class="document-table-wrap">
        <div class="document-table document-table-header">
          <span></span><span>文档</span><span>证据来源</span><span>上传时间</span><span>索引状态</span><span>操作</span>
        </div>
        <div v-for="file in filteredFiles" :key="file.id" class="document-table document-row" :class="{ selected: selectedIds.includes(file.id) }">
          <label class="document-check" :title="`选择 ${file.name}`"><input type="checkbox" :checked="selectedIds.includes(file.id)" :disabled="isBatchDeleting || deletingId === file.id" @change="toggleFileSelection(file.id)"/><span></span></label>
          <div class="document-name-cell"><span class="document-type-icon">{{ fileIcon(file.name) }}</span><div><strong :title="file.name">{{ file.name }}</strong><small>{{ formatFileSize(file.size) }}<template v-if="file.chunkCount"> · {{ file.chunkCount }} 个片段</template></small></div></div>
          <div class="document-evidence"><strong>{{ file.medicalMetadata?.issuer || '发布机构未标注' }}</strong><small>{{ file.medicalMetadata?.version || '版本未标注' }} · {{ file.medicalMetadata?.audience || '人群未标注' }}</small></div>
          <time>{{ formatTime(file.uploadTime) || '—' }}</time>
          <div><span class="file-status table-status" :class="statusClass(file.status)"><i></i>{{ statusText(file.status) }}</span><small v-if="file.error" class="document-error" :title="file.error">{{ file.error }}</small></div>
          <button class="document-delete" :disabled="deletingId === file.id || isBatchDeleting" @click="handleDelete(file)">{{ deletingId === file.id ? '删除中…' : '删除' }}</button>
        </div>
      </div>
      <div v-else class="document-empty">
        <div>{{ documentQuery ? '⌕' : '▤' }}</div>
        <strong>{{ documentQuery ? '没有匹配的文档' : '知识库中还没有文档' }}</strong>
        <span>{{ documentQuery ? '请尝试其他关键词' : '在下方填写证据元数据并上传医疗资料' }}</span>
        <button v-if="documentQuery" @click="documentQuery = ''">清除搜索</button>
      </div>
    </section>

    <!-- 双栏工作区 -->
    <div class="kb-workspace">
      <!-- 左栏：文件管理 -->
      <section class="panel panel-left">
        <section class="sidebar-documents">
          <div class="sidebar-documents-head">
            <div><strong>知识库文档</strong><span>{{ fileList.length }} 个文档 · {{ indexedCount }} 个已索引</span></div>
            <button :disabled="isLoadingFiles" title="刷新文档列表" @click="loadFileList"><span :class="{ spinning: isLoadingFiles }">↻</span></button>
          </div>
          <div class="sidebar-document-tools">
            <label><span>⌕</span><input v-model.trim="documentQuery" placeholder="搜索文档名称或发布机构"/></label>
            <label v-if="fileList.length" class="sidebar-select-all" title="选择或取消全部文档">
              <input type="checkbox" :checked="allFilesSelected" :disabled="isBatchDeleting" @change="toggleSelectAll"/>
              <span>全选</span>
            </label>
            <button v-if="fileList.length" class="sidebar-batch-delete" :disabled="!selectedCount || isBatchDeleting || deletingId !== null" @click="handleBatchDelete">{{ isBatchDeleting ? batchDeleteCompleted + '/' + batchDeleteTotal : '删除所选 ' + selectedCount }}</button>
          </div>
          <div v-if="isLoadingFiles && !fileList.length" class="sidebar-document-loading"><i v-for="i in 3" :key="i"></i></div>
          <div v-else-if="filteredFiles.length" class="sidebar-document-list">
            <article v-for="file in filteredFiles" :key="file.id" :class="{ selected: selectedIds.includes(file.id) }">
              <label class="document-check" :title="'选择 ' + file.name" @click.stop><input type="checkbox" :checked="selectedIds.includes(file.id)" :disabled="isBatchDeleting || deletingId === file.id" @change="toggleFileSelection(file.id)"/><span></span></label>
              <span class="sidebar-file-icon">{{ fileIcon(file.name) }}</span>
              <div class="sidebar-file-main"><strong :title="file.name">{{ file.name }}</strong><small>{{ file.medicalMetadata?.issuer || '发布机构未标注' }} · {{ formatFileSize(file.size) }}</small><span class="file-status table-status" :class="statusClass(file.status)"><i></i>{{ statusText(file.status) }}</span></div>
              <button class="sidebar-file-delete" :disabled="deletingId === file.id || isBatchDeleting" :title="'删除 ' + file.name" @click="handleDelete(file)">{{ deletingId === file.id ? '…' : '×' }}</button>
            </article>
          </div>
          <div v-else class="sidebar-document-empty"><span>{{ documentQuery ? '⌕' : '▤' }}</span><strong>{{ documentQuery ? '没有匹配的文档' : '暂无文档' }}</strong><button v-if="documentQuery" @click="documentQuery = ''">清除搜索</button></div>
        </section>
        <div class="metadata-card">
          <div class="metadata-title"><strong>文档证据元数据</strong><span>应用于本次批量上传</span></div>
          <div class="metadata-grid">
            <label>发布机构<input v-model.trim="metadata.issuer" placeholder="例：国家卫生健康委"/></label>
            <label>版本<input v-model.trim="metadata.version" placeholder="例：2025 版"/></label>
            <label>发布日期<input v-model="metadata.publishedAt" type="date"/></label>
            <label>适用人群<input v-model.trim="metadata.audience" placeholder="例：ADULT"/></label>
            <label>文档类型<select v-model="metadata.documentType"><option>GUIDELINE</option><option>DRUG_LABEL</option><option>SAFETY_NOTICE</option><option>SYSTEMATIC_REVIEW</option><option>CLINICAL_STUDY</option><option>MEDICAL_EDUCATION</option></select></label>
            <label>生效状态<input v-model="metadata.effectiveStatus" disabled/></label>
            <label>审核状态<input v-model="metadata.reviewStatus" disabled/></label>
          </div>
        </div>
        <!-- 上传区 -->
        <div
          class="upload-zone"
          :class="{ 'upload-zone--active': isDragging, 'upload-zone--uploading': isUploading }"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
          @click="triggerFileInput"
        >
          <input
            ref="fileInputRef"
            type="file"
            class="file-input"
            accept=".pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.pptx"
            multiple
            @change="handleFileSelect"
          />
          <template v-if="isUploading">
            <div class="uploading-row">
              <span class="uploading-icon">⏳</span>
              <div class="uploading-info">
                <p class="uploading-title">
                  正在上传 {{ uploadCompleted + 1 }}/{{ uploadTotal }}：{{ uploadCurrentName }}
                </p>
                <p class="uploading-progress">整体进度 {{ overallUploadProgress }}%</p>
                <div class="progress-bar">
                  <div class="progress-fill" :style="{ width: overallUploadProgress + '%' }"></div>
                </div>
              </div>
            </div>
          </template>
          <template v-else>
            <span class="upload-icon">⬆️</span>
            <div class="upload-text">
              <p class="upload-title">{{ uploadRetrySeconds ? `上传限流，请等待 ${uploadRetrySeconds} 秒` : '拖拽一个或多个文件到此处，或点击选择' }}</p>
              <p class="upload-hint">支持批量上传 PDF / Word / TXT / MD / Excel / PPT</p>
              <p class="upload-categories">临床指南 · 专家共识 · 药品说明书 · 健康教育资料</p>
            </div>
          </template>
        </div>

        <!-- 文件列表 -->
        <div class="file-panel embedded-file-panel">
          <div class="panel-header file-panel-header">
            <div class="file-panel-heading">
              <span class="panel-header-title">📁 我的文档</span>
              <span class="panel-header-count">{{ fileList.length }}</span>
            </div>
            <div v-if="fileList.length" class="batch-actions">
              <button class="select-all-btn" :disabled="isBatchDeleting" @click="toggleSelectAll">
                {{ allFilesSelected ? '取消全选' : '全选' }}
              </button>
              <button
                class="batch-delete-btn"
                :disabled="!selectedCount || isBatchDeleting || deletingId !== null"
                @click="handleBatchDelete"
              >
                {{ isBatchDeleting ? `删除中 ${batchDeleteCompleted}/${batchDeleteTotal}` : `删除所选 (${selectedCount})` }}
              </button>
            </div>
          </div>
          <div class="file-list">
            <div v-for="file in fileList" :key="file.id" class="file-item" :class="{ 'file-item--selected': selectedIds.includes(file.id) }">
              <label class="file-select" :title="`选择 ${file.name}`" @click.stop>
                <input
                  type="checkbox"
                  :checked="selectedIds.includes(file.id)"
                  :disabled="isBatchDeleting || deletingId === file.id"
                  @change="toggleFileSelection(file.id)"
                />
              </label>
              <span class="file-icon">{{ fileIcon(file.name) }}</span>
              <div class="file-detail">
                <div class="file-name-row">
                  <span class="file-name">{{ file.name }}</span>
                  <span v-if="file.status" class="file-status" :class="statusClass(file.status)">
                    {{ statusText(file.status) }}
                  </span>
                </div>
                <span class="file-meta">{{ formatFileSize(file.size) }} · {{ formatTime(file.uploadTime) }}</span>
                <span v-if="file.summary" class="file-summary">📝 {{ file.summary }}</span>
                <span v-if="file.error" class="file-error">⚠️ {{ file.error }}</span>
              </div>
              <button class="delete-btn" @click="handleDelete(file)" :disabled="deletingId === file.id || isBatchDeleting" title="删除文件">
                {{ deletingId === file.id ? '…' : '🗑️' }}
              </button>
            </div>
            <!-- 空状态 -->
            <div v-if="fileList.length === 0 && !isUploading" class="file-empty">
              <div class="file-empty-icon">🗂️</div>
              <p class="file-empty-title">暂无文档</p>
              <p class="file-empty-hint">上传来源明确、版本可核验的医疗资料</p>
            </div>
          </div>
        </div>
      </section>

      <!-- 右栏：检索验证 -->
      <section class="panel panel-right">
        <div class="panel-header">
          <span class="panel-header-title">⌕ 检索验证</span>
          <span class="panel-header-hint">用于验证已上传文档的召回与引用效果</span>
        </div>

        <div class="qa-body" ref="qaBodyRef">
          <!-- 空状态 -->
          <div v-if="qaHistory.length === 0" class="qa-empty">
            <div class="qa-empty-icon">🔍</div>
            <p class="qa-empty-title">验证知识库检索效果</p>
            <p class="qa-empty-hint">输入测试问题，检查召回片段、回答与来源是否准确：</p>
            <div class="qa-suggestions">
              <button
                v-for="s in suggestions"
                :key="s"
                class="suggestion-chip"
                @click="question = s"
              >{{ s }}</button>
            </div>
          </div>

          <!-- 问答记录 -->
          <div v-for="(qa, i) in qaHistory" :key="i" class="qa-entry">
            <div class="qa-question">
              <span class="qa-mark qa-mark--q">Q</span>
              <div class="qa-q-text markdown-body" v-html="renderMarkdown(qa.question)"></div>
            </div>
            <div v-if="qa.answer" class="qa-answer">
              <span class="qa-mark qa-mark--a">A</span>
              <div class="qa-response">
                <div class="qa-a-text markdown-body" v-html="renderMarkdownWithCitations(qa.answer, qa.sources, 'kb-source-' + i)"></div>
                <section v-if="qa.plan || qa.validation || qa.retrieval || qa.riskLevel" class="qa-quality">
                  <div class="qa-quality-head"><strong>检索与回答校验</strong><div class="qa-quality-badges"><span v-if="qa.riskLevel" :class="'qa-risk qa-risk-' + String(qa.riskLevel).toLowerCase()">{{ riskLabel(qa.riskLevel) }}</span><span :class="'qa-validation qa-validation-' + (qa.validation?.status || 'unknown')">{{ validationLabel(qa.validation?.status) }}</span></div></div>
                  <p v-if="qa.plan">回答类型：{{ qa.plan.answer_type || '未标注' }}<template v-if="qa.plan.sections?.length"> · {{ qa.plan.sections.join(' / ') }}</template></p>
                  <p v-if="qa.validation?.citation_integrity">引用覆盖率：{{ formatScore(qa.validation.citation_integrity.citation_coverage) }} · 有效引用 {{ qa.validation.citation_integrity.referenced_citations?.length || 0 }}</p>
                  <ul v-if="validationWarnings(qa.validation).length"><li v-for="warning in validationWarnings(qa.validation)" :key="warning">{{ warning }}</li></ul>
                  <p v-if="needsProfessionalReview(qa)" class="qa-review-note">该回答需要医生或药师复核后再用于医疗判断。</p>
                  <small v-if="qa.retrieval">检索模式：{{ qa.retrieval.mode || 'unknown' }} · 稠密候选 {{ qa.retrieval.dense_candidates || 0 }} · BM25 候选 {{ qa.retrieval.bm25_candidates || 0 }}</small>
                </section>
                <div v-if="qa.sources?.length" class="source-list">
                  <details v-for="(source, sourceIndex) in qa.sources" :id="'kb-source-' + i + '-' + (source.citation || sourceIndex + 1)" :key="source.chunkId || source.id || sourceIndex" class="source-card">
                    <summary>
                      <span class="source-index">[{{ source.citation }}]</span>
                      <span class="source-name">{{ source.fileName }}</span>
                      <span class="source-section">{{ source.section }}</span>
                      <span class="source-score">{{ formatScore(source.score) }}</span>
                    </summary>
                    <div class="source-content">
                      <div class="medical-source-meta">
                        <span>发布机构：{{ source.issuer || '未标注' }}</span><span>版本：{{ source.version || '未标注' }}</span>
                        <span>发布日期：{{ source.publishedAt || '未标注' }}</span><span>适用人群：{{ source.audience || '未标注' }}</span>
                        <span>生效：{{ source.effectiveStatus || '待核验' }}</span><span>审核：{{ source.reviewStatus || '待核验' }}</span>
                      </div>
                      <template v-if="source.originalUrl">
                        <div class="original-file-toolbar">
                          <span>原始文件预览</span>
                          <a :href="source.originalUrl" target="_blank" rel="noopener noreferrer">在新窗口打开 ↗</a>
                        </div>
                        <iframe
                          v-if="isPdfSource(source)"
                          class="original-file-frame"
                          :src="source.originalUrl"
                          :title="`${source.fileName} 原始文件`"
                        ></iframe>
                        <p v-else-if="!source.displayContent" class="source-tip">
                          当前文件类型不支持网页内预览，请点击“在新窗口打开”查看原始文件。
                        </p>
                      </template>
                      <p v-if="source.displayContent" class="source-quote">{{ source.displayContent }}</p>
                      <p v-else-if="!source.originalUrl" class="source-tip">
                        文档正文仍在解析中，暂时无法展示有效的原文片段。
                      </p>
                    </div>
                  </details>
                </div>
                <div v-else class="no-evidence">当前没有足够的已审核医疗证据，无法根据当前证据回答。</div>
              </div>
            </div>
            <div v-else-if="qa.error" class="qa-answer">
              <span class="qa-mark qa-mark--a">A</span>
              <div class="qa-a-text qa-a-text--error">⚠️ {{ qa.error }}</div>
            </div>
            <div v-else class="qa-pending">
              <span class="pending-dots"><span></span><span></span><span></span></span>
              正在检索知识库…
            </div>
          </div>
        </div>

        <!-- 输入栏 -->
        <div class="qa-input-bar">
          <label class="topk-control" title="返回的证据片段数量">
            Top K
            <select v-model.number="topK" :disabled="isQuerying">
              <option v-for="value in [3, 5, 8, 10]" :key="value" :value="value">{{ value }}</option>
            </select>
          </label>
          <input
            v-model="question"
            class="qa-input"
            type="text"
            placeholder="输入医疗资料问题（请勿填写身份信息）…"
            :disabled="isQuerying"
            @keydown.enter="doQuery"
          />
          <button class="qa-btn" @click="doQuery" :disabled="isQuerying || !question.trim()">
            {{ isQuerying ? '检索中' : '开始验证' }}
          </button>
        </div>
      </section>
    </div>

    <!-- 悬浮提示 -->
    <transition name="toast">
      <div v-if="notice" class="notice-toast" :class="'notice-toast--' + notice.type">
        <span class="notice-icon">{{ notice.type === 'error' ? '⚠️' : notice.type === 'success' ? '✅' : 'ℹ️' }}</span>
        {{ notice.text }}
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { uploadKnowledgeFile, getKnowledgeFiles, deleteKnowledgeFile, queryKnowledgeBase, getTask } from '../api'
import { emergencyMessage } from '../utils/medical'

const router = useRouter()

const fileInputRef = ref(null)
const fileList = ref([])
const isLoadingFiles = ref(false)
const documentQuery = ref('')
const isDragging = ref(false)
const isUploading = ref(false)
const uploadProgress = ref(0)
const uploadCurrentName = ref('')
const uploadTotal = ref(0)
const uploadCompleted = ref(0)
const deletingId = ref(null)
const selectedIds = ref([])
const isBatchDeleting = ref(false)
const batchDeleteCompleted = ref(0)
const batchDeleteTotal = ref(0)
const metadata = ref({ issuer: '', version: '', publishedAt: '', audience: '', documentType: 'GUIDELINE', effectiveStatus: 'ACTIVE', reviewStatus: 'APPROVED' })

// 检索问答状态（对话式历史）
const question = ref('')
const emergency = ref('')
const qaHistory = ref([]) // [{ question, answer, error }]
const isQuerying = ref(false)
const topK = ref(8)
const qaBodyRef = ref(null)
const pollingTimers = new Set()

// 顶部提示消息
const notice = ref(null)
let noticeTimer = null
const uploadRetrySeconds = ref(0)
let uploadRetryTimer = null

// 已索引文件数
const indexedCount = computed(() => fileList.value.filter(f => ['INDEXED', 'INDEXED_DEGRADED'].includes(f.status)).length)
const selectedCount = computed(() => selectedIds.value.length)
const allFilesSelected = computed(() => fileList.value.length > 0 && selectedIds.value.length === fileList.value.length)
const filteredFiles = computed(() => {
  const keyword = documentQuery.value.toLowerCase()
  if (!keyword) return fileList.value
  return fileList.value.filter(file => [file.name, file.medicalMetadata?.issuer, file.medicalMetadata?.version, file.medicalMetadata?.audience]
    .some(value => String(value || '').toLowerCase().includes(keyword)))
})
const overallUploadProgress = computed(() => {
  if (!uploadTotal.value) return 0
  const progress = (uploadCompleted.value + uploadProgress.value / 100) / uploadTotal.value * 100
  return Math.min(100, Math.round(progress))
})

// 推荐问题
const suggestions = [
  '这份指南适用于哪些人群？有哪些危险信号？',
  '比较文档中不同建议的证据依据和发布日期',
  '总结药品说明书中的禁忌、不良反应和就医提示'
]

const goBack = () => {
  router.push('/')
}

const triggerFileInput = () => {
  if (isUploading.value || uploadRetrySeconds.value) return
  fileInputRef.value?.click()
}

const handleDrop = (e) => {
  isDragging.value = false
  const files = e.dataTransfer?.files
  if (files?.length) {
    doUpload(Array.from(files))
  }
}

const handleFileSelect = (e) => {
  const files = e.target?.files
  if (files?.length) {
    doUpload(Array.from(files))
  }
  // 重置 input 以允许重复上传同一文件
  e.target.value = ''
}

const doUpload = async (files) => {
  if (isUploading.value || !files?.length) return
  if (uploadRetrySeconds.value) {
    showNotice('error', `上传请求仍在限流，请等待 ${uploadRetrySeconds.value} 秒`)
    return
  }
  const required = ['issuer', 'version', 'publishedAt', 'audience', 'documentType', 'effectiveStatus', 'reviewStatus']
  if (required.some(key => !metadata.value[key])) {
    showNotice('error', '请先完整填写文档证据元数据')
    return
  }

  isUploading.value = true
  uploadProgress.value = 0
  uploadTotal.value = files.length
  uploadCompleted.value = 0
  const failedFiles = []
  let successCount = 0
  showNotice('info', `准备上传 ${files.length} 个文件...`)

  for (const file of files) {
    uploadCurrentName.value = file.name
    uploadProgress.value = 0
    try {
      const res = await uploadKnowledgeFile(file, metadata.value, (progress) => {
        uploadProgress.value = progress
      })
      const data = res?.data
      successCount++
      if (data?.taskId) {
        pollUploadTask(data.taskId, data.name || file.name)
      }
    } catch (error) {
      failedFiles.push({ name: file.name, reason: extractError(error) })
      if (error?.response?.status === 429) startUploadCountdown(error)
    } finally {
      uploadCompleted.value++
    }
  }

  await loadFileList(false)
  if (failedFiles.length) {
    const names = failedFiles.slice(0, 3).map(item => `「${item.name}」`).join('、')
    const more = failedFiles.length > 3 ? `等 ${failedFiles.length} 个文件` : ''
    if (uploadRetrySeconds.value) {
      notice.value = { type: 'error', text: `上传请求过于频繁，请等待 ${uploadRetrySeconds.value} 秒后重试；已成功上传 ${successCount} 个` }
    } else {
      showNotice('error', `成功上传 ${successCount} 个，${names}${more}上传失败`)
    }
  } else {
    showNotice('success', `${successCount} 个文件已上传，后台正在解析与索引`)
  }

  isUploading.value = false
  uploadProgress.value = 0
  uploadCurrentName.value = ''
  uploadTotal.value = 0
  uploadCompleted.value = 0
}

const pollUploadTask = (taskId, fileName) => {
  const poll = async () => {
    try {
      const { data } = await getTask(taskId)
      await loadFileList(false)
      if (data?.status === 'COMPLETED') {
        showNotice('success', `「${fileName}」已完成解析与索引`)
        return
      }
      if (data?.status === 'FAILED' || data?.status === 'CANCELLED') {
        showNotice('error', `「${fileName}」处理${data.status === 'FAILED' ? '失败' : '已取消'}：${data.error || data.stage || '未知原因'}`)
        return
      }
      const timer = setTimeout(poll, 1500)
      pollingTimers.add(timer)
    } catch (error) {
      showNotice('error', `无法获取「${fileName}」处理进度：${extractError(error)}`)
    }
  }
  poll()
}

const handleDelete = async (file) => {
  if (!window.confirm(`确定删除“${file.name}”吗？原文及其知识库索引将同时删除，且无法恢复。`)) return
  deletingId.value = file.id
  try {
    await deleteKnowledgeFile(file.id)
    fileList.value = fileList.value.filter(f => f.id !== file.id)
    selectedIds.value = selectedIds.value.filter(id => id !== file.id)
    showNotice('success', `已删除「${file.name}」`)
  } catch (error) {
    showNotice('error', extractError(error))
  } finally {
    deletingId.value = null
  }
}

const toggleFileSelection = (fileId) => {
  selectedIds.value = selectedIds.value.includes(fileId)
    ? selectedIds.value.filter(id => id !== fileId)
    : [...selectedIds.value, fileId]
}

const toggleSelectAll = () => {
  selectedIds.value = allFilesSelected.value ? [] : fileList.value.map(file => file.id)
}

const handleBatchDelete = async () => {
  if (!selectedIds.value.length || isBatchDeleting.value) return
  const selectedFiles = fileList.value.filter(file => selectedIds.value.includes(file.id))
  if (!selectedFiles.length) return
  if (!window.confirm(`确定要永久删除选中的 ${selectedFiles.length} 个文件及其知识库索引吗？`)) return

  isBatchDeleting.value = true
  batchDeleteTotal.value = selectedFiles.length
  batchDeleteCompleted.value = 0
  const failedIds = []
  const failedNames = []

  for (const file of selectedFiles) {
    try {
      await deleteKnowledgeFile(file.id)
      fileList.value = fileList.value.filter(item => item.id !== file.id)
    } catch (error) {
      failedIds.push(file.id)
      failedNames.push(file.name)
    } finally {
      batchDeleteCompleted.value++
    }
  }

  selectedIds.value = failedIds
  const deletedCount = selectedFiles.length - failedIds.length
  if (failedIds.length) {
    const names = failedNames.slice(0, 3).map(name => `「${name}」`).join('、')
    const more = failedNames.length > 3 ? `等 ${failedNames.length} 个文件` : ''
    showNotice('error', `已删除 ${deletedCount} 个，${names}${more}删除失败，请重试`)
  } else {
    showNotice('success', `已批量删除 ${deletedCount} 个文件及其索引`)
  }

  isBatchDeleting.value = false
  batchDeleteCompleted.value = 0
  batchDeleteTotal.value = 0
}

const loadFileList = async (showError = true) => {
  isLoadingFiles.value = true
  try {
    const res = await getKnowledgeFiles()
    if (res.data) {
      fileList.value = Array.isArray(res.data) ? res.data : []
      const existingIds = new Set(fileList.value.map(file => file.id))
      selectedIds.value = selectedIds.value.filter(id => existingIds.has(id))
    }
  } catch (error) {
    console.error('Failed to load file list:', error)
    if (showError) showNotice('error', extractError(error))
  } finally {
    isLoadingFiles.value = false
  }
}

// 提取后端返回的错误信息
const extractError = (error) => {
  const data = error?.response?.data
  if (error?.response?.status === 429) {
    const retry = error.response.headers?.['retry-after']
    return `请求过于频繁，请${retry ? `在 ${retry} 秒后` : '稍后'}重试`
  }
  if (error?.response?.status >= 500) return '服务暂时不可用，请稍后重试'
  if (typeof data === 'string') return data
  if (data && typeof data === 'object') return data.message || data.error?.message || data.error || '请求失败'
  return error?.message || '请求失败'
}

const startUploadCountdown = (error) => {
  const seconds = Number(error?.response?.headers?.['retry-after'])
  if (!Number.isFinite(seconds) || seconds <= 0) return
  if (noticeTimer) {
    clearTimeout(noticeTimer)
    noticeTimer = null
  }
  if (uploadRetryTimer) clearInterval(uploadRetryTimer)
  uploadRetrySeconds.value = Math.ceil(seconds)
  notice.value = { type: 'error', text: `上传请求过于频繁，请等待 ${uploadRetrySeconds.value} 秒` }
  uploadRetryTimer = setInterval(() => {
    uploadRetrySeconds.value--
    if (uploadRetrySeconds.value > 0) {
      notice.value = { type: 'error', text: `上传请求过于频繁，请等待 ${uploadRetrySeconds.value} 秒` }
      return
    }
    clearInterval(uploadRetryTimer)
    uploadRetryTimer = null
    showNotice('info', '限流等待已结束，现在可以重新上传')
  }, 1000)
}

// 显示悬浮提示（5 秒后自动消失）
const showNotice = (type, text) => {
  notice.value = { type, text }
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    notice.value = null
  }, 5000)
}

// 文件索引状态展示
const statusClass = (status) => {
  if (status === 'INDEXED' || status === 'INDEXED_DEGRADED') return 'status-indexed'
  if (status === 'FAILED') return 'status-failed'
  return 'status-pending'
}

const statusText = (status) => {
  if (status === 'INDEXED') return '已索引'
  if (status === 'INDEXED_DEGRADED') return '本地索引完成'
  if (status === 'FAILED') return '解析失败'
  const labels = { QUEUED: '等待处理', UPLOADING: '上传存储', PARSING: '解析中', INDEXING: '索引中', PARSING_INDEXING: '解析并索引中' }
  return labels[status] || '处理中'
}

// 按文件类型显示图标
const fileIcon = (name = '') => {
  const ext = name.slice(name.lastIndexOf('.')).toLowerCase()
  if (ext === '.pdf') return '📕'
  if (ext === '.doc' || ext === '.docx') return '📘'
  if (ext === '.xlsx' || ext === '.csv') return '📊'
  if (ext === '.pptx') return '📙'
  if (ext === '.md' || ext === '.txt') return '📝'
  return '📄'
}

// 检索验证区滚动到底部
const scrollQaToBottom = async () => {
  await nextTick()
  if (qaBodyRef.value) {
    qaBodyRef.value.scrollTop = qaBodyRef.value.scrollHeight
  }
}

// 知识库检索问答
const doQuery = async () => {
  const q = question.value.trim()
  if (!q || isQuerying.value) return

  emergency.value = ''
  const index = qaHistory.value.length
  qaHistory.value.push({ question: q, answer: '', sources: [], error: '' })
  question.value = ''
  isQuerying.value = true
  scrollQaToBottom()

  try {
    const res = await queryKnowledgeBase(q, topK.value)
    const data = res?.data
    qaHistory.value[index].answer = typeof data === 'string'
      ? data
      : (data?.answer || data?.content || data?.message || '未获取到回答')
    qaHistory.value[index].sources = normalizeSources(Array.isArray(data?.sources) ? data.sources : [])
    qaHistory.value[index].plan = data?.plan
    qaHistory.value[index].validation = data?.validation
    qaHistory.value[index].retrieval = data?.retrieval
    qaHistory.value[index].riskLevel = data?.riskLevel
    const emergencyText = emergencyMessage(data)
    if (emergencyText) emergency.value = emergencyText
  } catch (error) {
    qaHistory.value[index].error = extractError(error)
  } finally {
    isQuerying.value = false
    scrollQaToBottom()
  }
}

const formatFileSize = (bytes) => {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return size.toFixed(1) + ' ' + units[i]
}

const formatTime = (timeStr) => {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  })
}

const formatScore = (score) => Number.isFinite(Number(score)) ? `${(Number(score) * 100).toFixed(1)}%` : ''
const validationLabel = value => ({ passed: '校验通过', revised: '已修订', needs_review: '需要复核', deterministic_only: '确定性分诊' }[value] || '未校验')
const riskLabel = value => ({ EMERGENCY: '紧急', HIGH: '高风险', ROUTINE: '常规', MEDIUM: '中风险', LOW: '低风险' }[String(value || '').toUpperCase()] || '待评估')
const needsProfessionalReview = qa => qa?.validation?.status === 'needs_review' || ['HIGH', 'EMERGENCY'].includes(String(qa?.riskLevel || '').toUpperCase())
const validationWarnings = validation => {
  if (!validation) return []
  return [
    ...(validation.unsupported_claims || []).map(item => `缺少证据：${item}`),
    ...(validation.missing_citations || []).map(item => `缺少引用：${item}`),
    ...(validation.conflicts || []).map(item => `来源冲突：${item}`),
    ...(validation.citation_integrity?.invalid_citations || []).map(item => `无效引用：[${item}]`)
  ]
}

const renderMarkdown = (content) => {
  if (!content) return ''
  const html = marked.parse(String(content), {
    gfm: true,
    breaks: true,
    async: false
  })
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}

const renderMarkdownWithCitations = (content, sources, prefix) => {
  const html = renderMarkdown(content)
  if (!sources?.length || typeof document === 'undefined') return html
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  const citations = new Set(sources.map((source, index) => String(source.citation || index + 1)))
  const walker = document.createTreeWalker(parsed.body, NodeFilter.SHOW_TEXT)
  const nodes = []
  let node
  while ((node = walker.nextNode())) {
    if (!node.parentElement?.closest('a, code, pre')) nodes.push(node)
  }
  nodes.forEach(textNode => {
    const text = textNode.nodeValue || ''
    const pattern = /\[(\d+)\]/g
    if (!pattern.test(text)) return
    pattern.lastIndex = 0
    const fragment = document.createDocumentFragment()
    let cursor = 0
    let match
    while ((match = pattern.exec(text))) {
      fragment.append(text.slice(cursor, match.index))
      if (citations.has(match[1])) {
        const link = document.createElement('a')
        link.className = 'citation-link'
        link.href = `#${prefix}-${match[1]}`
        link.textContent = `[${match[1]}]`
        link.title = `查看来源 ${match[1]}`
        fragment.append(link)
      } else {
        fragment.append(match[0])
      }
      cursor = pattern.lastIndex
    }
    fragment.append(text.slice(cursor))
    textNode.replaceWith(fragment)
  })
  return DOMPurify.sanitize(parsed.body.innerHTML, { USE_PROFILES: { html: true } })
}

const handleCitationClick = (event) => {
  const link = event.target.closest?.('a.citation-link')
  if (!link) return
  const target = document.querySelector(link.getAttribute('href'))
  if (!target) return
  event.preventDefault()
  if (target.tagName === 'DETAILS') target.open = true
  target.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

// MinerU 异步提交响应有时会被错误写入索引。溯源区隐藏任务 JSON，优先展示其中的原文件。
const looksLikeTaskMetadata = (value) => {
  if (typeof value !== 'string') return false
  const text = value.trim()
  return text.startsWith('{') && /"(?:task_id|output_dir|cache_key|auto_index|fingerprint_kind|items)"\s*:/.test(text)
}

const findOriginalUrl = (value) => {
  if (!value) return ''
  if (typeof value === 'string') {
    if (/^https?:\/\//i.test(value)) return value
    const matched = value.match(/"source"\s*:\s*"((?:\\.|[^"\\])*)"/)
    if (matched) {
      try { return JSON.parse(`"${matched[1]}"`) } catch { return matched[1].replaceAll('\\/', '/') }
    }
    return ''
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const url = findOriginalUrl(item)
      if (url) return url
    }
    return ''
  }
  if (typeof value === 'object') {
    if (typeof value.source === 'string' && /^https?:\/\//i.test(value.source)) return value.source
    for (const item of Object.values(value)) {
      const url = findOriginalUrl(item)
      if (url) return url
    }
  }
  return ''
}

const extractReadableContent = (quote) => {
  if (typeof quote !== 'string') return ''
  if (!looksLikeTaskMetadata(quote)) return quote.trim()
  try {
    const parsed = JSON.parse(quote)
    const candidates = [parsed.markdown, parsed.content, parsed.text, parsed.result?.markdown, parsed.result?.content]
    return candidates.find(item => typeof item === 'string' && item.trim())?.trim() || ''
  } catch {
    return ''
  }
}

const normalizeSources = (sources) => {
  const documentUrls = new Map()
  sources.forEach(source => {
    const url = findOriginalUrl(source?.quote)
    if (url && source?.documentId) documentUrls.set(source.documentId, url)
  })
  return sources.map((source, index) => ({
    ...source,
    citation: source.citation || index + 1,
    fileName: source.fileName || source.title || source.documentName || '未命名来源',
    publishedAt: source.publishedAt || source.publishDate,
    displayContent: extractReadableContent(source?.quote),
    originalUrl: (/^https?:\/\//i.test(source?.sourceUri || '') ? source.sourceUri : '') || findOriginalUrl(source?.quote) || documentUrls.get(source?.documentId) || ''
  }))
}

const isPdfSource = (source) => /\.pdf(?:$|[?#])/i.test(source?.fileName || source?.originalUrl || '')

onMounted(() => {
  loadFileList()
})

onBeforeUnmount(() => {
  pollingTimers.forEach(timer => clearTimeout(timer))
  pollingTimers.clear()
  if (noticeTimer) clearTimeout(noticeTimer)
  if (uploadRetryTimer) clearInterval(uploadRetryTimer)
})
</script>

<style scoped>
.kb-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f4f6f9;
  color: #1e293b;
  position: relative;
}

.metadata-card{order:1;flex-shrink:0;margin:14px 14px 10px;padding:14px;border:1px solid #d8e6e4;border-radius:14px;background:linear-gradient(145deg,#f8fcfb,#f2f8f7)}.metadata-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:11px;color:#215b5c}.metadata-title strong{font-size:13px}.metadata-title span{font-size:10px;color:#819391}.metadata-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.metadata-grid label{display:flex;flex-direction:column;gap:4px;color:#647975;font-size:10px}.metadata-grid input,.metadata-grid select{height:32px;border:1px solid #d8e3e1;border-radius:8px;background:#fff;padding:0 8px;color:#314d4b;font:11px inherit;outline:0}.metadata-grid input:focus,.metadata-grid select:focus{border-color:#0f8b7e;box-shadow:0 0 0 2px rgba(15,139,126,.09)}.medical-source-meta{display:grid;grid-template-columns:repeat(2,1fr);gap:5px 12px;padding:9px;background:#f1f7f5;border-radius:8px;color:#5c726d;font-size:11px}.no-evidence{margin-top:10px;padding:10px 12px;border-radius:9px;background:#fff7df;color:#805c00;font-size:12px}

/* ===== 顶部导航 ===== */
.kb-header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 14px 24px;
  background: #ffffff;
  border-bottom: 1px solid #e8edf2;
  position: sticky;
  top: 0;
  z-index: 10;
}

.policy-notice{margin:14px 24px 0;padding:10px 14px;border:1px solid #fde68a;border-radius:10px;background:#fffbeb;color:#92400e;display:flex;gap:12px;align-items:flex-start;font-size:12px;line-height:1.6}.policy-notice strong{white-space:nowrap}.upload-categories{margin-top:6px;font-size:10px;color:#94a3b8}

.kb-emergency {
  margin: 0 24px 16px;
  padding: 14px 18px;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  border: 1px solid #fecaca;
  border-radius: 14px;
  background: linear-gradient(135deg, #fff1f2, #fff7f7);
  color: #991b1b;
  box-shadow: 0 10px 28px rgba(153, 27, 27, .08);
}

.kb-emergency strong {
  flex: 0 0 auto;
  padding: 3px 8px;
  border-radius: 7px;
  background: #b91c1c;
  color: #fff;
  font-size: 12px;
}

.back-button {
  font-size: 15px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: all 0.3s;
  color: #64748b;
}

.back-button:hover {
  color: #10b981;
  transform: translateX(-4px);
}

.back-button:before {
  content: '←';
  margin-right: 8px;
}

.kb-title {
  font-size: 19px;
  font-weight: 700;
  margin: 0;
  color: #0f766e;
  letter-spacing: -0.3px;
}

.header-right {
  text-align: right;
}

.header-stats {
  font-size: 13px;
  color: #64748b;
  background: #f1f5f9;
  padding: 4px 12px;
  border-radius: 999px;
}

/* ===== 双栏工作区 ===== */
.kb-workspace {
  flex: 1;
  min-height: 0; /* 允许子面板内部滚动 */
  display: flex;
  gap: 16px;
  padding: 16px 20px;
  max-width: 1400px;
  width: 100%;
  margin: 0 auto;
}

.panel {
  background: #ffffff;
  border: 1px solid #e8edf2;
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(15, 23, 42, 0.03);
}

.panel-left {
  flex: 0 0 400px;
  overflow-x: hidden;
  overflow-y: auto;
}

.panel-right {
  flex: 1;
  min-width: 0;
}

/* 面板标题栏 */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 20px;
  border-bottom: 1px solid #f1f5f9;
  background: #fafbfc;
  flex-shrink: 0;
}

.panel-header-title {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
}

.panel-header-count {
  font-size: 12px;
  color: #0f766e;
  background: #ecfdf5;
  padding: 2px 10px;
  border-radius: 999px;
  font-weight: 600;
}

.panel-header-hint {
  font-size: 12px;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ===== 左栏：上传区 ===== */
.upload-zone {
  order: 3;
  flex-shrink: 0;
  margin: 10px 14px 14px;
  border: 2px dashed #d1d9e0;
  border-radius: 12px;
  padding: 18px 20px;
  display: flex;
  align-items: center;
  gap: 14px;
  cursor: pointer;
  transition: all 0.25s;
  background: #fafbfc;
  flex-shrink: 0;
}

.upload-zone:hover,
.upload-zone--active {
  border-color: #10b981;
  background: #f0fdf6;
}

.upload-zone--uploading {
  border-color: #10b981;
  border-style: solid;
  cursor: not-allowed;
  background: #f0fdf6;
}

.file-input {
  display: none;
}

.upload-icon {
  font-size: 28px;
  flex-shrink: 0;
}

.upload-title {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
  margin: 0;
}

.upload-hint {
  font-size: 12px;
  color: #94a3b8;
  margin: 4px 0 0;
}

.uploading-row {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
}

.uploading-icon {
  font-size: 24px;
  flex-shrink: 0;
}

.uploading-info {
  flex: 1;
  min-width: 0;
}

.uploading-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f766e;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.uploading-progress {
  margin: 3px 0 6px;
  font-size: 11px;
  color: #64748b;
}

.progress-bar {
  width: 100%;
  height: 6px;
  background: #e5e7eb;
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #10b981, #34d399);
  border-radius: 3px;
  transition: width 0.3s ease;
}

/* ===== 左栏：文件列表 ===== */
.file-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 0 0;
}

.file-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 16px 16px;
}

.file-panel-header {
  gap: 12px;
}

.file-panel-heading,
.batch-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.batch-actions {
  margin-left: auto;
}

.select-all-btn,
.batch-delete-btn {
  height: 28px;
  padding: 0 9px;
  border: 1px solid #dce3e9;
  border-radius: 8px;
  background: #ffffff;
  color: #64748b;
  font-size: 11px;
  white-space: nowrap;
  transition: all 0.2s;
}

.select-all-btn:hover:not(:disabled) {
  border-color: #6ee7b7;
  color: #047857;
  background: #f0fdf6;
}

.batch-delete-btn {
  border-color: #fecaca;
  color: #b91c1c;
  background: #fff7f7;
}

.batch-delete-btn:hover:not(:disabled) {
  border-color: #f87171;
  background: #fef2f2;
}

.select-all-btn:disabled,
.batch-delete-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.file-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #f1f5f9;
  border-radius: 12px;
  margin-bottom: 10px;
  transition: all 0.2s;
  background: #ffffff;
}

.file-item:hover {
  border-color: #d1fae5;
  background: #f8fffc;
  box-shadow: 0 2px 10px rgba(16, 185, 129, 0.06);
}

.file-item--selected {
  border-color: #6ee7b7;
  background: #f0fdf6;
}

.file-select {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 26px;
  flex-shrink: 0;
  cursor: pointer;
}

.file-select input {
  width: 15px;
  height: 15px;
  accent-color: #10b981;
  cursor: pointer;
}

.file-select input:disabled {
  cursor: not-allowed;
}

.file-icon {
  font-size: 22px;
  flex-shrink: 0;
  margin-top: 2px;
}

.file-detail {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.file-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.file-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.file-status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  flex-shrink: 0;
  font-weight: 500;
}

.status-indexed {
  background: #ecfdf5;
  color: #047857;
}

.status-failed {
  background: #fef2f2;
  color: #b91c1c;
}

.status-pending {
  background: #f1f5f9;
  color: #64748b;
}

.file-meta {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}

.file-summary {
  font-size: 12px;
  color: #64748b;
  margin-top: 6px;
  line-height: 1.5;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.file-error {
  font-size: 12px;
  color: #b91c1c;
  margin-top: 6px;
  word-break: break-word;
}

.delete-btn {
  width: 32px;
  height: 32px;
  background: transparent;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  flex-shrink: 0;
}

.delete-btn:hover:not(:disabled) {
  background: #fef2f2;
  border-color: #fecaca;
}

.delete-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 文件空状态 */
.file-empty {
  text-align: center;
  padding: 48px 16px;
  color: #94a3b8;
}

.file-empty-icon {
  font-size: 40px;
  margin-bottom: 10px;
}

.file-empty-title {
  font-size: 14px;
  font-weight: 600;
  color: #64748b;
  margin: 0 0 4px;
}

.file-empty-hint {
  font-size: 12px;
  margin: 0;
}

/* ===== 右栏：检索验证区 ===== */
.qa-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 24px 12px;
}

/* 问答空状态 */
.qa-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 20px;
}

.qa-empty-icon {
  font-size: 48px;
  margin-bottom: 14px;
}

.qa-empty-title {
  font-size: 17px;
  font-weight: 700;
  color: #334155;
  margin: 0 0 6px;
}

.qa-empty-hint {
  font-size: 13px;
  color: #94a3b8;
  margin: 0 0 18px;
}

.qa-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  max-width: 420px;
}

.suggestion-chip {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  padding: 8px 16px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  transition: all 0.2s;
}

.suggestion-chip:hover {
  border-color: #10b981;
  color: #0f766e;
  background: #f0fdf6;
}

/* 问答条目 */
.qa-entry {
  margin-bottom: 24px;
  animation: qa-in 0.25s ease;
}

@keyframes qa-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

.qa-question {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.qa-mark {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
}

.qa-mark--q {
  background: #10b981;
  color: #ffffff;
}

.qa-mark--a {
  background: #ecfdf5;
  color: #047857;
}

.qa-q-text {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  line-height: 1.6;
  word-break: break-word;
  padding-top: 1px;
}

.qa-answer {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-top: 10px;
}

.qa-response {
  flex: 1;
  min-width: 0;
}

.qa-a-text {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #334155;
  line-height: 1.75;
  background: #f8fafc;
  border: 1px solid #f1f5f9;
  border-radius: 12px;
  padding: 12px 16px;
  word-break: break-word;
}

.markdown-body :deep(p) {
  margin: 0 0 10px;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  color: #1e293b;
  line-height: 1.4;
  margin: 18px 0 8px;
  font-weight: 700;
}

.markdown-body :deep(h1:first-child),
.markdown-body :deep(h2:first-child),
.markdown-body :deep(h3:first-child) {
  margin-top: 0;
}

.markdown-body :deep(h1) { font-size: 20px; }
.markdown-body :deep(h2) { font-size: 18px; }
.markdown-body :deep(h3) { font-size: 16px; }
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) { font-size: 14px; }

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0 12px;
  padding-left: 24px;
}

.markdown-body :deep(li) {
  margin: 4px 0;
}

.markdown-body :deep(blockquote) {
  margin: 12px 0;
  padding: 8px 14px;
  border-left: 4px solid #34d399;
  background: #ecfdf5;
  color: #475569;
}

.markdown-body :deep(code) {
  padding: 2px 5px;
  border-radius: 5px;
  background: #e9eef5;
  color: #be185d;
  font: 0.9em Consolas, Monaco, monospace;
}

.markdown-body :deep(pre) {
  margin: 12px 0;
  padding: 14px;
  overflow-x: auto;
  border-radius: 10px;
  background: #172033;
  color: #e2e8f0;
  line-height: 1.6;
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.markdown-body :deep(a) {
  color: #047857;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.markdown-body :deep(hr) {
  height: 1px;
  margin: 16px 0;
  border: 0;
  background: #dce5e1;
}

.markdown-body :deep(table) {
  display: block;
  width: 100%;
  margin: 12px 0;
  overflow-x: auto;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 8px 10px;
  border: 1px solid #dce5e1;
  text-align: left;
  white-space: nowrap;
}

.markdown-body :deep(th) {
  background: #ecfdf5;
  color: #065f46;
  font-weight: 600;
}

.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}

.qa-a-text--error {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fee2e2;
}

.qa-quality{margin-top:9px;border:1px solid #dce8e5;border-radius:10px;background:#f8fcfb;overflow:hidden;color:#607773;font-size:10px}.qa-quality-head{display:flex;justify-content:space-between;align-items:center;padding:8px 10px;border-bottom:1px solid #e5efed}.qa-quality-badges{display:flex;align-items:center;gap:6px}.qa-validation,.qa-risk{padding:3px 7px;border-radius:99px;background:#e8efee}.qa-validation-passed{background:#def4e5;color:#277343}.qa-validation-revised,.qa-validation-needs_review{background:#fff1d5;color:#86600c}.qa-validation-deterministic_only{background:#e6efff;color:#315ea8}.qa-risk-emergency,.qa-risk-high{background:#fee2e2;color:#b91c1c}.qa-risk-medium{background:#fff1d5;color:#86600c}.qa-risk-low,.qa-risk-routine{background:#def4e5;color:#277343}.qa-quality>p,.qa-quality>small{display:block;padding:7px 10px;border-top:1px solid #edf3f2}.qa-quality>.qa-review-note{background:#fff8e6;color:#805f18;font-weight:700}.qa-quality>ul{margin:0;padding:8px 10px 8px 27px;background:#fff8e6;color:#805f18}

.source-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.source-card {
  border: 1px solid #dbe8e3;
  border-radius: 10px;
  background: #fbfefd;
  color: #475569;
}

.markdown-body :deep(.citation-link){display:inline-flex;align-items:center;margin:0 2px;padding:0 5px;border-radius:6px;background:#d9f2e8;color:#08786c!important;font-size:.82em;font-weight:800;line-height:1.55;text-decoration:none;vertical-align:1px;transition:.18s}.markdown-body :deep(.citation-link:hover){background:#0f8b7e;color:#fff!important;transform:translateY(-1px)}.source-card{scroll-margin:80px}.source-card:target{border-color:#0f8b7e;box-shadow:0 0 0 3px rgba(15,139,126,.12)}

.source-card summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  cursor: pointer;
  font-size: 12px;
  list-style: none;
}

.source-card summary::-webkit-details-marker { display: none; }
.source-card summary::after { content: '展开'; margin-left: auto; color: #0f766e; }
.source-card[open] summary::after { content: '收起'; }

.source-content {
  padding: 0 12px 12px;
}

.source-card .source-quote,
.source-card .source-tip {
  padding: 10px 0 0;
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: #64748b;
  white-space: pre-wrap;
}

.source-card .source-tip {
  color: #8a6d3b;
  background: #fffbeb;
  border-radius: 8px;
  padding: 10px;
}

.original-file-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 0;
  font-size: 12px;
  color: #475569;
  border-top: 1px solid #e8f0ed;
}

.original-file-toolbar a {
  color: #047857;
  font-weight: 600;
}

.original-file-frame {
  display: block;
  width: 100%;
  height: min(62vh, 620px);
  min-height: 360px;
  border: 1px solid #dbe8e3;
  border-radius: 8px;
  background: #f8fafc;
}

.source-index { color: #047857; font-weight: 700; }
.source-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-section { color: #94a3b8; white-space: nowrap; }
.source-score { color: #059669; white-space: nowrap; }

.document-manager{
  margin:16px 22px 0;
  border:1px solid #d9e5e3;
  border-radius:18px;
  background:#fff;
  box-shadow:0 10px 28px rgba(30,70,68,.06);
  overflow:hidden;
  flex-shrink:0;
}
.document-manager-head{min-height:74px;padding:14px 18px;display:flex;align-items:center;justify-content:space-between;gap:18px;border-bottom:1px solid #e7eeee}
.document-manager-title{display:flex;align-items:center;gap:11px}.document-manager-icon{width:39px;height:39px;display:grid;place-items:center;border-radius:11px;background:#e2f3f0;color:#08796f;font-size:20px}.document-manager-title h2{font-size:16px;color:#244746}.document-manager-title p{margin-top:3px;color:#839491;font-size:11px}.document-total{min-width:25px;height:25px;padding:0 7px;display:grid;place-items:center;border-radius:99px;background:#edf4f3;color:#55716e;font-size:11px;font-weight:700}.document-actions{display:flex;align-items:center;gap:7px}.document-search{height:36px;width:min(260px,23vw);display:flex;align-items:center;gap:7px;padding:0 10px;border:1px solid #dbe6e4;border-radius:10px;background:#f9fbfb;color:#78908c}.document-search input{width:100%;border:0;outline:0;background:transparent;color:#365552;font:12px inherit}.refresh-files-btn{height:36px!important;padding:0 11px!important;display:flex!important;align-items:center;gap:5px;border:1px solid #d7e4e2!important;border-radius:10px!important;background:#fff!important;color:#33716b!important;font-size:12px}.refresh-files-btn .spinning{animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}.document-table-wrap{max-height:255px;overflow:auto}.document-table{display:grid;grid-template-columns:34px minmax(210px,1.55fr) minmax(170px,1.2fr) 110px 120px 72px;align-items:center;gap:10px;padding:0 18px}.document-table-header{height:37px;position:sticky;top:0;z-index:2;background:#f7faf9;color:#7b8d8a;font-size:10px;font-weight:700;text-transform:uppercase}.document-row{min-height:66px;border-top:1px solid #edf2f1;transition:.18s}.document-row:hover{background:#fafdfc}.document-row.selected{background:#eff9f7}.document-check{display:grid;place-items:center}.document-check input{position:absolute;opacity:0}.document-check span{width:17px;height:17px;border:1px solid #bfcfcd;border-radius:5px;background:#fff}.document-check input:checked+span{background:#078478;border-color:#078478;box-shadow:inset 0 0 0 4px #078478}.document-check input:checked+span:after{content:'✓';display:block;color:#fff;font-size:11px;text-align:center;line-height:15px}.document-name-cell{min-width:0;display:flex;align-items:center;gap:10px}.document-type-icon{width:34px;height:38px;display:grid;place-items:center;border-radius:9px;background:#f1f6f5}.document-name-cell>div,.document-evidence{min-width:0}.document-name-cell strong,.document-evidence strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#314f4d;font-size:12px}.document-name-cell small,.document-evidence small{display:block;margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#899996;font-size:10px}.document-row>time{color:#718681;font-size:11px}.table-status{display:inline-flex!important;align-items:center;gap:5px;font-size:10px!important}.table-status i{width:6px;height:6px;border-radius:50%;background:currentColor}.document-error{display:block;max-width:105px;margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#c54545;font-size:9px}.document-delete{height:31px!important;padding:0 11px!important;border:1px solid #f2cece!important;border-radius:9px!important;background:#fff!important;color:#b43b3b!important;font-size:11px!important}.document-delete:hover:not(:disabled){background:#fff1f1!important;border-color:#e8a8a8!important}.document-empty{min-height:145px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#889a97}.document-empty>div{width:38px;height:38px;display:grid;place-items:center;border-radius:12px;background:#edf4f3;color:#60807b;font-size:20px}.document-empty strong{margin-top:9px;color:#526c68;font-size:13px}.document-empty span{margin-top:4px;font-size:11px}.document-empty button{margin-top:9px;border:0;background:transparent;color:#078478;font:11px inherit}.document-loading{padding:16px 18px;display:grid;gap:9px}.document-loading i{height:42px;border-radius:9px;background:linear-gradient(90deg,#eef3f2,#f8fbfa,#eef3f2);background-size:200% 100%;animation:skeleton 1.2s infinite}@keyframes skeleton{to{background-position:-200% 0}}.embedded-file-panel{display:none!important}

.top-document-manager{display:none!important}
.sidebar-documents{order:2;display:flex;flex-direction:column;flex:1;min-height:150px;margin:0 14px;border:1px solid #dce7e5;border-radius:14px;background:#fff;overflow:hidden}.sidebar-documents-head{height:52px;padding:9px 12px;display:flex;align-items:center;justify-content:space-between;flex-shrink:0;background:#fbfdfd}.sidebar-documents-head>div{min-width:0}.sidebar-documents-head strong{display:block;color:#294c49;font-size:13px}.sidebar-documents-head span{display:block;margin-top:2px;color:#879895;font-size:9px}.sidebar-documents-head>button{width:29px;height:29px;display:grid;place-items:center;border:1px solid #d9e5e3;border-radius:9px;background:#fff;color:#32776f;font-size:16px}.sidebar-documents-head .spinning{animation:spin .8s linear infinite}.sidebar-document-tools{display:flex;gap:5px;padding:7px 10px 9px;border-top:1px solid #eff4f3;flex-shrink:0}.sidebar-document-tools label{height:31px;min-width:0;flex:1;display:flex;align-items:center;gap:5px;padding:0 8px;border:1px solid #dbe6e4;border-radius:8px;background:#f8fbfa;color:#7a908d}.sidebar-document-tools input{width:100%;border:0;outline:0;background:transparent;color:#365552;font:10px inherit}.sidebar-document-tools>button{height:31px;padding:0 8px;border:1px solid #d7e3e1;border-radius:8px;background:#fff;color:#55726e;font:9px inherit}.sidebar-document-tools .sidebar-batch-delete{border-color:#f0d0d0;color:#ad4141}.sidebar-document-tools button:disabled{opacity:.45;cursor:not-allowed}.sidebar-document-list{flex:1;min-height:0;overflow-y:auto;border-top:1px solid #eef3f2}.sidebar-document-list article{min-height:59px;display:grid;grid-template-columns:19px 32px minmax(0,1fr) 27px;align-items:center;gap:7px;padding:7px 9px 7px 10px;border-bottom:1px solid #edf2f1;transition:.18s}.sidebar-document-list article:hover{background:#f8fbfa}.sidebar-document-list article.selected{background:#edf8f6}.sidebar-file-icon{width:30px;height:34px;display:grid;place-items:center;border-radius:8px;background:#f0f5f4;font-size:16px}.sidebar-file-main{min-width:0}.sidebar-file-main>strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#345350;font-size:11px}.sidebar-file-main>small{display:block;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#8a9997;font-size:9px}.sidebar-file-main .table-status{margin-top:3px;padding:2px 6px!important;font-size:8px!important}.sidebar-file-delete{width:25px;height:25px;border:0;border-radius:8px;background:transparent;color:#a8b4b2;font-size:18px;line-height:1}.sidebar-file-delete:hover:not(:disabled){background:#ffeded;color:#b83939}.sidebar-document-empty{flex:1;min-height:90px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#849794}.sidebar-document-empty>span{font-size:19px}.sidebar-document-empty strong{margin-top:4px;font-size:11px}.sidebar-document-empty button{margin-top:4px;border:0;background:transparent;color:#087f75;font:9px inherit}.sidebar-document-loading{padding:8px 10px;display:grid;gap:6px}.sidebar-document-loading i{height:42px;border-radius:8px;background:linear-gradient(90deg,#eef3f2,#fafcfc,#eef3f2);background-size:200%;animation:skeleton 1.2s infinite}

.sidebar-documents{min-height:320px}
.sidebar-document-tools .sidebar-select-all{flex:0 0 auto;width:auto;padding:0 7px;background:#fff;cursor:pointer;color:#55726e}.sidebar-select-all input{position:static!important;width:14px!important;height:14px!important;opacity:1!important;accent-color:#087f75;cursor:pointer}.sidebar-select-all span{font-size:9px;white-space:nowrap}.sidebar-document-list .document-check{width:18px;height:18px;display:grid;place-items:center;cursor:pointer}.sidebar-document-list .document-check input{position:static;width:16px;height:16px;opacity:1;accent-color:#087f75;cursor:pointer}.sidebar-document-list .document-check span{display:none}.sidebar-batch-delete{white-space:nowrap}

/* 检索中状态 */
.qa-pending {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  margin-left: 34px;
  font-size: 13px;
  color: #10b981;
}

.pending-dots {
  display: flex;
  gap: 4px;
}

.pending-dots span {
  width: 6px;
  height: 6px;
  background-color: #10b981;
  border-radius: 50%;
  animation: dot-bounce 1.4s infinite ease-in-out both;
}

.pending-dots span:nth-child(1) { animation-delay: -0.32s; }
.pending-dots span:nth-child(2) { animation-delay: -0.16s; }

@keyframes dot-bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1.0); }
}

/* 输入栏 */
.qa-input-bar {
  display: flex;
  gap: 10px;
  padding: 14px 20px 18px;
  border-top: 1px solid #f1f5f9;
  background: #fafbfc;
  flex-shrink: 0;
}

.topk-control {
  height: 44px;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 0 9px;
  border: 1px solid #e2e8f0;
  border-radius: 11px;
  background: #ffffff;
  color: #64748b;
  font-size: 12px;
  white-space: nowrap;
}

.topk-control select {
  border: 0;
  outline: 0;
  color: #0f766e;
  font: inherit;
  font-weight: 600;
  background: transparent;
}

.qa-input {
  flex: 1;
  min-width: 0;
  height: 44px;
  padding: 0 16px;
  border: 1px solid #e2e8f0;
  border-radius: 11px;
  font-size: 14px;
  outline: none;
  color: #1e293b;
  background: #ffffff;
  transition: all 0.2s;
  font-family: inherit;
}

.qa-input:focus {
  border-color: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.12);
}

.qa-input:disabled {
  background: #f8fafc;
  cursor: not-allowed;
}

.qa-btn {
  height: 44px;
  padding: 0 24px;
  background: #10b981;
  color: #ffffff;
  border: none;
  border-radius: 11px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.qa-btn:hover:not(:disabled) {
  background: #059669;
}

.qa-btn:disabled {
  background: #d1d5db;
  cursor: not-allowed;
}

/* ===== 悬浮提示 ===== */
.notice-toast {
  position: fixed;
  top: 68px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 18px;
  border-radius: 12px;
  font-size: 14px;
  z-index: 100;
  max-width: min(90vw, 560px);
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.12);
  border: 1px solid transparent;
}

.notice-icon {
  flex-shrink: 0;
}

.notice-toast--info {
  background: #eff6ff;
  color: #1d4ed8;
  border-color: #dbeafe;
}

.notice-toast--success {
  background: #ecfdf5;
  color: #047857;
  border-color: #d1fae5;
}

.notice-toast--error {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fee2e2;
}

/* toast 进出场动画 */
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-10px);
}

/* ===== 响应式 ===== */
@media (max-width: 900px) {
  .kb-container {
    height: auto;
    min-height: 100vh;
  }

  .kb-workspace {
    flex-direction: column;
    padding: 14px 14px 20px;
  }

  .panel-left,
  .panel-right {
    flex: none;
  }

  .file-list {
    max-height: 340px;
  }

  .qa-body {
    min-height: 220px;
    max-height: 520px;
  }

  .kb-title {
    font-size: 17px;
  }

  .header-stats {
    display: none;
  }

  .topk-control { display: none; }
  .metadata-grid{grid-template-columns:1fr}
  .sidebar-documents{flex:none;max-height:330px}.panel-left{overflow:visible}.sidebar-document-list{max-height:220px}
  .document-manager{margin:12px 14px 0}.document-manager-head{align-items:flex-start;flex-direction:column}.document-actions{width:100%;flex-wrap:wrap}.document-search{width:100%;flex:1 0 100%}.document-table-wrap{max-height:310px}.document-table{grid-template-columns:28px minmax(190px,1fr) 110px 70px}.document-table-header span:nth-child(3),.document-table-header span:nth-child(4),.document-row>.document-evidence,.document-row>time{display:none}
}
</style>
