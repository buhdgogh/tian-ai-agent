<template>
  <section class="chat-shell" :style="{ '--chat-accent': themeColor }" @click="handleCitationClick">
    <div v-if="emergencyText" class="emergency" role="alert"><strong>紧急提示</strong><span>{{ emergencyText }}</span></div>
    <div ref="scroller" class="messages">
      <div v-if="!visibleMessages.length" class="welcome">
        <div class="welcome-icon"><WorkspaceIcon name="agent" /></div><p class="ui-kicker">Agent Workspace</p>
        <h1>{{ title }}</h1><p>{{ subtitle }}</p>
        <div class="quick-grid"><button v-for="q in quickQuestions" :key="q" class="ui-button" type="button" :disabled="generating" @click="emit('send-message', q)">{{ q }}<WorkspaceIcon name="arrow" /></button></div>
      </div>
      <article v-for="(msg,index) in visibleMessages" :key="msg.id || `${msg.time}-${index}`" class="message" :class="msg.isUser?'user':'assistant'">
        <div class="avatar"><span v-if="msg.isUser">我</span><WorkspaceIcon v-else name="agent" /></div>
        <div class="bubble">
          <div v-if="msg.task || msg.runId" class="run-links">
            <span v-if="msg.task">{{ msg.task.stage || msg.task.status }} · {{ msg.task.progress ?? 0 }}%</span>
            <RouterLink v-if="msg.runId" :to="{ path: '/agent-runs', query: { runId: msg.runId } }">运行日志</RouterLink>
            <RouterLink v-if="msg.task?.id" :to="{ path: '/agent-runs', query: { taskId: msg.task.id } }">工作流日志</RouterLink>
          </div>
          <div v-if="msg.incomplete" class="note muted">生成已停止 · 以下为不完整内容</div>
          <details v-if="msg.trace?.length" class="agent-trace" :open="generating && index === visibleMessages.length - 1">
            <summary><span class="trace-mark">⌁</span><strong>执行路径</strong><small>{{ msg.trace.length }} 个步骤</small></summary>
            <ol>
              <li v-for="(step,stepIndex) in msg.trace" :key="step.id || `${step.type}-${stepIndex}`" :class="'trace-'+step.type">
                <i>{{ traceIcon(step.type) }}</i><span><strong>{{ step.title }}</strong><small v-if="step.detail">{{ step.detail }}</small><code v-if="step.tool">{{ step.tool }}</code></span><em>{{ ({ failed:'失败', called:'已调用', completed:'已完成' })[step.status] || '已记录' }}</em>
              </li>
            </ol>
          </details>
          <div v-if="String(msg.content||'').trim()" class="markdown" v-html="msg.isUser ? md(msg.content) : citedMd(msg.content, msg.sources, 'chat-source-' + index)"></div>
          <section v-if="medical && msg.report" class="report">
            <header><strong>结构化循证安全报告</strong><span :class="'risk '+riskClass(msg.report.riskLevel)">{{ riskLabel(msg.report.riskLevel) }}</span></header>
            <div v-for="part in reportParts(msg.report)" :key="part.title" class="report-part"><h3>{{ part.title }}</h3><div class="markdown" v-html="md(part.value)"></div></div>
            <p v-if="msg.report.requiresProfessionalReview||msg.report.professionalReviewRequired||msg.report.requiresHumanReview" class="note warn">需要医生或药师审核，不能视为 AI 已确认安全。</p>
          </section>
          <section v-if="msg.ragMeta" class="rag-quality">
            <header><strong>回答依据</strong><div class="quality-badges"><span v-if="medical && msg.ragMeta.riskLevel" :class="'risk '+riskClass(msg.ragMeta.riskLevel)">{{ riskLabel(msg.ragMeta.riskLevel) }}</span><span v-if="msg.ragMeta.validation?.status" :class="'validation validation-'+msg.ragMeta.validation.status">{{ validationLabel(msg.ragMeta.validation.status) }}</span></div></header>
            <div v-if="msg.ragMeta.mode || msg.ragMeta.level || msg.ragMeta.reason" class="rag-plan"><span>{{ [msg.ragMeta.mode, msg.ragMeta.level, msg.ragMeta.reason].filter(Boolean).join(' · ') }}</span></div>
            <div v-if="msg.ragMeta.plan" class="rag-plan"><span>回答类型：{{ msg.ragMeta.plan.answer_type || '未标注' }}</span><span v-if="msg.ragMeta.plan.sections?.length">结构：{{ msg.ragMeta.plan.sections.join(' / ') }}</span></div>
            <div v-if="msg.ragMeta.validation?.citation_integrity" class="coverage"><span>引用覆盖率</span><b>{{ score(msg.ragMeta.validation.citation_integrity.citation_coverage) }}</b><i><em :style="{width:score(msg.ragMeta.validation.citation_integrity.citation_coverage)}"></em></i></div>
            <div v-if="ragWarnings(msg.ragMeta.validation).length" class="rag-warnings"><strong>需要注意</strong><ul><li v-for="warning in ragWarnings(msg.ragMeta.validation)" :key="warning">{{ warning }}</li></ul></div>
            <p v-if="needsRagReview(msg.ragMeta)" class="note warn">{{ medical ? '该回答需要医生或药师复核后再用于医疗判断。' : '当前资料需要人工复核。' }}</p>
            <small v-if="msg.ragMeta.retrieval">检索模式：{{ msg.ragMeta.retrieval.mode || 'unknown' }} · 稠密候选 {{ msg.ragMeta.retrieval.dense_candidates || 0 }} · BM25 候选 {{ msg.ragMeta.retrieval.bm25_candidates || 0 }}</small>
          </section>
          <p v-if="msg.evidenceEmpty" class="note warn">所选知识库没有返回相关片段。</p>
          <section v-if="msg.sources?.length" class="sources">
            <h3>引用来源 · {{ msg.sources.length }}</h3>
            <details v-for="(source,i) in msg.sources" :id="'chat-source-' + index + '-' + (source.citation||i+1)" :key="source.chunkId||source.id||i">
              <summary><b>{{ source.citation||i+1 }}</b><span><strong>{{ source.title||source.fileName||'未命名来源' }}</strong><small v-if="medical">{{ source.issuer||'发布机构未标注' }} · {{ source.version||'版本未标注' }}</small></span><em v-if="medical">{{ source.reviewStatus||source.effectiveStatus||'待核验' }}</em></summary>
              <div class="source-detail"><div><span v-if="medical">发布日期：{{ source.publishedAt||'未标注' }}</span><span v-if="medical">适用人群：{{ source.audience||'未标注' }}</span><span v-if="source.page">页码：{{ source.page }}</span><span v-if="source.score!=null">相关度：{{ score(source.score) }}</span><a v-if="sourceLink(source)" :href="sourceLink(source)" target="_blank" rel="noopener noreferrer">查看原文 ↗</a></div><blockquote v-if="source.quote||source.content">{{ source.quote||source.content }}</blockquote></div>
            </details>
          </section>
          <time>{{ formatTime(msg.time) }}</time>
        </div>
      </article>
      <article v-if="generating && !emergencyText && !messages.at(-1)?.content" class="message assistant"><div class="avatar"><WorkspaceIcon name="agent" /></div><div class="bubble loading"><i></i><i></i><i></i><span>正在处理请求…</span></div></article>
    </div>
    <footer>
      <TrSender v-model="input" class="composer" :placeholder="placeholder" :loading="generating"
        :mode="$slots['composer-actions'] ? 'multiple' : 'single'"
        :auto-size="{ minRows: 1, maxRows: 5 }" @submit="send" @cancel="emit('stop-generation')">
        <template v-if="$slots['composer-actions']" #footer-right>
          <slot name="composer-actions" />
        </template>
      </TrSender>
      <p class="privacy">请勿提交个人身份信息或未获授权的敏感资料。</p>
      <p v-if="medical" class="boundary">提供健康信息与就医沟通支持，不能替代医生、药师或急救服务。</p>
    </footer>
  </section>
</template>
<script setup>
import {computed,nextTick,onMounted,ref,watch} from 'vue'
import {marked} from 'marked'
import DOMPurify from 'dompurify'
import { TrSender } from '@opentiny/tiny-robot'
import '@opentiny/tiny-robot/dist/style.css'
import WorkspaceIcon from './WorkspaceIcon.vue'
const props=defineProps({medical:Boolean,messages:{type:Array,default:()=>[]},connectionStatus:{type:String,default:'disconnected'},aiType:{type:String,default:'medical'},themeColor:{type:String,default:'#3f49ba'},themeColorRgb:{type:String,default:'8,127,140'},title:{type:String,default:'知识助手'},subtitle:{type:String,default:'选择知识库空间，按需检索文档或直接提问'},welcomeIcon:{type:String,default:'⚕'},suggestions:{type:Array,default:()=>[]},placeholder:{type:String,default:'输入问题或描述要完成的任务'},emergencyText:{type:String,default:''}})
const emit=defineEmits(['send-message','stop-generation']),input=ref(''),scroller=ref(null)
const generating=computed(()=>props.connectionStatus==='connecting')
const visibleMessages=computed(()=>props.messages.filter(msg=>String(msg?.content||'').trim()||msg?.sources?.length||msg?.report||msg?.ragMeta||msg?.trace?.length))
const quickQuestions=computed(()=>props.suggestions.length?props.suggestions:['归纳资料中的关键条款','整理信息并列出待办事项'])
const md=v=>DOMPurify.sanitize(marked.parse(String(v||''),{gfm:true,breaks:true,async:false}),{USE_PROFILES:{html:true}})
const citedMd=(value,sources,prefix)=>{
  const html=md(value)
  if(!sources?.length||typeof document==='undefined')return html
  const parsed=new DOMParser().parseFromString(html,'text/html')
  const citations=new Set(sources.map((source,index)=>String(source.citation||index+1)))
  const walker=document.createTreeWalker(parsed.body,NodeFilter.SHOW_TEXT)
  const nodes=[];let node
  while((node=walker.nextNode()))if(!node.parentElement?.closest('a,code,pre'))nodes.push(node)
  nodes.forEach(textNode=>{
    const text=textNode.nodeValue||'',pattern=/\[(\d+)\]/g
    if(!pattern.test(text))return
    pattern.lastIndex=0
    const fragment=document.createDocumentFragment();let cursor=0,match
    while((match=pattern.exec(text))){
      fragment.append(text.slice(cursor,match.index))
      if(citations.has(match[1])){const link=document.createElement('a');link.className='citation-link';link.href=`#${prefix}-${match[1]}`;link.textContent=`[${match[1]}]`;link.title=`查看来源 ${match[1]}`;fragment.append(link)}
      else fragment.append(match[0])
      cursor=pattern.lastIndex
    }
    fragment.append(text.slice(cursor));textNode.replaceWith(fragment)
  })
  return DOMPurify.sanitize(parsed.body.innerHTML,{USE_PROFILES:{html:true}})
}
const handleCitationClick=event=>{
  const link=event.target.closest?.('a.citation-link')
  if(!link)return
  const target=document.querySelector(link.getAttribute('href'))
  if(!target)return
  event.preventDefault()
  if(target.tagName==='DETAILS')target.open=true
  target.scrollIntoView({behavior:'smooth',block:'center'})
}
const send = text => {
  const value = text.trim()
  if (!value || generating.value) return
  emit('send-message', value)
  input.value = ''
}
const formatTime=v=>new Date(v||Date.now()).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})
const traceIcon=type=>({thinking:'思',tool:'调',complete:'✓',error:'!'}[type]||'·')
const score=v=>Math.round(Number(v)*(Number(v)<=1?100:1))+'%'
const riskLabel=v=>({EMERGENCY:'紧急',HIGH:'高风险',ROUTINE:'常规',MEDIUM:'中风险',LOW:'低风险'}[String(v||'').toUpperCase()]||'待评估')
const riskClass=v=>'risk-'+String(v||'unknown').toLowerCase()
const validationLabel=value=>({passed:'校验通过',revised:'已修订',needs_review:'需要复核',deterministic_only:'确定性分诊'}[value]||'未校验')
const ragWarnings=validation=>{
  if(!validation)return[]
  return [
    ...(validation.unsupported_claims||[]).map(item=>`缺少证据：${item}`),
    ...(validation.missing_citations||[]).map(item=>`缺少引用：${item}`),
    ...(validation.conflicts||[]).map(item=>`来源冲突：${item}`),
    ...(validation.citation_integrity?.invalid_citations||[]).map(item=>`无效引用：[${item}]`)
  ]
}
const sourceLink=source=>/^https?:\/\//i.test(source?.sourceUri||'')?source.sourceUri:''
const needsRagReview=meta=>meta?.requiresReview||meta?.validation?.status==='needs_review'||['HIGH','EMERGENCY'].includes(String(meta?.riskLevel||'').toUpperCase())
const stringify=v=>Array.isArray(v)?v.map(x=>'- '+(typeof x==='object'?JSON.stringify(x):x)).join('\n'):typeof v==='object'?JSON.stringify(v,null,2):String(v||'未提供')
const reportParts=r=>[['摘要',r.summary],['证据摘要',r.evidence||r.evidenceSummary],['不确定性',r.uncertainties],['危险信号',r.redFlags],['建议的下一步',r.nextSteps]].filter(x=>x[1]!=null&&x[1]!=='').map(x=>({title:x[0],value:stringify(x[1])}))
const scroll=async()=>{await nextTick();if(scroller.value)scroller.value.scrollTop=scroller.value.scrollHeight}
watch(()=>JSON.stringify(props.messages),scroll);onMounted(scroll);defineExpose({clearInput:()=>input.value=''})
</script>
<style scoped>
.chat-shell { display: flex; flex: 1; flex-direction: column; min-height: 0; color: var(--ink); background: var(--canvas); }
.messages { flex: 1; min-height: 0; overflow: auto; padding: 24px max(16px, calc((100% - 840px) / 2)); }
.welcome { max-width: 650px; margin: clamp(20px, 8vh, 90px) auto 0; text-align: center; }
.welcome-icon { display: grid; place-items: center; width: 54px; height: 54px; margin: 0 auto 17px; border: 1px solid #dce1ff; border-radius: 15px; color: var(--chat-accent); background: var(--accent-soft); }
.welcome-icon svg { width: 24px; height: 24px; }
.welcome h1 { font-size: clamp(25px, 4vw, 35px); margin: 5px 0 8px; line-height: 1.2; }
.welcome > p:not(.ui-kicker) { color: var(--muted); }
.quick-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 26px 0 0; }
.quick-grid button { display: flex; align-items: center; justify-content: space-between; gap: 9px; text-align: left; white-space: normal; }
.quick-grid button svg { flex: none; width: 15px; height: 15px; color: var(--chat-accent); }

.message { display: flex; gap: 10px; align-items: flex-start; margin: 0 0 19px; }
.message.user { flex-direction: row-reverse; }
.avatar { display: grid; place-items: center; flex: 0 0 30px; height: 30px; border-radius: 9px; color: var(--accent-ink); background: var(--accent-soft); font-size: 12px; font-weight: 750; }
.avatar svg { width: 16px; height: 16px; }
.bubble { min-width: 0; max-width: min(90%, 720px); padding: 13px 15px; border: 1px solid var(--line); border-radius: 4px 13px 13px; background: var(--surface); box-shadow: var(--shadow); line-height: 1.7; }
.user .bubble { border-color: #dfe3ff; border-radius: 13px 4px 13px 13px; background: var(--accent-soft); }
.bubble time { display: block; margin-top: 6px; color: var(--muted); font-size: 10px; }
.user time { text-align: right; }
.markdown { overflow-wrap: anywhere; }
.markdown :deep(p),.markdown :deep(ul),.markdown :deep(ol),.markdown :deep(pre),.markdown :deep(blockquote),.markdown :deep(table) { margin: 0 0 .7em; }
.markdown :deep(:last-child) { margin-bottom: 0; }
.markdown :deep(ul),.markdown :deep(ol) { padding-left: 1.4em; }
.markdown :deep(h1),.markdown :deep(h2),.markdown :deep(h3) { margin: .8em 0 .35em; font-size: 1.15em; }
.markdown :deep(code) { padding: 2px 5px; border-radius: 4px; background: rgb(90 100 150 / 10%); }
.markdown :deep(pre) { padding: 12px; overflow: auto; border-radius: 9px; color: #e7ebfa; background: #242b40; }
.markdown :deep(blockquote) { padding-left: 12px; border-left: 3px solid var(--chat-accent); }

.run-links { display: flex; flex-wrap: wrap; gap: 8px 12px; margin-bottom: 8px; color: var(--muted); font-size: 12px; }
.agent-trace,.report,.rag-quality,.sources details { margin: 0 0 9px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface-muted); }
.agent-trace summary,.report header,.rag-quality header,.sources summary { display: flex; align-items: center; gap: 8px; padding: 8px 10px; font-size: 12px; }
.agent-trace summary small { margin-left: auto; color: var(--muted); }
.trace-mark,.agent-trace li > i { display: grid; place-items: center; width: 21px; height: 21px; border-radius: 6px; color: var(--accent-ink); background: var(--accent-soft); font-style: normal; }
.agent-trace ol { list-style: none; padding: 0 10px 8px; }
.agent-trace li { display: grid; grid-template-columns: 23px 1fr auto; gap: 8px; padding: 5px 0; font-size: 12px; }
.agent-trace li small,.agent-trace li code,.agent-trace li em { color: var(--muted); font-size: 11px; }
.agent-trace li small,.agent-trace li code { display: block; overflow-wrap: anywhere; }
.agent-trace li em { font-style: normal; }
.report,.rag-quality,.sources { margin-top: 10px; }
.report header,.rag-quality header { justify-content: space-between; }
.report-part,.rag-plan,.coverage,.rag-warnings,.source-detail { padding: 8px 10px; }
.report-part h3,.sources > h3 { color: var(--accent-ink); font-size: 12px; margin-bottom: 5px; }
.rag-warnings ul { padding-left: 16px; }
.coverage { display: flex; gap: 6px; }
.quality-badges { display: flex; gap: 4px; }
.risk,.validation { padding: 2px 6px; border-radius: 8px; background: var(--accent-soft); font-size: 11px; }
.risk-emergency,.risk-high,.validation-needs_review { background: var(--danger-soft); color: var(--danger); }
.sources summary b { padding: 0 6px; border-radius: 5px; color: var(--accent-ink); background: var(--accent-soft); }
.source-detail { font-size: 12px; color: var(--muted); }
.source-detail div { display: flex; flex-wrap: wrap; gap: 8px; }
.source-detail blockquote { margin: 6px 0; }
.note { padding: 8px 10px; border-radius: 8px; font-size: 12px; }
.note.warn { background: #fff4dd; color: #805900; }
.note.muted { color: var(--muted); background: var(--surface-muted); }
.loading { display: flex; align-items: center; gap: 5px; color: var(--muted); font-size: 12px; }
.loading i { width: 5px; height: 5px; border-radius: 50%; background: var(--chat-accent); animation: pulse 1.2s infinite; }
.loading i:nth-child(2) { animation-delay: .15s; }
.loading i:nth-child(3) { animation-delay: .3s; }
.loading span { margin-left: 5px; }
@keyframes pulse { 50% { opacity: .25; transform: translateY(-2px); } }
@media (prefers-reduced-motion: reduce) { .loading i { animation: none; } }

footer { flex-shrink: 0; padding: 12px max(16px, calc((100% - 840px) / 2)) 14px; }
.composer {
  --tr-color-primary: var(--chat-accent);
  --tr-sender-box-shadow: var(--shadow);
  --tr-sender-border-radius: 14px;
  width: 100%;
}
.privacy,.boundary { margin-top: 6px; text-align: center; color: var(--muted); font-size: 11px; }
.emergency { display: flex; gap: 10px; padding: 10px 16px; color: var(--danger); background: var(--danger-soft); }
.emergency strong { white-space: nowrap; }
@media (max-width: 560px) {
  .messages { padding-top: 15px; }
  .welcome { margin-top: 18px; }
  .quick-grid { grid-template-columns: 1fr; margin-top: 16px; }
  .bubble { max-width: calc(100% - 40px); }
}
</style>
