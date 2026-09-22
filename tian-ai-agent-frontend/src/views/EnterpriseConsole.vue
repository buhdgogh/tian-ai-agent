<template>
  <div class="console-page">
    <header class="console-header">
      <button class="back" @click="router.push('/')">← 返回</button>
      <div>
        <h1>管理控制台</h1>
        <p>管理员专用 · 工作流、异步任务、RAG 评测与工具审计</p>
      </div>
      <button class="refresh" :disabled="loading" @click="refreshCurrent">{{ loading ? '刷新中…' : '刷新' }}</button>
    </header>

    <main class="console-main">
      <nav class="tabs" aria-label="控制台模块">
        <button v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" @click="tab = item.id">
          <span>{{ item.icon }}</span>{{ item.label }}
        </button>
      </nav>

      <section v-if="tab === 'workflow'" class="workspace two-column">
        <div class="card form-card">
          <div class="card-title"><div><h2>创建规划工作流</h2><p>ProfileAnalyzer → CareerPlanner → PlanReviewer</p></div><span class="badge blue">异步</span></div>
          <label>规划目标<textarea v-model="workflow.objective" rows="7" placeholder="例如：分析 Java 后端实习岗位要求，并制定未来 12 周的准备计划"></textarea></label>
          <div class="form-grid">
            <label>用户 ID<input v-model="workflow.ownerId" placeholder="student_001" /></label>
            <label>会话 ID<input v-model="workflow.chatId" placeholder="留空则自动生成" /></label>
          </div>
          <label class="checkbox"><input v-model="workflow.reviewEnabled" type="checkbox" />启用 Reviewer 结果复核</label>
          <button class="primary" :disabled="submitting || !workflow.objective.trim()" @click="submitWorkflow">
            {{ submitting ? '正在提交…' : '启动工作流' }}
          </button>
        </div>

        <div class="card">
          <div class="card-title"><div><h2>最近的规划任务</h2><p>自动刷新运行进度与结果</p></div></div>
          <TaskList :tasks="workflowTasks" empty-text="尚未提交工作流" @cancel="doCancel" />
        </div>
      </section>

      <section v-else-if="tab === 'tasks'" class="workspace">
        <div class="card">
          <div class="card-title horizontal">
            <div><h2>统一异步任务中心</h2><p>查看、筛选或取消后台任务</p></div>
            <div class="filters">
              <input v-model="filters.ownerId" placeholder="所有者 ID" @keydown.enter="loadTasks" />
              <select v-model="filters.type" @change="loadTasks">
                <option value="">全部类型</option><option value="AGENT_WORKFLOW">Agent Workflow</option>
                <option value="DOCUMENT_INDEX">文档索引</option><option value="RAG_EVALUATION">RAG 评测</option>
              </select>
              <button class="secondary" @click="loadTasks">查询</button>
            </div>
          </div>
          <TaskList :tasks="tasks" empty-text="没有符合条件的任务" @cancel="doCancel" />
        </div>
      </section>

      <section v-else-if="tab === 'evaluation'" class="workspace two-column evaluation-grid">
        <div class="card form-card">
          <div class="card-title"><div><h2>新建 RAG 评测</h2><p>计算 HitRate@K、MRR@K 与首位得分</p></div><span class="badge green">回归</span></div>
          <div class="form-grid">
            <label>评测名称<input v-model="evaluation.name" /></label>
            <label>所有者 ID<input v-model="evaluation.ownerId" /></label>
            <label>Top K<input v-model.number="evaluation.topK" type="number" min="1" max="20" /></label>
            <label class="checkbox inline-check"><input v-model="evaluation.generateAnswers" type="checkbox" />生成答案并统计引用覆盖率</label>
          </div>
          <label>评测用例（JSON 数组）
            <textarea v-model="evaluation.casesText" class="code-input" rows="13" spellcheck="false"></textarea>
          </label>
          <p class="form-help">每条用例包含 id、question、expectedDocumentIds；文档 ID 可在知识库文件列表中获取。</p>
          <button class="primary green-button" :disabled="submitting" @click="submitEvaluation">{{ submitting ? '正在提交…' : '启动评测' }}</button>
        </div>
        <div class="card">
          <div class="card-title"><div><h2>评测任务</h2><p>完成后展开任务查看指标与逐条结果</p></div></div>
          <TaskList :tasks="evaluationTasks" empty-text="尚未运行 RAG 评测" @cancel="doCancel" />
        </div>
      </section>

      <section v-else class="workspace">
        <div class="card">
          <div class="card-title"><div><h2>Tool Gateway 审计</h2><p>最近的受控工具调用、耗时与错误</p></div><span class="badge amber">管理员</span></div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>时间</th><th>工具</th><th>状态</th><th>耗时</th><th>参数摘要</th><th>错误</th></tr></thead>
              <tbody>
                <tr v-for="audit in audits" :key="audit.id">
                  <td>{{ formatTime(audit.createdAt) }}</td><td class="strong">{{ audit.toolName }}</td>
                  <td><span class="status" :class="statusClass(audit.status)">{{ audit.status }}</span></td>
                  <td>{{ audit.durationMs }} ms</td><td class="digest">{{ audit.argumentDigest || '—' }}</td><td class="error-cell">{{ audit.error || '—' }}</td>
                </tr>
                <tr v-if="!audits.length"><td colspan="6" class="empty">暂无工具调用审计</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </main>

    <transition name="toast"><div v-if="notice" class="toast" :class="notice.type">{{ notice.type === 'error' ? '⚠' : '✓' }} {{ notice.text }}</div></transition>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { cancelTask, createAgentWorkflow, createRagEvaluation, getTasks, getToolAudits } from '../api'

const router = useRouter()
const tab = ref('workflow')
const loading = ref(false)
const submitting = ref(false)
const tasks = ref([])
const audits = ref([])
const filters = ref({ ownerId: '', type: '' })
const notice = ref(null)
let noticeTimer
let refreshTimer

const tabs = [
  { id: 'workflow', icon: '🧭', label: 'Agent Workflow' },
  { id: 'tasks', icon: '⏱', label: '任务中心' },
  { id: 'evaluation', icon: '📈', label: 'RAG 评测' },
  { id: 'audits', icon: '🛡', label: '工具审计' }
]

const workflow = ref({ objective: '', ownerId: 'student_001', chatId: '', reviewEnabled: true })
const evaluation = ref({
  name: '知识库回归集', ownerId: 'qa', topK: 5, generateAnswers: false,
  casesText: JSON.stringify([{ id: 'case-1', question: '文档中的主要结论是什么？', expectedDocumentIds: ['请替换为文档ID'] }], null, 2)
})

const workflowTasks = computed(() => tasks.value.filter(item => item.type === 'AGENT_WORKFLOW'))
const evaluationTasks = computed(() => tasks.value.filter(item => item.type === 'RAG_EVALUATION'))

const showNotice = (type, text) => {
  notice.value = { type, text }
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = null }, 4500)
}

const extractError = error => {
  const data = error?.response?.data
  return typeof data === 'string' ? data : data?.message || data?.error || error?.message || '请求失败'
}

const loadTasks = async (silent = false, applyFilters = tab.value === 'tasks') => {
  if (!silent) loading.value = true
  try {
    const params = {}
    if (applyFilters && filters.value.ownerId.trim()) params.ownerId = filters.value.ownerId.trim()
    if (applyFilters && filters.value.type) params.type = filters.value.type
    const { data } = await getTasks(params)
    tasks.value = Array.isArray(data) ? data : []
  } catch (error) {
    if (!silent) showNotice('error', extractError(error))
  } finally { if (!silent) loading.value = false }
}

const loadAudits = async () => {
  loading.value = true
  try { const { data } = await getToolAudits(); audits.value = Array.isArray(data) ? data : [] }
  catch (error) { showNotice('error', extractError(error)) }
  finally { loading.value = false }
}

const refreshCurrent = () => tab.value === 'audits' ? loadAudits() : loadTasks()

const submitWorkflow = async () => {
  submitting.value = true
  try {
    await createAgentWorkflow({ ...workflow.value, objective: workflow.value.objective.trim(), chatId: workflow.value.chatId.trim() || null, ownerId: workflow.value.ownerId.trim() || null })
    workflow.value.objective = ''
    showNotice('success', '工作流已进入任务队列')
    await loadTasks(true)
  } catch (error) { showNotice('error', extractError(error)) }
  finally { submitting.value = false }
}

const submitEvaluation = async () => {
  submitting.value = true
  try {
    const cases = JSON.parse(evaluation.value.casesText)
    if (!Array.isArray(cases) || !cases.length) throw new Error('评测用例必须是非空 JSON 数组')
    await createRagEvaluation({ ...evaluation.value, cases, casesText: undefined })
    showNotice('success', 'RAG 评测已进入任务队列')
    await loadTasks(true)
  } catch (error) { showNotice('error', error instanceof SyntaxError ? '评测用例 JSON 格式不正确' : extractError(error)) }
  finally { submitting.value = false }
}

const doCancel = async task => {
  try { await cancelTask(task.id); showNotice('success', '任务已取消'); await loadTasks(true) }
  catch (error) { showNotice('error', extractError(error)) }
}

const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const statusClass = status => ['COMPLETED', 'SUCCESS'].includes(status) ? 'ok' : ['FAILED', 'ERROR'].includes(status) ? 'bad' : status === 'CANCELLED' ? 'muted' : 'running'

const TaskList = defineComponent({
  props: { tasks: { type: Array, default: () => [] }, emptyText: String },
  emits: ['cancel'],
  setup(props, { emit }) {
    const expanded = ref({})
    const label = type => ({ AGENT_WORKFLOW: 'Agent Workflow', DOCUMENT_INDEX: '文档索引', RAG_EVALUATION: 'RAG 评测' }[type] || type)
    const statusLabel = status => ({ PENDING: '等待中', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消', SUCCESS: '已完成', ERROR: '失败' }[status] || status)
    const stageLabel = stage => ({ ProfileAnalyzer: '背景与目标分析', CareerPlanner: '规划与工具执行', PlanReviewer: '完整性与可执行性复核' }[stage] || stage || '等待执行')
    const stringify = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2)
    return () => h('div', { class: 'task-list' }, props.tasks.length ? props.tasks.map(task => h('article', { class: 'task-item', key: task.id }, [
      h('div', { class: 'task-head' }, [
        h('div', { class: 'task-type' }, [h('span', { class: ['status-dot', statusClass(task.status)] }), h('div', [h('strong', label(task.type)), h('small', task.id)])]),
        h('span', { class: ['status', statusClass(task.status)] }, statusLabel(task.status))
      ]),
      h('div', { class: 'progress' }, h('span', { style: { width: `${task.progress || 0}%` } })),
      h('div', { class: 'task-meta' }, [h('span', `${task.progress || 0}% · ${stageLabel(task.stage)}`), h('time', formatTime(task.createdAt))]),
      task.events?.length ? h('p', { class: 'last-event' }, task.events[task.events.length - 1]) : null,
      h('div', { class: 'task-actions' }, [
        (task.result || task.error) ? h('button', { class: 'link-button', onClick: () => { expanded.value[task.id] = !expanded.value[task.id] } }, expanded.value[task.id] ? '收起详情' : '查看详情') : null,
        ['PENDING', 'RUNNING'].includes(task.status) ? h('button', { class: 'cancel-button', onClick: () => emit('cancel', task) }, '取消任务') : null
      ]),
      expanded.value[task.id] ? h('pre', { class: ['task-result', task.error ? 'result-error' : ''] }, stringify(task.error || task.result)) : null
    ])) : h('div', { class: 'empty-state' }, [h('span', '⌛'), h('p', props.emptyText)]))
  }
})

watch(tab, current => current === 'audits' ? loadAudits() : loadTasks(true))
onMounted(() => { loadTasks(); refreshTimer = setInterval(() => { if (tab.value !== 'audits' && tasks.value.some(t => ['PENDING', 'RUNNING'].includes(t.status))) loadTasks(true) }, 2000) })
onBeforeUnmount(() => { clearInterval(refreshTimer); clearTimeout(noticeTimer) })
</script>

<style scoped>
.console-page{min-height:100vh;background:#f4f7fb;color:#172033}.console-header{height:86px;padding:0 28px;display:grid;grid-template-columns:1fr auto 1fr;align-items:center;background:#fff;border-bottom:1px solid #e5eaf1;position:sticky;top:0;z-index:5}.console-header>div{text-align:center}.console-header h1{font-size:21px;margin:0}.console-header p{font-size:12px;color:#8390a4;margin:5px 0 0}.back,.refresh{border:0;background:transparent;color:#617087;font:inherit}.back{justify-self:start}.refresh{justify-self:end;border:1px solid #dce3ec;border-radius:9px;padding:8px 15px}.console-main{max-width:1380px;margin:0 auto;padding:22px}.tabs{display:flex;gap:7px;padding:6px;background:#e9eef5;border-radius:14px;width:max-content;max-width:100%;overflow-x:auto;margin-bottom:18px}.tabs button{border:0;background:transparent;border-radius:10px;padding:10px 16px;color:#64748b;font:inherit;white-space:nowrap}.tabs button span{margin-right:7px}.tabs button.active{background:#fff;color:#2456d8;box-shadow:0 2px 8px #2d42611a;font-weight:600}.workspace{display:grid;gap:18px}.two-column{grid-template-columns:minmax(330px,.9fr) minmax(420px,1.1fr)}.card{background:#fff;border:1px solid #e5eaf1;border-radius:17px;padding:22px;box-shadow:0 8px 30px #1d355708;min-width:0}.card-title{display:flex;justify-content:space-between;gap:12px;margin-bottom:20px}.card-title h2{font-size:17px;margin:0}.card-title p{font-size:12px;color:#8a96a8;margin:5px 0 0}.card-title.horizontal{align-items:flex-end}.badge{height:max-content;padding:4px 9px;border-radius:999px;font-size:11px}.badge.blue{background:#edf3ff;color:#2f62d9}.badge.green{background:#eafaf4;color:#07845b}.badge.amber{background:#fff6e6;color:#a86300}.form-card label{display:flex;flex-direction:column;gap:7px;font-size:12px;font-weight:600;color:#58677c;margin-bottom:15px}.form-card input,.form-card textarea,.filters input,.filters select{border:1px solid #dce3ec;border-radius:10px;padding:11px 12px;outline:0;font:inherit;color:#1e293b;background:#fff;resize:vertical}.form-card input:focus,.form-card textarea:focus{border-color:#4a76df;box-shadow:0 0 0 3px #4a76df1a}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 12px}.form-card .checkbox{flex-direction:row;align-items:center;font-weight:400}.checkbox input{width:16px;height:16px}.inline-check{align-self:end;min-height:42px}.primary,.secondary{border:0;border-radius:10px;padding:12px 18px;background:#315fdb;color:#fff;font:inherit;font-weight:600}.primary{width:100%}.primary:disabled{opacity:.55}.green-button{background:#079669}.form-help{font-size:11px;line-height:1.5;color:#94a3b8;margin:-7px 0 14px}.code-input{font-family:Consolas,monospace!important;font-size:12px!important}.filters{display:flex;gap:8px;flex-wrap:wrap}.filters input,.filters select{padding:8px 10px;font-size:12px}.secondary{padding:8px 14px}.task-list{display:grid;gap:11px;max-height:680px;overflow-y:auto;padding-right:3px}.task-item{border:1px solid #e8edf3;border-radius:13px;padding:14px;background:#fcfdff}.task-head,.task-meta,.task-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.task-type{display:flex;align-items:center;gap:9px;min-width:0}.task-type div{min-width:0;display:flex;flex-direction:column;gap:2px}.task-type strong{font-size:13px}.task-type small{font-size:10px;color:#98a3b4;overflow:hidden;text-overflow:ellipsis}.status-dot{width:8px;height:8px;border-radius:50%;flex:none}.status-dot.running{background:#3b82f6;box-shadow:0 0 0 4px #dbeafe}.status-dot.ok{background:#10b981}.status-dot.bad{background:#ef4444}.status-dot.muted{background:#94a3b8}.status{padding:3px 8px;border-radius:999px;font-size:10px;font-weight:700}.status.running{background:#eff6ff;color:#2563eb}.status.ok{background:#ecfdf5;color:#047857}.status.bad{background:#fef2f2;color:#b91c1c}.status.muted{background:#f1f5f9;color:#64748b}.progress{height:5px;background:#edf1f6;border-radius:5px;overflow:hidden;margin:13px 0 7px}.progress span{display:block;height:100%;background:linear-gradient(90deg,#4169df,#6f91ee);transition:width .35s}.task-meta{font-size:11px;color:#718096}.last-event{font-size:11px;color:#4d5b70;background:#f4f7fb;border-radius:8px;padding:7px 9px;margin:9px 0 0}.task-actions{justify-content:flex-end;margin-top:9px}.link-button,.cancel-button{border:0;background:transparent;font:inherit;font-size:11px;color:#315fdb}.cancel-button{color:#dc5353}.task-result{margin:10px 0 0;padding:12px;border-radius:9px;background:#172033;color:#d9e3f0;font:11px/1.55 Consolas,monospace;white-space:pre-wrap;max-height:320px;overflow:auto}.result-error{background:#401f26;color:#ffd9df}.empty-state{text-align:center;color:#9ba7b7;padding:70px 20px}.empty-state span{font-size:30px}.empty-state p{font-size:13px}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;font-size:12px}th{text-align:left;color:#7b8798;font-weight:600;background:#f7f9fc}th,td{padding:11px 12px;border-bottom:1px solid #edf1f5;white-space:nowrap}.strong{font-weight:600}.digest{max-width:300px;overflow:hidden;text-overflow:ellipsis}.error-cell{color:#b94b55;max-width:260px;overflow:hidden;text-overflow:ellipsis}.empty{text-align:center;color:#98a3b4;padding:60px}.toast{position:fixed;top:100px;left:50%;transform:translateX(-50%);padding:11px 17px;border-radius:10px;background:#ecfdf5;color:#047857;border:1px solid #a7f3d0;box-shadow:0 12px 30px #1720331c;z-index:20;font-size:13px}.toast.error{background:#fef2f2;color:#b91c1c;border-color:#fecaca}.toast-enter-active,.toast-leave-active{transition:.2s}.toast-enter-from,.toast-leave-to{opacity:0;transform:translate(-50%,-8px)}
@media(max-width:900px){.console-header{padding:0 16px}.console-header p{display:none}.console-main{padding:14px}.two-column{grid-template-columns:1fr}.tabs{width:100%}.tabs button{flex:1}.card-title.horizontal{align-items:flex-start;flex-direction:column}.form-grid{grid-template-columns:1fr}}@media(max-width:560px){.console-header h1{font-size:16px}.refresh{padding:7px 9px}.tabs button{padding:9px}.tabs button span{display:none}.card{padding:16px}.filters{width:100%}.filters input,.filters select{flex:1;min-width:100px}.form-grid{display:block}}
</style>
