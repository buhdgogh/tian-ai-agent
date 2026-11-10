<template>
  <section class="ui-page audit">
    <header class="ui-page-heading">
      <div><span class="ui-kicker">Agent Runs</span><h1>运行日志</h1><p>查看执行路径、模型和工具调用、缓存结果、耗时及 Token 用量。</p></div>
      <button class="ui-button" type="button" :disabled="loading" @click="refresh">
        <WorkspaceIcon name="refresh" /> {{ loading ? '刷新中' : '刷新' }}
      </button>
    </header>
    <p v-if="error" role="alert" class="ui-notice error">{{ error }}</p>
    <div class="workspace">
      <section class="ui-panel runs" aria-label="运行列表">
        <div class="list-head"><h2>{{ taskId ? '关联任务的运行' : '最近运行' }}</h2><span class="ui-pill">{{ visibleRuns.length }} 条</span></div>
        <RouterLink v-if="taskId" to="/agent-runs" class="back">查看全部运行</RouterLink>
        <p v-if="!visibleRuns.length && !loading" class="muted">{{ taskId ? '最近运行中暂无该任务的审计记录。' : '当前身份暂无运行记录。' }}</p>
        <button v-for="run in visibleRuns" :key="run.id" type="button" class="run"
          :class="{ selected: selectedId === run.id }" :aria-pressed="selectedId === run.id" @click="select(run.id)">
          <span><b>{{ runName(run.kind) }}</b><span class="ui-pill" :data-tone="statusTone(run.status)">{{ label(run.status) }}</span></span>
          <small>{{ time(run.startedAt) }} · {{ duration(run.elapsedMs) }} · {{ run.eventCount }} 条事件</small>
        </button>
      </section>
      <section v-if="detail" class="ui-panel detail" aria-label="运行详情">
        <div class="detail-head">
          <div><h2>{{ runName(detail.summary.kind) }}</h2><code>{{ detail.summary.id }}</code></div>
          <span class="ui-pill" :data-tone="statusTone(detail.summary.status)">{{ label(detail.summary.status) }}</span>
        </div>
        <div v-if="detail.summary.parentRunId" class="links">
          <button class="ui-button" type="button" @click="select(detail.summary.parentRunId)">查看上游分发</button>
        </div>
        <details v-if="task" class="task-result">
          <summary>{{ task.type === 'WIKI_COMPILE' ? 'Wiki 编译' : '工作流' }} · {{ label(task.status) }} · {{ task.progress ?? 0 }}%</summary>
          <p v-if="task.stage">{{ task.stage }}</p>
          <pre v-if="task.result?.executionResult">{{ task.result.executionResult }}</pre>
          <p v-if="task.type === 'WIKI_COMPILE' && task.result">已从 {{ task.result.sourceCount }} 份资料生成 {{ task.result.pageCount }} 个页面。</p>
          <p v-if="task.error">{{ task.error }}</p>
        </details>
        <dl class="metrics">
          <div><dt>总耗时</dt><dd>{{ duration(detail.summary.elapsedMs) }}</dd></div>
          <div><dt>输入 Token</dt><dd>{{ number(detail.summary.inputTokens) }}</dd></div>
          <div><dt>输出 Token</dt><dd>{{ number(detail.summary.outputTokens) }}</dd></div>
          <div><dt>总 Token</dt><dd>{{ number(detail.summary.totalTokens) }}</dd></div>
        </dl>
        <p v-if="cacheHits || cacheMisses" class="muted">缓存命中 {{ cacheHits }} 次 · 未命中 {{ cacheMisses }} 次</p>
        <div class="events-head">
          <h3>执行时间线</h3>
          <div class="filters" aria-label="筛选事件类型">
            <button v-for="filter in eventFilters" :key="filter.value" type="button" :class="{ active: kindFilter === filter.value }"
              :aria-pressed="kindFilter === filter.value" @click="kindFilter = filter.value">{{ filter.label }}</button>
          </div>
        </div>
        <p v-if="!filteredEvents.length" class="empty">当前筛选条件下没有事件。</p>
        <div v-else class="table-wrap">
          <table>
            <thead><tr><th>调用</th><th>状态</th><th>时间线</th><th>耗时</th><th>Token 入 / 出 / 总</th></tr></thead>
            <tbody>
              <tr v-for="(event, index) in filteredEvents" :key="index">
                <td>
                  <small>{{ kindName(event.kind) }}</small>
                  <strong>{{ event.model || event.name }}</strong>
                  <small v-if="event.provider">{{ event.provider }}</small>
                </td>
                <td><span class="ui-pill" :data-tone="statusTone(event.status)">{{ label(event.status) }}</span><small v-if="event.reason">{{ eventReason(event) }}</small></td>
                <td class="timeline"><div class="track"><div class="bar" :class="[event.kind, event.status]" :style="barStyle(event)"
                  :title="time(event.startedAt)"></div></div></td>
                <td>{{ duration(event.elapsedMs) }}</td>
                <td>{{ number(event.inputTokens) }} / {{ number(event.outputTokens) }} / {{ number(event.totalTokens) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <section v-else class="ui-panel detail empty">选择一条运行记录查看执行时间线。</section>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import WorkspaceIcon from '../components/WorkspaceIcon.vue'
import { getAgentRun, getAgentRuns, getTask } from '../api'

const runs = ref([])
const selectedId = ref('')
const detail = ref(null)
const task = ref(null)
const loading = ref(false)
const error = ref('')
let timer
let detailVersion = 0
const route = useRoute()
const runId = computed(() => typeof route.query.runId === 'string' ? route.query.runId : '')
const taskId = computed(() => typeof route.query.taskId === 'string' ? route.query.taskId : '')
const visibleRuns = computed(() => taskId.value ? runs.value.filter(run => run.taskId === taskId.value) : runs.value)

const kindFilter = ref('')
const eventFilters = [
  { value: '', label: '全部' }, { value: 'STAGE', label: '编排' },
  { value: 'MODEL', label: '模型' }, { value: 'TOOL', label: '工具' }, { value: 'CACHE', label: '缓存' }
]
const events = computed(() => [...(detail.value?.events || [])]
  .sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt)))
const cacheEvents = computed(() => events.value.filter(event => event.kind === 'CACHE'))
const cacheHits = computed(() => cacheEvents.value.filter(event => event.status === 'HIT').length)
const cacheMisses = computed(() => cacheEvents.value.filter(event => event.status === 'MISS').length)
const filteredEvents = computed(() => kindFilter.value ? events.value.filter(event => event.kind === kindFilter.value) : events.value)
watch(selectedId, () => { kindFilter.value = '' })
const statusTone = status => ['SUCCESS', 'COMPLETED', 'HIT'].includes(status) ? 'positive'
  : ['RUNNING', 'PENDING'].includes(status) ? 'active'
    : ['FAILED', 'TIMEOUT', 'REJECTED', 'BLOCKED'].includes(status) ? 'negative' : 'neutral'
const kindName = kind => ({ MODEL: '模型', TOOL: '工具', STAGE: '阶段', CACHE: '缓存' })[kind] || kind
const cacheReasons = {
  EXACT: '精确匹配', SEMANTIC: '语义匹配', DISABLED: '缓存已关闭',
  INELIGIBLE: '请求不符合缓存规则', CACHE_READ_FAILED: '缓存读取失败，已回退到完整检索'
}
const eventReason = event => event.kind === 'CACHE' ? cacheReasons[event.reason] || event.reason : event.reason
const runName = kind => ({ CHAT: '对话', CHAT_STREAM: '流式对话', TOOL_CHAT: '工具对话', RAG_CHAT: '知识库对话',
  REACT: 'ReAct', WORKFLOW: '工作流', WIKI_COMPILE: 'Wiki 编译', DISPATCH: '意图分发', EVIDENCE_REPORT: '证据报告' })[kind] || kind
const label = status => ({ RUNNING: '运行中', COMPLETED: '完成', PENDING: '等待执行', SUCCESS: '成功', FAILED: '失败',
  SKIPPED: '跳过', BLOCKED: '拦截', REJECTED: '拒绝', CANCELLED: '取消', TIMEOUT: '超时', HIT: '命中', MISS: '未命中' })[status] || status
const number = value => value == null ? '—' : value.toLocaleString()
const time = value => new Date(value).toLocaleString('zh-CN', { hour12: false })
const duration = ms => ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${ms} ms`
const barStyle = event => {
  const start = Date.parse(detail.value.summary.startedAt)
  const total = Math.max(1, detail.value.summary.elapsedMs)
  const left = Math.min(99, Math.max(0, (Date.parse(event.startedAt) - start) * 100 / total))
  return { left: `${left}%`, width: `${Math.min(100 - left, Math.max(1, event.elapsedMs * 100 / total))}%` }
}
const message = err => err?.response?.status === 401
  ? '请在页面上方设置有效的访问令牌。' : '运行审计读取失败，请稍后刷新。'

async function select(id, keep = false) {
  const version = ++detailVersion
  selectedId.value = id
  if (!keep) {
    detail.value = null
    task.value = null
    error.value = ''
  }
  try {
    const { data } = await getAgentRun(id)
    if (version !== detailVersion) return
    detail.value = data
    if (data.summary.taskId) {
      const response = await getTask(data.summary.taskId).catch(() => null)
      if (version === detailVersion) task.value = response?.data || null
    }
  } catch (err) {
    if (version === detailVersion) error.value = message(err)
  }
}

async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const { data } = await getAgentRuns()
    runs.value = Array.isArray(data) ? data : []
    const id = selectedId.value || runId.value || visibleRuns.value[0]?.id
    if (id) await select(id, id === selectedId.value)
  } catch (err) {
    error.value = message(err)
  } finally {
    loading.value = false
  }
}

watch([taskId, runId], () => {
  detailVersion++
  selectedId.value = ''
  detail.value = null
  task.value = null
  refresh()
})
onMounted(() => {
  refresh()
  timer = setInterval(() => {
    if (taskId.value || visibleRuns.value.some(run => run.status === 'RUNNING')) refresh()
  }, 4000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.workspace { display:grid; grid-template-columns:minmax(220px, 280px) minmax(0, 1fr); align-items:start; gap:14px; }
.ui-page-heading button svg { width:16px; height:16px; }
.ui-notice { margin-bottom:12px; }
.runs,.detail { padding:14px; }
.list-head,.detail-head,.events-head { display:flex; align-items:center; justify-content:space-between; gap:8px; flex-wrap:wrap; }
.list-head { padding-bottom:8px; }
h2 { font-size:15px; }
h3 { font-size:13px; }
.muted,small,code { color:var(--muted); font-size:12px; }
code { overflow-wrap:anywhere; }
.run {
  width:100%;
  margin:4px 0;
  padding:9px;
  border:1px solid var(--line);
  border-radius:8px;
  color:var(--ink);
  background:var(--surface);
  text-align:left;
}
.run:hover,.run.selected { border-color:#b4beef; background:var(--accent-soft); }
.run > span { display:flex; justify-content:space-between; align-items:center; gap:6px; }
.run small { display:block; margin-top:4px; }
.metrics { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; margin:13px 0; }
.metrics > div { padding:8px 10px; border-radius:8px; background:var(--surface-muted); }
.metrics dt { color:var(--muted); font-size:11px; }
.metrics dd { margin-top:2px; font-size:16px; font-weight:750; overflow-wrap:anywhere; }
.task-result { padding:9px; margin-top:10px; border:1px solid var(--line); border-radius:8px; font-size:12px; }
.task-result pre { white-space:pre-wrap; overflow-wrap:anywhere; margin:8px 0 0; }
.back { font-size:12px; }
.links { margin-top:10px; }
.events-head { margin:15px 0 6px; }
.filters { display:flex; flex-wrap:wrap; gap:4px; }
.filters button { padding:4px 8px; border:1px solid transparent; border-radius:7px; color:var(--muted); background:transparent; font-size:12px; }
.filters button:hover,.filters button.active { border-color:#d9dffd; color:var(--accent-ink); background:var(--accent-soft); }
.table-wrap { overflow-x:auto; }
table { width:100%; min-width:535px; border-collapse:collapse; font-size:12px; text-align:left; }
th,td { padding:8px 7px; border-bottom:1px solid var(--line); }
thead { background:var(--surface-muted); color:var(--muted); }
td small,td strong { display:block; overflow-wrap:anywhere; }
td small { font-size:11px; }
.timeline { width:29%; min-width:115px; }
.track { position:relative; height:8px; border-radius:8px; background:#edf0f8; }
.bar { position:absolute; height:100%; border-radius:8px; background:#8997af; }
.bar.MODEL { background:#4b57c7; }
.bar.TOOL,.bar.CACHE.HIT { background:#19826d; }
.bar.FAILED,.bar.TIMEOUT,.bar.BLOCKED,.bar.REJECTED { background:#ca4d62; }
.empty { padding:33px 12px; color:var(--muted); text-align:center; }
@media (max-width:820px) { .workspace { grid-template-columns:1fr; } .runs { max-height:260px; overflow:auto; } }
@media (max-width:500px) { .metrics { grid-template-columns:repeat(2,1fr); } }
</style>
