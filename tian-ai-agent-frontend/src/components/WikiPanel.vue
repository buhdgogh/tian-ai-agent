<template>
  <div class="wiki-overlay" @click.self="$emit('close')">
    <section class="wiki-panel" role="dialog" aria-modal="true" aria-labelledby="wiki-title" @keydown.esc="$emit('close')">
      <header><div><h2 id="wiki-title">LLM Wiki 知识编译</h2><p>资料理解、主题归纳与版本审核 · 已发布且来源有效的内容自动参与问答</p></div><button aria-label="关闭Wiki" @click="$emit('close')">×</button></header>
      <div class="wiki-toolbar">
        <select v-model="docId" aria-label="选择编译文档"><option value="">选择已入库资料</option><option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.name }}</option></select>
        <button :disabled="busy || !docId" @click="compile(docId)">编译 / 重新提交</button>
        <button :disabled="busy" @click="refresh">刷新</button><button :disabled="busy" @click="lint">引用检查</button>
      </div>
      <p v-if="error" class="wiki-error" role="alert">{{ error }}</p><p v-if="notice" class="wiki-notice" role="status">{{ notice }}</p>
      <nav><button :class="{active: tab === 'pages'}" @click="tab = 'pages'">知识页面 {{ pages.length }}</button><button :class="{active: tab === 'jobs'}" @click="tab = 'jobs'">编译任务 {{ jobs.length }}</button><button :class="{active: tab === 'events'}" @click="tab = 'events'">操作记录</button></nav>
      <div v-if="tab === 'pages'" class="wiki-workspace">
        <aside><input v-model="search" placeholder="搜索页面名称" aria-label="搜索页面名称"/><button v-for="page in filteredPages" :key="page.page_id" class="wiki-page-link" :class="{chosen: selectedPageId === page.page_id}" @click="openPage(page.page_id)"><strong>{{ page.title }}</strong><span>{{ kindLabel(page.kind) }} · {{ stateLabel(page.status) }}</span></button><p v-if="!pages.length">暂无知识页面。上传资料后自动编译；也可以选择资料手动提交。</p></aside>
        <article v-if="revision">
          <div class="wiki-article-head"><div><h3>{{ revision.page.title }}</h3><p>{{ stateLabel(revision.status) }} · {{ revision.created_at }}</p></div><button :disabled="busy || revision.status === 'STALE'" @click="publish">审核并发布此版本</button></div>
          <label class="wiki-version">历史版本<select :value="revision.revision_id" @change="openRevision($event.target.value)"><option v-for="item in history" :key="item.revision_id" :value="item.revision_id">{{ item.created_at }} · {{ stateLabel(item.status) }}</option></select></label>
          <p class="wiki-review-hint">请先核对归纳结论、适用条件与原文引用。引用检查能验证来源，语义正确性仍需人工审核。</p>
          <div class="wiki-tags"><span v-for="tag in revision.page.tags || []" :key="tag">#{{ tag }}</span></div>
          <div class="wiki-markdown" @click="followWikiLink" v-html="renderedMarkdown"></div>
          <h4>原文依据</h4><details v-for="(claim, index) in revision.page.claims" :id="'wiki-claim-' + (index + 1)" :key="index"><summary>[{{ index + 1 }}] {{ claim.title }} · {{ documentName(claim.doc_id) }}</summary><p>{{ claim.statement }}</p><blockquote>{{ claim.quote }}</blockquote><small>片段：{{ claim.node_id }}<br/>来源版本：{{ claim.version }}</small><p><button :disabled="busy" @click="openSource(claim)">查看此版本原文快照</button></p></details>
          <details v-if="sourceSnapshot" open class="wiki-source-snapshot"><summary>{{ sourceSnapshot.title }} · 原文快照</summary><pre>{{ sourceSnapshot.markdown || (sourceSnapshot.chunks || []).map(c => c.text).join('\n\n') }}</pre></details>
          <h4>关联知识</h4><div class="wiki-links"><button v-for="link in revision.page.links" :key="link" @click="openPage(link)">{{ pages.find(p => p.page_id === link)?.title || link }}</button></div>
          <h4>反向链接</h4><div v-if="pageMetadata?.revision_id === revision.revision_id" class="wiki-links"><button v-for="link in pageMetadata.backlinks || []" :key="link.page_id" @click="openPage(link.page_id)">{{ link.title }}</button><small v-if="!pageMetadata.backlinks?.length">暂无页面引用此页。</small></div><small v-else>历史版本保留原始出链，反向链接以当前页面版本为准。</small>
        </article><article v-else class="wiki-empty">选择左侧页面，查看预编译内容、引用和历史版本。</article>
      </div>
      <div v-else-if="tab === 'jobs'" class="wiki-table-wrap"><table><thead><tr><th>资料</th><th>状态 / 进度</th><th>尝试</th><th>Token（输入 / 输出）</th><th>错误 / 操作</th></tr></thead><tbody><tr v-for="job in jobs" :key="job.job_id"><td>{{ documentName(job.doc_id) }}<small>{{ job.job_id }}</small></td><td>{{ stateLabel(job.status) }} · {{ job.progress }}%</td><td>{{ job.attempt }}</td><td>{{ job.input_tokens }} / {{ job.output_tokens }}</td><td>{{ job.error_code }}<button v-if="['FAILED','BLOCKED','SUPERSEDED'].includes(job.status)" :disabled="busy" @click="compile(job.doc_id)">重新提交</button></td></tr></tbody></table><p v-if="!jobs.length">尚无编译任务。</p><p class="wiki-review-hint">未配置模型会显示“等待配置”。在文档 MCP 的 yml 配置 chat.api-key 后重启，再重新提交。</p></div>
      <div v-else class="wiki-table-wrap"><table><thead><tr><th>时间</th><th>操作</th><th>对象</th><th>操作者</th></tr></thead><tbody><tr v-for="event in events" :key="event.EVENT_ID"><td>{{ event.CREATED_AT }}</td><td>{{ event.EVENT_TYPE }}</td><td>{{ event.SUBJECT }}</td><td>{{ event.ACTOR }}</td></tr></tbody></table></div>
      <footer>Wiki 为资料的预编译理解层。草稿和过期内容不会用于正式问答；缺少可用页面时使用原文检索。</footer>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import request from '../api'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({ documents: { type: Array, default: () => [] } })
defineEmits(['close'])
const pages = ref([]), jobs = ref([]), events = ref([]), history = ref([]), revision = ref(null)
const sourceSnapshot = ref(null)
const pageMetadata = ref(null)
const renderedMarkdown = computed(() => {
  const markdown = (revision.value?.page?.markdown || '').replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, '')
  const html = DOMPurify.sanitize(marked.parse(markdown))
  const root = document.createElement('div'); root.innerHTML = html
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode)
  for (const node of nodes) {
    if (node.parentElement?.closest('pre,code,a')) continue
    const regex = /\[\[([^\]\n]+)\]\]|\[(\d+)\]/g
    const matches = [...node.textContent.matchAll(regex)]; if (!matches.length) continue
    const fragment = document.createDocumentFragment(); let offset = 0
    for (const match of matches) {
      fragment.append(document.createTextNode(node.textContent.slice(offset, match.index)))
      if (match[1]) {
        const [rawTarget, label] = match[1].split('|', 2); const target = rawTarget.split('#', 1)[0].trim()
        const button = document.createElement('button'); button.type = 'button'; button.dataset.wikiTarget = target
        button.textContent = label || pages.value.find(p => p.page_id === target)?.title || target; fragment.append(button)
      } else {
        const link = document.createElement('a'); link.href = '#wiki-claim-' + match[2]; link.textContent = '[' + match[2] + ']'; fragment.append(link)
      }
      offset = match.index + match[0].length
    }
    fragment.append(document.createTextNode(node.textContent.slice(offset))); node.replaceWith(fragment)
  }
  return root.innerHTML
})
const followWikiLink = event => { const button = event.target.closest('button[data-wiki-target]'); if (button) openPage(button.dataset.wikiTarget) }
const tab = ref('pages'), docId = ref(''), search = ref(''), selectedPageId = ref('')
const busy = ref(false), error = ref(''), notice = ref('')
const base = '/knowledge-base/wiki'
const filteredPages = computed(() => pages.value.filter(page => page.title.toLowerCase().includes(search.value.toLowerCase())))
const kindLabel = kind => ({ ENTITY: '实体', CONCEPT: '概念', SYNTHESIS: '综合分析', SOURCE: '来源综合', TOPIC: '概念' }[kind] || kind)
const stateLabel = state => ({ DRAFT: '待审核', PUBLISHED: '已发布', STALE: '已失效', QUEUED: '排队中', RUNNING: '编译中', RETRY: '等待重试', SUCCEEDED: '编译完成', FAILED: '失败', BLOCKED: '等待配置', SUPERSEDED: '版本已替换' }[state] || state)
const documentName = id => props.documents.find(doc => doc.id === id)?.name || id
const message = err => err.response?.data?.message || err.response?.data?.error || err.message || 'Wiki请求失败'
const action = async fn => { if (busy.value) return; busy.value = true; error.value = ''; try { await fn() } catch (err) { error.value = message(err) } finally { busy.value = false } }
const load = async () => {
  const [p, j, e] = await Promise.all([request.get(base + '/pages'), request.get(base + '/jobs'), request.get(base + '/events')])
  pages.value = p.data.pages || []; jobs.value = j.data.jobs || []; events.value = e.data.events || []
  const selectedId = revision.value?.revision_id
  if (selectedId) {
    const current = await request.get(base + '/revisions/' + encodeURIComponent(selectedId))
    if (revision.value?.revision_id === selectedId) revision.value = current.data
    const graph = await request.get(base + '/pages/' + encodeURIComponent(current.data.page_id) + '/content')
    if (revision.value?.revision_id === selectedId) pageMetadata.value = graph.data
  }
}
const refresh = () => action(load)
let pageRequest = 0
const openPage = async id => {
  const sequence = ++pageRequest
  error.value = ''
  try {
    const metadata = await request.get(base + '/pages/' + encodeURIComponent(id) + '/content')
    const response = await request.get(base + '/pages/' + encodeURIComponent(metadata.data.page_id))
    if (sequence !== pageRequest) return
    selectedPageId.value = metadata.data.page_id; pageMetadata.value = metadata.data; history.value = response.data.history || []; revision.value = history.value[0] || null; sourceSnapshot.value = null
  } catch (err) { if (sequence === pageRequest) error.value = message(err) }
}
const openRevision = id => { const item = history.value.find(r => r.revision_id === id); if (item) { revision.value = item; sourceSnapshot.value = null } }
const openSource = claim => action(async () => { const response = await request.get(base + '/sources/' + encodeURIComponent(claim.doc_id) + '/' + encodeURIComponent(claim.version)); sourceSnapshot.value = response.data })
const compile = id => action(async () => { await request.post(base + '/compile', { docId: id }); notice.value = '编译任务已提交，可在“编译任务”查看进度。'; tab.value = 'jobs'; await load() })
const publish = () => {
  const selected = revision.value
  if (!selected || !window.confirm('已核对本版本的归纳内容、适用条件和原文引用，确认发布？')) return
  return action(async () => { await request.post(base + '/pages/' + selected.page_id + '/publish', { revisionId: selected.revision_id }); notice.value = '已发布，后续问答将使用这个有效版本。'; await load(); await openPage(selected.page_id) })
}
const lint = () => action(async () => { const response = await request.post(base + '/lint'); notice.value = response.data.passed ? '来源与链接检查通过。发布前仍需审核语义。' : (response.data.issues || []).map(issue => issue.page_id + ': ' + issue.issue).join('；') })
let timer, disposed = false
onMounted(async () => { await refresh(); if (disposed) return; timer = window.setInterval(() => { if (jobs.value.some(j => ['QUEUED','RUNNING','RETRY'].includes(j.status)) && !busy.value) refresh() }, 5000) })
onBeforeUnmount(() => { disposed = true; window.clearInterval(timer); pageRequest++ })
</script>

<style scoped>
.wiki-tags{display:flex;gap:6px;flex-wrap:wrap;margin:12px 0}.wiki-tags span{font-size:11px;background:#e5f2eb;color:#2a705d;padding:4px 8px;border-radius:5px}.wiki-markdown{font-size:14px;line-height:1.9;overflow-wrap:anywhere}.wiki-markdown :deep(h1){font-size:25px}.wiki-markdown :deep(h2){font-size:18px;margin:24px 0 10px}.wiki-markdown :deep(h3){font-size:16px}.wiki-markdown :deep(pre){padding:12px;overflow:auto;background:#f3f6f4;border-radius:8px}.wiki-markdown :deep(button){border:0;background:#e5f3ed;color:#096f5a;padding:2px 7px;border-radius:5px;cursor:pointer}.wiki-markdown :deep(a){color:#08786c}.wiki-markdown :deep(table){border-collapse:collapse;width:100%}.wiki-markdown :deep(td),.wiki-markdown :deep(th){padding:6px;border:1px solid #dce6e0}
.wiki-source-snapshot pre{max-height:360px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.7}
.wiki-overlay{position:fixed;inset:0;z-index:150;background:#102e35a6;display:flex;align-items:center;justify-content:center;padding:24px}.wiki-panel{width:min(1220px,96vw);height:90vh;display:flex;flex-direction:column;background:#fff;border:1px solid #d7e5e2;border-radius:18px;overflow:hidden;box-shadow:0 24px 80px #082d4440;color:#294b49}.wiki-panel header{display:flex;justify-content:space-between;gap:16px;align-items:center;padding:18px 24px;background:#f3f9f7}.wiki-panel h2{font-size:20px;margin:0}.wiki-panel header p{font-size:12px;color:#64807b;margin:6px 0 0}.wiki-panel button{padding:8px 12px;border:1px solid #cadeda;border-radius:8px;background:white;color:#126e62;cursor:pointer;font:inherit}.wiki-panel button:disabled{opacity:.45;cursor:not-allowed}.wiki-toolbar{display:flex;gap:8px;padding:14px 24px;flex-wrap:wrap}.wiki-panel select,.wiki-panel input{padding:8px;border:1px solid #ccdbd7;border-radius:7px;background:white;color:#294b49;max-width:100%}.wiki-toolbar select{width:300px}.wiki-panel nav{display:flex;gap:6px;padding:0 24px 12px;border-bottom:1px solid #e5eeeb}.wiki-panel nav .active{background:#0b7e70;color:white}.wiki-error,.wiki-notice{margin:0 24px 10px;padding:10px 12px;border-radius:8px;font-size:13px;overflow-wrap:anywhere;max-height:100px;overflow:auto}.wiki-error{background:#fff0ee;color:#ab322d}.wiki-notice{background:#eef8f2;color:#286641}.wiki-workspace{display:grid;grid-template-columns:260px 1fr;flex:1;min-height:0}.wiki-workspace aside{padding:14px;overflow:auto;border-right:1px solid #e3edeb;background:#f9fbfa}.wiki-workspace aside input{width:100%;box-sizing:border-box;margin-bottom:10px}.wiki-page-link{width:100%;text-align:left;margin-bottom:6px}.wiki-page-link strong,.wiki-page-link span{display:block;overflow-wrap:anywhere}.wiki-page-link span{font-size:11px;color:#68827b;margin-top:5px}.wiki-page-link.chosen{background:#e5f4ef;border-color:#438f7f}.wiki-workspace article{padding:24px 30px;overflow:auto}.wiki-article-head{display:flex;gap:14px;align-items:start;justify-content:space-between}.wiki-panel h3{margin:0;font-size:23px}.wiki-article-head p,.wiki-version{font-size:11px;color:#6e837e}.wiki-version{display:flex;align-items:center;gap:10px;margin:12px 0}.wiki-review-hint{background:#f5f8ed;padding:10px 12px;font-size:12px;color:#63713c;line-height:1.6}.wiki-section{border-bottom:1px solid #edf1ef;padding:6px 0 16px;margin-bottom:14px}.wiki-panel h4{font-size:15px;margin:16px 0 8px}.wiki-section p,.wiki-panel details p{font-size:14px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere}.wiki-section a{font-size:12px;margin-right:6px;color:#08786c}.wiki-panel details{padding:12px;border:1px solid #dce9e4;border-radius:8px;margin:8px 0;scroll-margin:20px}.wiki-panel summary{font-size:12px;cursor:pointer}.wiki-panel blockquote{margin:12px 0;padding:8px 12px;border-left:3px solid #459b84;background:#f4faf7;white-space:pre-wrap;font-size:12px;line-height:1.7}.wiki-panel small{font-size:10px;color:#7a8e88;overflow-wrap:anywhere}.wiki-links{display:flex;gap:6px;flex-wrap:wrap}.wiki-empty{color:#7b928b;font-size:14px}.wiki-table-wrap{flex:1;min-height:0;overflow:auto;padding:20px}.wiki-panel table{width:100%;border-collapse:collapse;font-size:12px;table-layout:fixed}.wiki-panel td,.wiki-panel th{padding:12px;text-align:left;border-bottom:1px solid #e3ece8;overflow-wrap:anywhere}.wiki-panel td small{display:block;margin-top:6px}.wiki-panel th{background:#f4f9f6}.wiki-panel footer{padding:12px 24px;background:#f6faf8;border-top:1px solid #e3ece8;color:#73877e;font-size:11px}@media(max-width:700px){.wiki-overlay{padding:8px}.wiki-panel{height:96vh;width:100%}.wiki-workspace{grid-template-columns:140px 1fr}.wiki-workspace article{padding:14px}.wiki-article-head{flex-direction:column}.wiki-toolbar{padding:10px}.wiki-panel header{padding:14px}.wiki-panel h2{font-size:16px}.wiki-toolbar select{width:100%}.wiki-panel nav{padding-left:10px}.wiki-panel table{min-width:650px}}
</style>
