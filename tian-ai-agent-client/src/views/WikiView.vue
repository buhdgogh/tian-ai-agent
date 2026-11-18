<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as api from '../api/workbench'
import { errorText } from '../api/java'
import PageHeading from '../components/PageHeading.vue'
import WikiNetwork from '../components/WikiNetwork.vue'

const spaces = ref<api.Space[]>([])
const spaceId = ref('')
const graph = ref<api.WikiGraph>({ nodes: [], edges: [] })
const selectedId = ref('')
const selected = ref<api.WikiPage | null>(null)
const search = ref('')
const neighborhood = ref(false)
const task = ref<api.TaskRecord>()
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const pageError = ref('')
const kinds = ['实体', '概念', '综合']
let loadVersion = 0
let pickVersion = 0
let polling: AbortController | undefined

const byId = computed(() => new Map(graph.value.nodes.map(node => [node.id, node])))
const matches = computed(() => graph.value.nodes.filter(page => page.title.toLowerCase().includes(search.value.trim().toLowerCase())).slice(0, 30))
const relationships = computed(() => selectedId.value
  ? graph.value.edges.filter(edge => edge.source === selectedId.value || edge.target === selectedId.value) : [])
const edges = computed(() => neighborhood.value ? relationships.value : graph.value.edges)
const visibleNodes = computed(() => {
  if (!neighborhood.value) return graph.value.nodes
  const ids = new Set([selectedId.value, ...relationships.value.flatMap(edge => [edge.source, edge.target])])
  return graph.value.nodes.filter(node => ids.has(node.id))
})
const selectedKind = computed(() => byId.value.get(selectedId.value)?.kind)
function title(id: string) { return byId.value.get(id)?.title ?? id }

async function pick(id: string) {
  const version = ++pickVersion
  selectedId.value = id
  selected.value = null
  pageError.value = ''
  try {
    const page = await api.getWikiPage(spaceId.value, id)
    if (version === pickVersion) selected.value = page
  } catch (failure) {
    if (version === pickVersion) pageError.value = errorText(failure)
  }
}
function clearSelection() {
  pickVersion++
  selectedId.value = ''
  selected.value = null
  pageError.value = ''
  neighborhood.value = false
}
async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    const result = spaceId.value ? await api.getWikiGraph(spaceId.value) : { nodes: [], edges: [] }
    if (version !== loadVersion) return
    graph.value = result
    if (selectedId.value && !byId.value.has(selectedId.value)) clearSelection()
  } catch (failure) {
    if (version === loadVersion) {
      graph.value = { nodes: [], edges: [] }
      clearSelection()
      error.value = errorText(failure)
    }
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
async function loadSpaces() {
  error.value = ''
  try {
    spaces.value = (await api.getKnowledgeSpaces()).filter(space => !space.deleting)
    if (!spaces.value.some(space => space.id === spaceId.value)) spaceId.value = spaces.value[0]?.id ?? ''
    else await load()
  } catch (failure) { error.value = errorText(failure) }
}
async function compile() {
  if (!spaceId.value || busy.value) return
  const context = spaceId.value
  const wait = new AbortController()
  polling = wait
  error.value = ''
  busy.value = true
  try {
    const started = await api.compileWiki(context)
    if (wait.signal.aborted) return
    task.value = started
    const result = await api.waitForTask(started, wait.signal, update => { task.value = update })
    task.value = result
    if (result.status === 'COMPLETED' && spaceId.value === context) await load()
  } catch (failure) {
    if (!wait.signal.aborted) error.value = errorText(failure)
  } finally {
    if (polling === wait) {
      busy.value = false
      polling = undefined
    }
  }
}
watch(spaceId, () => {
  polling?.abort()
  polling = undefined
  busy.value = false
  clearSelection()
  graph.value = { nodes: [], edges: [] }
  search.value = ''
  task.value = undefined
  void load()
})
onMounted(loadSpaces)
onBeforeUnmount(() => {
  loadVersion++
  pickVersion++
  polling?.abort()
})
</script>

<template>
  <main class="page wiki-page">
    <PageHeading title="Wiki 知识图谱" detail="查看 Wiki 页面之间的引用，点击节点可打开正文和来源。" />
    <div class="stack">
      <div class="panel row">
        <label class="field inline">知识库<select v-model="spaceId"><option value="">请选择知识库</option><option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option></select></label>
        <button type="button" class="primary" :disabled="!spaceId || busy" @click="compile">编译 Wiki</button>
        <button type="button" :disabled="loading" @click="loadSpaces">重新加载</button>
      </div>
      <div v-if="task" class="notice row" role="status">{{ api.taskText(task) }} <RouterLink :to="{ path: '/agent-runs', query: { taskId: task.id } }">查看编译记录</RouterLink></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="graph.stale" class="muted">原始资料已更新，可重新编译 Wiki。</p>
      <div class="wiki-layout" :class="{ 'has-selection': selectedId }">
        <section class="panel stack graph-panel" aria-label="图谱">
          <div class="row spread">
            <h2>引用关系</h2>
            <div v-if="graph.nodes.length" class="tabs">
              <button type="button" :aria-pressed="!neighborhood" @click="neighborhood = false">全局</button>
              <button type="button" :disabled="!selectedId" :aria-pressed="neighborhood" @click="neighborhood = true">邻近图</button>
            </div>
          </div>
          <template v-if="graph.nodes.length">
            <label class="field">搜索 Wiki 页面<input v-model="search" type="search" placeholder="输入页面标题" /></label>
            <div v-if="search.trim()" class="row search-results" aria-label="搜索结果">
              <button v-for="page in matches" :key="page.id" type="button" :aria-pressed="selectedId === page.id" @click="pick(page.id)">{{ page.title }} · {{ page.kind }}</button>
              <p v-if="!matches.length" class="muted">没有匹配页面。</p>
            </div>
            <WikiNetwork :nodes="visibleNodes" :edges="edges" :selected-id="selectedId" @pick="pick" />
            <div class="row spread graph-footer">
              <div class="row"><span v-for="kind in kinds" :key="kind" class="legend" :data-kind="kind">{{ kind }}</span></div>
              <span>{{ visibleNodes.length }} 个页面 · {{ edges.length }} 条引用<span v-if="graph.truncated"> · 部分引用未显示</span></span>
            </div>
          </template>
          <p v-else-if="loading" class="empty" role="status">正在加载 Wiki 图谱…</p>
          <p v-else-if="spaceId && !error" class="empty">此知识库还没有 Wiki 图谱。导入文档并完成索引后，点击「编译 Wiki」。</p>
          <p v-else-if="!error" class="empty">选择知识库后查看图谱。</p>
        </section>
        <aside v-if="selectedId" class="panel stack inspector" aria-label="Wiki 页面">
          <div class="row spread"><h2>Wiki 页面</h2><button type="button" @click="clearSelection">关闭</button></div>
          <div class="row"><h3>{{ title(selectedId) }}</h3><span v-if="selectedKind" class="badge">{{ selectedKind }}</span></div>
          <p v-if="pageError" class="error" role="alert">{{ pageError }}</p>
          <p v-else-if="!selected" class="muted" role="status">正在加载页面…</p>
          <template v-else>
            <details open><summary>页面正文</summary><pre class="wiki-text">{{ selected.content }}</pre></details>
            <details v-for="source in selected.sources" :key="source.id">
              <summary>原文引用 · {{ source.source }}</summary>
              <blockquote v-if="source.excerpt">{{ source.excerpt }}</blockquote><RouterLink v-else :to="{ path: '/knowledge-base', query: { spaceId } }">打开来源所在知识库</RouterLink>
            </details>
            <div v-if="relationships.length" class="row">
              <button v-for="edge in relationships" :key="edge.source + ':' + edge.target" type="button" @click="pick(edge.source === selectedId ? edge.target : edge.source)">
                {{ title(edge.source === selectedId ? edge.target : edge.source) }}
              </button>
            </div>
          </template>
        </aside>
      </div>
    </div>
  </main>
</template>

<style scoped>
.wiki-layout { display: grid; grid-template-columns: minmax(0, 1fr); gap: .8rem; align-items: start; }
.wiki-layout.has-selection { grid-template-columns: minmax(0, 1fr) minmax(215px, 290px); }
.graph-panel, .inspector { min-width: 0; }
.inspector { overflow-wrap: anywhere; }
.search-results { max-height: 8rem; overflow: auto; }
.graph-footer { color: var(--muted); font-size: .82rem; }
.legend { display: inline-flex; gap: .3rem; align-items: center; }
.legend::before { content: ''; width: .6rem; height: .6rem; border-radius: 50%; background: #647582; }
.legend[data-kind='实体']::before { background: #176eaa; }
.legend[data-kind='概念']::before { background: #178269; }
.legend[data-kind='综合']::before { background: #8750a3; }
.wiki-text { margin: 0; font: inherit; }
@media (max-width: 800px) { .wiki-layout.has-selection { grid-template-columns: minmax(0, 1fr); } }
</style>
