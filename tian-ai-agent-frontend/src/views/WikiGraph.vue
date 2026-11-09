<template>
  <section class="wiki ui-page">
    <header class="ui-page-heading">
      <div><span class="ui-kicker">Wiki</span><h1>知识图谱</h1><p>由 Agent 从原始资料编译实体、概念与综合页。</p></div>
      <div class="toolbar">
        <label for="wiki-space">知识库</label>
        <select id="wiki-space" v-model="spaceId" class="ui-field">
          <option value="">请选择知识库</option>
          <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option>
        </select>
        <button class="ui-button primary" type="button" :disabled="!spaceId || submitting || taskActive" @click="startCompile">
          {{ taskActive ? '编译中…' : graph?.builtAt ? '重新编译' : '编译 Wiki' }}
        </button>
        <button class="ui-button" type="button" :disabled="!spaceId || loading" @click="load">
          <WorkspaceIcon name="refresh" /> 刷新
        </button>
      </div>
    </header>

    <div v-if="task" class="ui-notice compile-status" :class="{ error: task.status === 'FAILED' }" role="status">
      <span>{{ task.error || task.result?.consolidationWarning || (task.stage || task.status) + ' · ' + (task.progress ?? 0) + '%' }}</span>
      <RouterLink :to="{ path: '/agent-runs', query: { taskId: task.id } }">编译日志</RouterLink>
    </div>
    <p v-if="graph?.consolidationWarning && graph.consolidationWarning !== task?.result?.consolidationWarning" class="ui-notice compile-status" role="status">{{ graph.consolidationWarning }}</p>
    <p v-if="graph?.stale" class="ui-notice compile-status">原始资料已有变化，请重新编译 Wiki。</p>
    <p v-if="error" role="alert" class="ui-notice error">{{ error }}</p>
    <p v-else-if="loading" role="status" class="ui-panel empty">正在加载图谱…</p>
    <div v-else-if="!graph?.nodes.length" class="ui-panel empty">
      <WorkspaceIcon name="wiki" />
      <strong>{{ spaceId ? '此空间暂无编译后的 Wiki 页面' : '选择一个知识库' }}</strong>
      <p v-if="spaceId && graph?.sourceCount">当前有 {{ graph.sourceCount }} 份可用资料。点击「编译 Wiki」生成页面和引用。</p>
      <p v-else>请先导入原始资料并等待解析完成。</p>
    </div>
    <div v-else class="workspace" :class="{ inspecting: selectedPage }">
      <div class="ui-panel canvas">
        <div class="canvas-toolbar">
          <div class="graph-tabs" aria-label="图谱范围">
            <button type="button" :aria-pressed="!localGraph" @click="localGraph = false">全局</button>
            <button type="button" :aria-pressed="localGraph" :disabled="!selected" @click="localGraph = true">邻近图</button>
          </div>
          <div class="graph-search">
            <input v-model="search" type="search" aria-label="查找 Wiki 页面" placeholder="查找页面"
              @keydown.enter.prevent="pickMatch(matches[0])" @keydown.esc="search = ''">
            <div v-if="search.trim()" class="search-results">
              <button v-for="node in matches" :key="node.id" type="button" @click="pickMatch(node)">
                <span>{{ node.title }}</span><small>{{ kindLabel(node.kind) }}</small>
              </button>
              <p v-if="!matches.length">没有匹配的页面</p>
            </div>
          </div>
        </div>
        <WikiNetwork :nodes="visibleNodes" :edges="visibleEdges" :selected="selected" :kinds="kinds" @pick="pick" />
        <footer class="graph-footer">
          <div class="legend">
            <span v-for="(kind, key) in kinds" :key="key"><i :style="{ background: kind.color }"></i>{{ kind.label }}</span>
          </div>
          <span>{{ visibleNodes.length }} 个页面 · {{ visibleEdges.length }} 条引用<span v-if="graph.truncated"> · 引用上限 600</span></span>
        </footer>
      </div>

      <aside v-if="selectedPage" class="ui-panel inspector" aria-label="Wiki 页面">
        <div class="inspector-heading">
          <WorkspaceIcon name="documents" /><h2>Wiki 页面</h2>
          <button class="ui-button" type="button" @click="pick('')">关闭</button>
        </div>
        <template v-if="selectedPage">
          <h3>{{ selectedPage.title }}</h3>
          <p class="muted">{{ kindLabel(selectedPage.kind) }} · {{ selectedPage.sourceCount }} 份来源</p>
          <p v-if="!content && !pageError" class="muted" role="status">正在加载页面…</p>
          <p v-if="pageError" class="ui-notice error">{{ pageError }}</p>
          <details v-if="content" class="page-content" open>
            <summary>页面正文</summary>
            <code>{{ content.path }}</code>
            <div class="markdown" v-html="renderPage(content.markdown)"></div>
          </details>
          <p v-if="!neighbors.length" class="muted">当前图中没有关联引用。</p>
          <ul v-else>
            <li v-for="edge in neighbors" :key="edge.source + ':' + edge.target">
              <span class="ui-pill">{{ edge.source === selected ? '引用' : '被引用' }}</span>
              <button type="button" @click="pick(edge.source === selected ? edge.target : edge.source)">
                {{ byId.get(edge.source === selected ? edge.target : edge.source)?.title }}
              </button>
            </li>
          </ul>
        </template>
      </aside>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import WikiNetwork from '../components/WikiNetwork.vue'
import WorkspaceIcon from '../components/WorkspaceIcon.vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { compileWiki, getKnowledgeSpaces, getTask, getTasks, getWikiGraph, getWikiPage } from '../api'
import { apiError } from '../utils/medical'

const route = useRoute()
const spaces = ref([])
const spaceId = ref(typeof route.query.spaceId === 'string' ? route.query.spaceId : '')
const graph = ref(null)
const loading = ref(false)
const error = ref('')
const selected = ref('')
const search = ref('')
const localGraph = ref(false)
const content = ref(null)
const pageError = ref('')
const task = ref(null)
const submitting = ref(false)
const taskActive = computed(() => isActive(task.value))
let pollTimer
let taskVersion = 0
let requestVersion = 0

const isActive = value => value?.status === 'PENDING' || value?.status === 'RUNNING'
const kinds = {
  entities: { label: '实体', color: '#79c8b1' },
  concepts: { label: '概念', color: '#b8a3ff' },
  syntheses: { label: '综合', color: '#efba85' }
}
const kindLabel = kind => kinds[kind]?.label || kind
const renderPage = markdown => DOMPurify.sanitize(marked.parse(markdown || '', { async: false }), { FORBID_TAGS: ['img', 'iframe'] })

const byId = computed(() => new Map((graph.value?.nodes || []).map(node => [node.id, node])))
const selectedPage = computed(() => byId.value.get(selected.value))
const neighbors = computed(() => (graph.value?.edges || []).filter(edge => edge.source === selected.value || edge.target === selected.value))
const neighborhood = computed(() => {
  const ids = new Set([selected.value])
  for (const { source, target } of neighbors.value) {
    ids.add(source)
    ids.add(target)
  }
  return ids
})
const visibleNodes = computed(() => localGraph.value
  ? graph.value.nodes.filter(node => neighborhood.value.has(node.id))
  : graph.value.nodes)
const visibleEdges = computed(() => localGraph.value
  ? graph.value.edges.filter(edge => neighborhood.value.has(edge.source) && neighborhood.value.has(edge.target))
  : graph.value.edges)
const matches = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return query ? (graph.value?.nodes || []).filter(node => node.title.toLocaleLowerCase().includes(query)).slice(0, 8) : []
})

function pick(id) {
  selected.value = id
  if (!id) localGraph.value = false
}
function pickMatch(node) {
  if (!node) return
  pick(node.id)
  localGraph.value = true
  search.value = ''
}

async function load() {
  const version = ++requestVersion
  const scope = spaceId.value
  graph.value = null
  search.value = ''
  pick('')
  error.value = ''
  loading.value = Boolean(scope)
  if (!scope) return
  try {
    const { data } = await getWikiGraph(scope)
    if (version === requestVersion) graph.value = data
  } catch (failure) {
    if (version === requestVersion) error.value = apiError(failure)
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

async function startCompile() {
  if (!spaceId.value || submitting.value || taskActive.value) return
  const version = ++taskVersion
  submitting.value = true
  error.value = ''
  try {
    const { data } = await compileWiki(spaceId.value)
    if (version !== taskVersion) return
    task.value = data
    trackTask(version)
  } catch (failure) {
    if (version === taskVersion) error.value = apiError(failure)
  } finally {
    if (version === taskVersion) submitting.value = false
  }
}

function trackTask(version) {
  if (task.value?.status === 'COMPLETED') {
    load()
    return
  }
  if (!taskActive.value) return
  const id = task.value.id
  pollTimer = setTimeout(async () => {
    try {
      const { data } = await getTask(id)
      if (version !== taskVersion) return
      task.value = data
      trackTask(version)
    } catch (failure) {
      if (version !== taskVersion) return
      task.value = null
      error.value = apiError(failure)
    }
  }, 1700)
}

watch([spaceId, selected], async ([space, id], _, onCleanup) => {
  content.value = null
  pageError.value = ''
  if (!space || !id) return
  let current = true
  onCleanup(() => { current = false })
  try {
    const { data } = await getWikiPage(space, id)
    if (current) content.value = data
  } catch (failure) {
    if (current) pageError.value = apiError(failure)
  }
})

watch(spaceId, async scope => {
  const version = ++taskVersion
  clearTimeout(pollTimer)
  submitting.value = false
  task.value = null
  load()
  if (!scope) return
  try {
    const { data } = await getTasks({ type: 'WIKI_COMPILE' })
    if (version !== taskVersion) return
    task.value = data.find(item => item.input?.spaceId === scope && isActive(item)) || null
    trackTask(version)
  } catch {
    // 图谱读取与历史任务查询可以独立使用。
  }
}, { immediate: true })
watch(() => route.query.spaceId, id => { spaceId.value = typeof id === 'string' ? id : '' })
onMounted(async () => {
  try {
    const { data } = await getKnowledgeSpaces()
    spaces.value = data.filter(space => !space.deleting)
  } catch (failure) {
    error.value = apiError(failure)
  }
})
onBeforeUnmount(() => {
  requestVersion++
  taskVersion++
  clearTimeout(pollTimer)
})
</script>

<style scoped>
.toolbar,.canvas-toolbar,.graph-tabs,.graph-footer,.legend,.inspector-heading { display:flex; align-items:center; gap:8px; }
.toolbar { flex-wrap:wrap; max-width:100%; color:var(--muted); font-size:13px; }
.toolbar select { max-width:min(240px, 70vw); }
.toolbar svg,.inspector-heading svg { width:16px; height:16px; }
.workspace { display:grid; gap:14px; grid-template-columns:minmax(0, 1fr); }
.workspace.inspecting { grid-template-columns:minmax(0, 1fr) minmax(235px, 300px); }
.canvas {
  min-width:0;
  overflow:hidden;
  border-color:#30323b;
  background:#1b1c22;
  color:#d7d9e1;
}
.canvas-toolbar,.graph-footer { justify-content:space-between; flex-wrap:wrap; padding:9px 12px; background:#24252d; }
.canvas-toolbar { border-bottom:1px solid #343640; }
.graph-footer { border-top:1px solid #343640; color:#b8bac6; font-size:12px; }
.graph-tabs { gap:3px; padding:3px; border-radius:8px; background:#1a1b21; }
.graph-tabs button {
  min-height:29px;
  padding:3px 10px;
  border:0;
  border-radius:5px;
  background:transparent;
  color:#d7d9e1;
  font-size:12px;
}
.graph-tabs button[aria-pressed="true"] { background:#414052; color:#fff; }
.graph-tabs button:disabled { opacity:.5; }
.graph-search { position:relative; z-index:1; width:min(240px, 100%); }
.graph-search input {
  width:100%;
  min-height:34px;
  padding:5px 9px;
  border:1px solid #414350;
  border-radius:7px;
  color:#f1eff8;
  background:#1a1b21;
  font:inherit;
  font-size:12px;
}
.graph-search input::placeholder { color:#aeb0bc; }
.graph-tabs button:focus-visible,.graph-search input:focus-visible,.search-results button:focus-visible {
  outline-color:#beaaff;
  outline-offset:-2px;
}
.search-results {
  position:absolute;
  top:calc(100% + 5px);
  left:0;
  right:0;
  padding:4px;
  border:1px solid #454754;
  border-radius:7px;
  background:#262830;
  box-shadow:0 10px 24px #0006;
}
.search-results button {
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:5px;
  width:100%;
  padding:7px;
  border:0;
  border-radius:4px;
  color:#f1eff8;
  background:transparent;
  text-align:left;
  font-size:12px;
}
.search-results button:hover { background:#3a3b49; }
.search-results button span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.search-results button small { flex:none; color:#c1bfd0; }
.search-results p { margin:0; padding:7px; color:#c1bfd0; font-size:12px; }
.legend { gap:12px; }
.legend span { display:inline-flex; align-items:center; gap:5px; }
.legend i { width:7px; height:7px; border-radius:50%; }
.compile-status { display:flex; justify-content:space-between; gap:12px; margin-bottom:10px; }
.page-content { max-height:34vh; overflow:auto; margin:8px 0; padding:8px; border:1px solid var(--line); border-radius:8px; font-size:12px; overflow-wrap:anywhere; }
.page-content code { color:var(--muted); font-size:10px; }
.markdown :deep(h1),.markdown :deep(h2),.markdown :deep(h3) { margin:6px 0; font-size:13px; }
.markdown :deep(ul),.markdown :deep(ol) { padding-left:16px; }
.markdown :deep(img) { max-width:100%; }
.inspector { align-self:start; padding:14px; }
.inspector-heading { margin-bottom:12px; color:var(--accent-ink); }
.inspector-heading .ui-button { margin-left:auto; min-height:28px; padding:4px 8px; font-size:12px; }
.inspector h2 { font-size:14px; }
.inspector h3 { margin-bottom:8px; font-size:14px; overflow-wrap:anywhere; }
.inspector ul { display:grid; gap:6px; list-style:none; }
.inspector li { display:flex; align-items:center; gap:7px; font-size:12px; }
.inspector li button { border:0; background:transparent; color:var(--accent-ink); text-align:left; overflow-wrap:anywhere; }
.muted { color:var(--muted); font-size:12px; }
.empty { display:grid; justify-items:center; gap:7px; padding:55px 16px; color:var(--muted); }
.empty svg { width:30px; height:30px; color:var(--accent); }
@media (max-width:830px) { .workspace.inspecting { grid-template-columns:1fr; } }
</style>
