<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as api from '../api/workbench'
import { errorText } from '../api/java'
import PageHeading from '../components/PageHeading.vue'

const spaces = ref<api.Space[]>([])
const spaceId = ref('')
const graph = ref<api.WikiGraph>({ nodes: [], edges: [] })
const selected = ref<api.WikiPage | null>(null)
const search = ref('')
const neighborhood = ref(false)
const task = ref<api.TaskRecord>()
const busy = ref(false)
const error = ref('')
let loadVersion = 0
let pickVersion = 0
let polling: AbortController | undefined

const matches = computed(() => graph.value.nodes.filter(page => page.title.includes(search.value.trim())))
const relationships = computed(() => !selected.value ? [] : graph.value.edges.filter(edge => edge.source === selected.value?.id || edge.target === selected.value?.id))
const edges = computed(() => neighborhood.value ? relationships.value : graph.value.edges)
function title(id: string) { return graph.value.nodes.find(node => node.id === id)?.title ?? id }
async function pick(id: string) {
  const version = ++pickVersion
  error.value = ''
  try {
    const page = await api.getWikiPage(spaceId.value, id)
    if (version === pickVersion) selected.value = page
  } catch (failure) {
    if (version === pickVersion) error.value = errorText(failure)
  }
}
function clearSelection() {
  pickVersion++
  selected.value = null
  neighborhood.value = false
}
async function load() {
  const version = ++loadVersion
  error.value = ''
  try {
    const result = spaceId.value ? await api.getWikiGraph(spaceId.value) : { nodes: [], edges: [] }
    if (version === loadVersion) graph.value = result
  } catch (failure) {
    if (version === loadVersion) {
      graph.value = { nodes: [], edges: [] }
      error.value = errorText(failure)
    }
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
  search.value = ''
  task.value = undefined
  void load()
})
onMounted(loadSpaces)
onBeforeUnmount(() => polling?.abort())
</script>

<template>
  <main class="page">
    <PageHeading title="Wiki 知识图谱" detail="浏览实体、概念和综合页，沿引用关系回溯原文。" />
    <div class="stack">
      <div class="panel row">
        <label class="field inline">知识库<select v-model="spaceId"><option value="">请选择知识库</option><option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option></select></label>
        <button type="button" class="primary" :disabled="!spaceId || busy" @click="compile">编译 Wiki</button>
        <button type="button" @click="loadSpaces">重新加载</button>
      </div>
      <div v-if="task" class="notice row" role="status">{{ api.taskText(task) }} <RouterLink :to="{ path: '/agent-runs', query: { taskId: task.id } }">查看编译记录</RouterLink></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="graph.stale" class="muted">原始资料已更新，可重新编译 Wiki。</p><div class="columns">
        <section class="panel stack" aria-label="图谱">
          <div class="row spread">
            <h2>引用关系</h2>
            <div class="tabs"><button type="button" :aria-pressed="!neighborhood" @click="neighborhood = false">全局</button><button type="button" :disabled="!selected" :aria-pressed="neighborhood" @click="neighborhood = true">邻近图</button></div>
          </div>
          <label class="field">搜索 Wiki 页面<input v-model="search" type="search" placeholder="输入页面标题" /></label>
          <div class="row">
            <button v-for="page in matches" :key="page.id" type="button" :aria-pressed="selected?.id === page.id" @click="pick(page.id)">{{ page.title }} · {{ page.kind }}</button>
          </div>
          <p v-if="!matches.length" class="muted">没有匹配页面。</p>
          <div v-for="edge in edges" :key="edge.source + '-' + edge.target" class="edge">
            <button type="button" @click="pick(edge.source)">{{ title(edge.source) }}</button>
            <span>{{ edge.label }} →</span>
            <button type="button" @click="pick(edge.target)">{{ title(edge.target) }}</button>
          </div>
        </section>
        <section class="panel stack" aria-label="Wiki 页面">
          <div class="row spread"><h2>Wiki 页面</h2><button v-if="selected" type="button" @click="clearSelection">关闭</button></div>
          <template v-if="selected">
            <div class="row"><h3>{{ selected.title }}</h3><span class="badge">{{ selected.kind }}</span></div>
            <details open><summary>页面正文</summary><pre class="wiki-text">{{ selected.content }}</pre></details>
            <details v-for="source in selected.sources" :key="source.id">
              <summary>原文引用 · {{ source.source }}</summary>
              <blockquote v-if="source.excerpt">{{ source.excerpt }}</blockquote><RouterLink v-else :to="{ path: '/knowledge-base', query: { spaceId } }">打开来源所在知识库</RouterLink>
            </details>
            <div class="row">
              <button v-for="edge in relationships" :key="edge.source + edge.target" type="button" @click="pick(edge.source === selected.id ? edge.target : edge.source)">
                {{ title(edge.source === selected.id ? edge.target : edge.source) }}
              </button>
            </div>
          </template>
          <p v-else class="empty">选择一个节点查看正文和原文摘录。</p>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
.wiki-text { margin: 0; font: inherit; }
.edge { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: .45rem; padding: .35rem; background: #eef5f3; border-radius: .45rem; }
.edge span { font-size: .82rem; color: var(--muted); }
@media (max-width: 620px) { .wiki-text { margin: 0; font: inherit; }
.edge { display: flex; flex-wrap: wrap; } }
</style>
