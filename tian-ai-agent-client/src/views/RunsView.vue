<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as api from '../api/workbench'
import { errorText, getDiagnostics, type Diagnostic } from '../api/java'
import PageHeading from '../components/PageHeading.vue'

const route = useRoute()
const runs = ref<api.AgentRunSummary[]>([])
const current = ref<api.AgentRun | null>(null)
const task = ref<api.TaskRecord | null>(null)
const diagnostic = ref<Diagnostic[]>([])
const error = ref('')
const localError = ref('')
const tab = ref<'java' | 'desktop'>('java')
const eventKind = ref<api.RunEventKind | ''>('')
const kinds: (api.RunEventKind | '')[] = ['', '阶段', '模型', '工具', '缓存', '其他']
const events = computed(() => current.value?.events.filter(event => !eventKind.value || event.kind === eventKind.value) ?? [])
const tokens = computed(() => current.value?.events.reduce((sum, event) => ({
  input: sum.input + (event.inputTokens ?? 0), output: sum.output + (event.outputTokens ?? 0),
}), { input: 0, output: 0 }) ?? { input: 0, output: 0 })
const traceTokens = computed(() => runs.value.reduce((total, run) => total + (run.totalTokens ?? 0), 0))
const hits = computed(() => current.value?.events.filter(event => event.kind === '缓存' && event.status === '命中').length ?? 0)
const misses = computed(() => current.value?.events.filter(event => event.kind === '缓存' && event.status === '未命中').length ?? 0)
let selectedRequest = 0
let loadRequest = 0
async function select(id: string) {
  const request = ++selectedRequest
  error.value = ''
  try {
    const detail = id ? await api.getAgentRun(id) : null
    if (request !== selectedRequest) return
    current.value = detail
    const taskId = detail?.taskId || (typeof route.query.taskId === 'string' ? route.query.taskId : '')
    const associated = taskId ? await api.getTask(taskId) : null
    if (request === selectedRequest) task.value = associated
  } catch (failure) {
    if (request === selectedRequest) error.value = errorText(failure)
  }
}
async function loadJava() {
  const request = ++loadRequest
  error.value = ''
  try {
    const traceId = typeof route.query.traceId === 'string' ? route.query.traceId : ''
    const all = traceId ? await api.getAgentTrace(traceId) : await api.getAgentRuns()
    if (request !== loadRequest) return
    const id = typeof route.query.runId === 'string' ? route.query.runId : current.value?.id
    runs.value = typeof route.query.taskId === 'string' ? all.filter(run => run.taskId === route.query.taskId) : all
    const selection = runs.value.find(run => run.id === id) ?? runs.value[0]
    await select(selection?.id ?? '')
  } catch (failure) {
    if (request !== loadRequest) return
    runs.value = []
    current.value = null
    task.value = null
    error.value = errorText(failure)
  }
}
async function loadDesktop() {
  localError.value = ''
  try { diagnostic.value = await getDiagnostics() }
  catch (failure) { localError.value = errorText(failure) }
}
function refresh() { return tab.value === 'desktop' ? loadDesktop() : loadJava() }
watch([() => route.query, tab], refresh, { immediate: true })
</script>

<template>
  <main class="page stack">
    <PageHeading title="运行日志" detail="查看模型、工具、缓存、耗时和 Token 用量。"><button type="button" @click="refresh">刷新</button></PageHeading>
    <div class="tabs" aria-label="日志来源">
      <button type="button" :aria-pressed="tab === 'java'" @click="tab = 'java'">Java 运行</button>
      <button type="button" :aria-pressed="tab === 'desktop'" @click="tab = 'desktop'">桌面诊断</button>
    </div>
    <section v-if="tab === 'desktop'" class="panel stack">
      <p v-if="localError" class="error" role="alert">{{ localError }}</p>
      <p v-if="!diagnostic.length" class="empty">暂无桌面诊断。</p>
      <div v-else class="table-wrap">
        <table>
          <thead><tr><th>时间</th><th>来源 / 操作</th><th>结果</th><th>耗时</th><th>HTTP</th><th>Trace</th></tr></thead>
          <tbody><tr v-for="(entry, index) in diagnostic" :key="index">
            <td>{{ new Date(entry.timestampMs).toLocaleString() }}</td>
            <td>{{ entry.layer }} / {{ entry.operation }}
              <details v-if="entry.workspace">
                <summary>{{ entry.workspace.path || '本轮工作区授权' }}</summary>
                <div>{{ entry.workspace.write ? '读写' : '只读' }} · {{ entry.workspace.commands ? '允许运行命令' : '禁止运行命令' }}</div>
                <div>请求 <code>{{ entry.workspace.requestId }}</code></div>
                <div v-if="entry.workspace.containerId">容器 <code>{{ entry.workspace.containerId.slice(0, 12) }}</code></div>
              </details>
            </td><td>{{ entry.outcome }}</td>
            <td>{{ entry.elapsedMs }} ms</td><td>{{ entry.httpStatus ?? '—' }}</td>
<td><RouterLink v-if="entry.traceId" :to="{ path: '/agent-runs', query: { traceId: entry.traceId } }" @click="tab = 'java'">{{ entry.traceId.slice(0, 8) }}</RouterLink></td>
          </tr></tbody>
        </table>
      </div>
    </section>
    <div v-else class="split">
      <section class="panel stack" aria-label="运行列表">
        <p v-if="error" class="error" role="alert">{{ error }}</p><details v-if="task && !current" open><summary>{{ task.type }} · {{ task.status }}</summary><p>{{ api.taskText(task) }}</p></details><h2>{{ route.query.traceId ? 'Trace 调用链' : route.query.taskId ? '关联任务' : '最近运行' }}</h2>
        <RouterLink v-if="route.query.taskId || route.query.traceId" to="/agent-runs">查看全部运行</RouterLink>
        <button v-for="run in runs" :key="run.id" type="button" class="item-button" :aria-pressed="current?.id === run.id" @click="select(run.id)">
          <strong>{{ run.kind }}</strong> <span class="badge">{{ run.status }}</span><br /><small>{{ run.elapsedMs }} ms · {{ run.eventCount }} 条事件</small>
        </button>
        <p v-if="!runs.length" class="empty">暂无运行记录。</p>
      </section>
      <section v-if="current" class="panel stack" aria-label="运行详情">
        <div class="row spread"><h2>{{ current.kind }}</h2><span class="badge">{{ current.status }}</span></div>
        <p class="muted">{{ new Date(current.startedAt).toLocaleString() }} · {{ current.elapsedMs }} ms</p>
        <p>输入 {{ current.inputTokens ?? tokens.input }} Token · 输出 {{ current.outputTokens ?? tokens.output }} Token · 总计 {{ current.totalTokens ?? (tokens.input + tokens.output) }} Token</p>
        <RouterLink v-if="current.traceId && !route.query.traceId" :to="{ path: '/agent-runs', query: { traceId: current.traceId, runId: current.id } }">查看 Trace 调用链</RouterLink>
        <p v-if="route.query.traceId" class="muted">Trace ID <code>{{ route.query.traceId }}</code> · 全链路 {{ traceTokens }} Token</p>
        <p>缓存命中 {{ hits }} 次 · 未命中 {{ misses }} 次</p>
        <details v-if="task"><summary>{{ task.type }} · {{ task.status }}</summary><p>{{ api.taskText(task) }}</p></details>
        <h3>执行时间线</h3>
        <div class="tabs" aria-label="筛选事件类型"><button v-for="kind in kinds" :key="kind" type="button" :aria-pressed="eventKind === kind" @click="eventKind = kind">{{ kind || "全部" }}</button></div>
        <div v-if="events.length" class="table-wrap">
          <table>
            <thead><tr><th>类型 / 调用</th><th>状态</th><th>耗时</th><th>Token 入 / 出</th></tr></thead>
            <tbody><tr v-for="(event, index) in events" :key="index"><td>{{ event.kind }} · {{ event.name }}</td><td>{{ event.status }}</td><td>{{ event.elapsedMs }} ms</td><td>{{ event.inputTokens ?? '—' }} / {{ event.outputTokens ?? '—' }}</td></tr></tbody>
          </table>
        </div>
        <p v-else class="empty">当前筛选条件下没有事件。</p>
      </section>
    </div>
  </main>
</template>
