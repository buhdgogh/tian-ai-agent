<template>
  <section class="chat-shell" :style="{ '--accent': themeColor, '--accent-rgb': themeColorRgb }" @click="handleCitationClick">
    <div v-if="emergencyText" class="emergency" role="alert"><strong>紧急提示</strong><span>{{ emergencyText }}</span></div>
    <div ref="scroller" class="messages">
      <div v-if="!visibleMessages.length" class="welcome">
        <div class="welcome-icon">{{ welcomeIcon }}</div><p class="eyebrow">TIAN MEDICAL SAFETY ASSISTANT</p>
        <h1>{{ title }}</h1><p>{{ subtitle }}</p>
        <div class="quick-grid"><button v-for="q in quickQuestions" :key="q" :disabled="generating" @click="emit('send-message', q)"><span>↗</span>{{ q }}</button></div>
      </div>
      <article v-for="(msg,index) in visibleMessages" :key="msg.id || `${msg.time}-${index}`" class="message" :class="msg.isUser?'user':'assistant'">
        <div class="avatar"><span v-if="msg.isUser">我</span><AiAvatarFallback v-else :type="aiType"/></div>
        <div class="bubble">
          <div v-if="!msg.isUser && ['RUNNING','PENDING'].includes(msg.status)" class="note muted">{{ msg.status === 'PENDING' ? '等待处理' : '正在生成' }}<button v-if="!generating" @click="emit('refresh-history')">刷新状态</button></div>
          <div v-else-if="!msg.isUser && msg.status === 'FAILED'" class="note warn">执行失败 · {{ msg.error || '本次结果可能不完整，请重试。' }}</div>
          <div v-else-if="msg.incomplete || msg.status === 'CANCELLED'" class="note muted">生成已停止 · 以下为不完整内容</div>
          <p v-if="msg.workflowReview?.requiresHumanReview" class="note warn">该任务结果需要人工复核。</p>
          <p v-if="msg.trackingError" class="note warn">{{ msg.trackingError }}</p>
          <section v-if="msg.workflowProgress" class="workflow-progress" aria-live="polite">
            <strong>{{ msg.workflowProgress.stage }}</strong><span>{{ Math.max(0, Math.min(100, Number(msg.workflowProgress.progress) || 0)) }}%</span>
            <progress max="100" :value="msg.workflowProgress.progress || 0"></progress>
          </section>
          <details v-if="msg.workflow?.plan?.steps?.length" class="workflow-plan">
            <summary>本次处理流程 · {{ msg.workflow.plan.steps.length }} 个步骤</summary>
            <ol><li v-for="step in msg.workflow.plan.steps" :key="step.id">{{ step.description }}</li></ol>
          </details>
          <p v-if="msg.workflowReview" class="note" :class="msg.workflowReview.passed ? 'muted' : 'warn'">{{ msg.workflowReview.passed ? '质量复核通过' : '质量复核未通过，请审慎使用' }}<span v-if="msg.workflowReview.message"> · {{ msg.workflowReview.message }}</span></p>
          <details v-if="msg.trace?.length" class="agent-trace" :open="generating && index === visibleMessages.length - 1">
            <summary><span class="trace-mark">⌁</span><strong>思考与调用路径</strong><small>{{ msg.trace.length }} 个步骤</small></summary>
            <ol>
              <li v-for="(step,stepIndex) in msg.trace" :key="step.id || `${step.type}-${stepIndex}`" :class="'trace-'+step.type">
                <i>{{ traceIcon(step.type) }}</i><span><strong>{{ step.title }}</strong><small v-if="step.detail">{{ step.detail }}</small><code v-if="step.tool">{{ step.tool }}</code></span><em>{{ step.status === 'failed' ? '失败' : '已完成' }}</em>
              </li>
            </ol>
          </details>
          <div v-if="String(msg.content||'').trim()" class="markdown" v-html="msg.isUser ? md(msg.content) : citedMd(msg.content, msg.sources, 'chat-source-' + index)"></div>
          <section v-if="msg.report" class="report">
            <header><strong>结构化循证安全报告</strong><span :class="'risk '+riskClass(msg.report.riskLevel)">{{ riskLabel(msg.report.riskLevel) }}</span></header>
            <div v-for="part in reportParts(msg.report)" :key="part.title" class="report-part"><h3>{{ part.title }}</h3><div class="markdown" v-html="md(part.value)"></div></div>
            <p v-if="msg.report.requiresProfessionalReview||msg.report.professionalReviewRequired||msg.report.requiresHumanReview" class="note warn">需要医生或药师审核，不能视为 AI 已确认安全。</p>
          </section>
          <section v-if="msg.ragMeta" class="rag-quality">
            <header><strong>检索与回答校验</strong><div class="quality-badges"><span v-if="msg.ragMeta.riskLevel" :class="'risk '+riskClass(msg.ragMeta.riskLevel)">{{ riskLabel(msg.ragMeta.riskLevel) }}</span><span :class="'validation validation-'+(msg.ragMeta.validation?.status||'unknown')">{{ validationLabel(msg.ragMeta.validation?.status) }}</span></div></header>
            <div v-if="msg.ragMeta.plan" class="rag-plan"><span>回答类型：{{ msg.ragMeta.plan.answer_type || '未标注' }}</span><span v-if="msg.ragMeta.plan.sections?.length">结构：{{ msg.ragMeta.plan.sections.join(' / ') }}</span></div>
            <div v-if="msg.ragMeta.validation?.citation_integrity" class="coverage"><span>引用覆盖率</span><b>{{ score(msg.ragMeta.validation.citation_integrity.citation_coverage) }}</b><i><em :style="{width:score(msg.ragMeta.validation.citation_integrity.citation_coverage)}"></em></i></div>
            <div v-if="ragWarnings(msg.ragMeta.validation).length" class="rag-warnings"><strong>需要注意</strong><ul><li v-for="warning in ragWarnings(msg.ragMeta.validation)" :key="warning">{{ warning }}</li></ul></div>
            <p v-if="needsRagReview(msg.ragMeta)" class="note warn">该回答需要医生或药师复核后再用于医疗判断。</p>
            <small v-if="msg.ragMeta.retrieval">检索模式：{{ msg.ragMeta.retrieval.mode || 'unknown' }} · 稠密候选 {{ msg.ragMeta.retrieval.dense_candidates || 0 }} · BM25 候选 {{ msg.ragMeta.retrieval.bm25_candidates || 0 }}</small>
          </section>
          <p v-if="msg.evidenceEmpty" class="note warn">当前没有足够的已审核医疗证据，无法根据当前证据回答；请勿将本回答作为医疗决策依据。</p>
          <section v-if="msg.sources?.length" class="sources">
            <h3>证据来源 · {{ msg.sources.length }}</h3>
            <details v-for="(source,i) in msg.sources" :id="'chat-source-' + index + '-' + (source.citation||i+1)" :key="source.chunkId||source.id||i">
              <summary><b>{{ source.citation||i+1 }}</b><span><strong>{{ source.title||source.fileName||'未命名来源' }}</strong><small>{{ source.issuer||'发布机构未标注' }} · {{ source.version||'版本未标注' }}</small></span><em>{{ source.reviewStatus||source.effectiveStatus||'待核验' }}</em></summary>
              <div class="source-detail"><div><span>发布日期：{{ source.publishedAt||'未标注' }}</span><span>适用人群：{{ source.audience||'未标注' }}</span><span v-if="source.page">页码：{{ source.page }}</span><span v-if="source.score!=null">相关度：{{ score(source.score) }}</span><a v-if="sourceLink(source)" :href="sourceLink(source)" target="_blank" rel="noopener noreferrer">查看原文 ↗</a></div><blockquote v-if="source.quote||source.content">{{ source.quote||source.content }}</blockquote></div>
            </details>
          </section>
          <time>{{ formatTime(msg.time) }}</time>
        </div>
      </article>
      <article v-if="generating&&!emergencyText" class="message assistant"><div class="avatar"><AiAvatarFallback :type="aiType"/></div><div class="bubble loading"><i></i><i></i><i></i><span>正在核对信息与安全边界…</span></div></article>
    </div>
    <footer>
      <p class="privacy">隐私提示：请勿提交姓名、身份证号、完整住址、病历号等无关身份信息。</p>
      <div class="input-wrap"><textarea v-model="input" rows="1" :placeholder="placeholder" :disabled="generating" @keydown.enter.exact.prevent="send"/><button v-if="generating" class="stop" @click="emit('stop-generation')">■ 停止</button><button v-else class="send" :disabled="!input.trim()" @click="send">↑</button></div>
      <p class="boundary">提供健康信息与就医沟通支持，不能替代医生、药师或急救服务。</p>
    </footer>
  </section>
</template>
<script setup>
import {computed,nextTick,onMounted,ref,watch} from 'vue'
import {marked} from 'marked'
import DOMPurify from 'dompurify'
import AiAvatarFallback from './AiAvatarFallback.vue'
const props=defineProps({messages:{type:Array,default:()=>[]},connectionStatus:{type:String,default:'disconnected'},aiType:{type:String,default:'medical'},themeColor:{type:String,default:'#087f8c'},themeColorRgb:{type:String,default:'8,127,140'},title:{type:String,default:'天医医疗信息助手'},subtitle:{type:String,default:'以循证资料解释健康问题，并优先识别需要及时就医的风险信号'},welcomeIcon:{type:String,default:'⚕'},suggestions:{type:Array,default:()=>[]},placeholder:{type:String,default:'描述健康问题、持续时间及已知用药（请勿填写身份信息）'},emergencyText:{type:String,default:''}})
const emit=defineEmits(['send-message','stop-generation','refresh-history']),input=ref(''),scroller=ref(null)
const generating=computed(()=>['connecting','loading'].includes(props.connectionStatus))
const visibleMessages=computed(()=>props.messages.filter(msg=>String(msg?.content||'').trim()||msg?.sources?.length||msg?.report||msg?.ragMeta||msg?.trace?.length||['RUNNING','PENDING','FAILED','CANCELLED'].includes(msg?.status)))
const quickQuestions=computed(()=>props.suggestions.length?props.suggestions:['这些症状有哪些需要立即就医的危险信号？','帮我整理一份就诊时要向医生说明的信息','请用通俗语言解释这份检查结果的常见含义'])
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
const send=()=>{const value=input.value.trim();if(!value||generating.value)return;emit('send-message',value);input.value=''}
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
const needsRagReview=meta=>meta?.validation?.status==='needs_review'||['HIGH','EMERGENCY'].includes(String(meta?.riskLevel||'').toUpperCase())
const stringify=v=>Array.isArray(v)?v.map(x=>'- '+(typeof x==='object'?JSON.stringify(x):x)).join('\n'):typeof v==='object'?JSON.stringify(v,null,2):String(v||'未提供')
const reportParts=r=>[['摘要',r.summary],['证据摘要',r.evidence||r.evidenceSummary],['不确定性',r.uncertainties],['危险信号',r.redFlags],['建议的下一步',r.nextSteps]].filter(x=>x[1]!=null&&x[1]!=='').map(x=>({title:x[0],value:stringify(x[1])}))
const scroll=async()=>{await nextTick();if(scroller.value)scroller.value.scrollTop=scroller.value.scrollHeight}
watch(()=>JSON.stringify(props.messages),scroll);onMounted(scroll);defineExpose({clearInput:()=>input.value=''})
</script>
<style scoped>
.workflow-progress{display:grid;grid-template-columns:1fr auto;gap:6px 16px;padding:10px 12px;margin-bottom:12px;border-radius:9px;background:#eef6f6;color:#42656a;font-size:12px}.workflow-progress progress{grid-column:1/-1;width:100%;height:7px;accent-color:var(--accent)}.workflow-plan{margin-bottom:12px;font-size:12px;color:#527176}.workflow-plan summary{cursor:pointer}.workflow-plan ol{padding:8px 0 0 20px}
.chat-shell{height:100%;display:flex;flex-direction:column;background:radial-gradient(circle at 70% -10%,rgba(var(--accent-rgb),.09),transparent 34%),#f4f8f8;color:#19333a;position:relative}.messages{flex:1;overflow:auto;padding:44px max(20px,calc((100% - 900px)/2)) 28px}.welcome{text-align:center;padding:42px 0}.welcome-icon{width:72px;height:72px;margin:auto;display:grid;place-items:center;border-radius:24px;background:linear-gradient(145deg,#075e68,var(--accent));color:#fff;font-size:35px;box-shadow:0 18px 40px rgba(var(--accent-rgb),.22)}.eyebrow{margin-top:22px!important;color:var(--accent)!important;font-size:11px!important;font-weight:800;letter-spacing:1.7px}.welcome h1{font-size:34px;margin:8px 0 12px}.welcome>p{color:#657b80;line-height:1.7}.quick-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:42px}.quick-grid button{border:1px solid #d9e6e6;background:#fff;padding:18px;text-align:left;border-radius:16px;color:#36545a;line-height:1.5;transition:.2s}.quick-grid button span{display:block;color:var(--accent);margin-bottom:10px}.quick-grid button:hover{transform:translateY(-3px);border-color:var(--accent);box-shadow:0 12px 24px rgba(31,72,77,.08)}.message{display:flex;gap:13px;margin:0 auto 25px;max-width:900px}.message.user{flex-direction:row-reverse}.avatar{width:38px;height:38px;flex:0 0 38px;border-radius:13px;display:grid;place-items:center;background:#e1ebeb;color:#536d72;font-weight:700;overflow:hidden}.bubble{max-width:82%;background:#fff;border:1px solid #e1eaea;border-radius:5px 18px 18px;padding:15px 18px;line-height:1.7;box-shadow:0 6px 22px rgba(28,58,63,.04)}.user .bubble{background:linear-gradient(135deg,#087783,var(--accent));color:#fff;border:0;border-radius:18px 5px 18px 18px}.bubble time{display:block;margin-top:7px;font-size:10px;color:#91a3a6}.user time{text-align:right;color:#cce}.markdown{word-break:break-word}.markdown :deep(p),.markdown :deep(ul),.markdown :deep(ol),.markdown :deep(pre),.markdown :deep(blockquote),.markdown :deep(table){margin:0 0 .7em}.markdown :deep(:last-child){margin-bottom:0}.markdown :deep(ul),.markdown :deep(ol){padding-left:1.4em}.markdown :deep(h1),.markdown :deep(h2),.markdown :deep(h3){margin:.8em 0 .3em;line-height:1.35}.markdown :deep(h1){font-size:1.4em}.markdown :deep(h2){font-size:1.22em}.markdown :deep(h3){font-size:1.08em}.markdown :deep(code){padding:2px 5px;background:rgba(15,23,42,.08);border-radius:5px}.markdown :deep(pre){padding:12px;overflow:auto;background:#142a30;color:#e8f3f3;border-radius:10px}.markdown :deep(blockquote){border-left:3px solid var(--accent);padding-left:12px}.report{margin-top:14px;border:1px solid #cfe2e2;border-radius:14px;overflow:hidden}.report header{display:flex;justify-content:space-between;padding:13px 15px;background:#eff8f8}.risk{font-size:11px;padding:4px 9px;border-radius:99px;background:#e5e7eb}.risk-emergency,.risk-high{background:#fee2e2;color:#b91c1c}.risk-medium{background:#fff4cc;color:#8a6200}.risk-low{background:#dcfce7;color:#15733c}.report-part{padding:13px 15px;border-top:1px solid #e5eeee}.report-part h3,.sources>h3{font-size:12px;color:#08717c;margin-bottom:6px}.note{margin:10px 0;padding:9px 11px;border-radius:9px;font-size:12px}.warn{background:#fff8e1;color:#805c00}.muted{background:#eef2f4;color:#607178}.sources{margin-top:15px}.sources details{border:1px solid #dce8e8;border-radius:10px;margin-top:7px;background:#fbfefe}.sources summary{display:grid;grid-template-columns:25px 1fr auto;gap:9px;align-items:center;padding:10px;cursor:pointer}.sources summary>b{width:23px;height:23px;border-radius:7px;display:grid;place-items:center;background:#dff3f3;color:#08727d}.sources small{display:block;color:#819397}.sources em{font-size:10px;color:#08727d;font-style:normal}.source-detail{padding:0 12px 12px}.source-detail>div{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:11px;color:#71878b}.source-detail blockquote{margin-top:9px;padding:9px;background:#f0f6f6;border-left:3px solid #5fa8ad}.loading{display:flex;align-items:center;gap:5px}.loading i{width:6px;height:6px;border-radius:50%;background:var(--accent);animation:pulse 1.2s infinite}.loading i:nth-child(2){animation-delay:.15s}.loading i:nth-child(3){animation-delay:.3s}.loading span{font-size:12px;color:#688084;margin-left:5px}@keyframes pulse{50%{opacity:.25;transform:translateY(-3px)}}footer{padding:10px max(20px,calc((100% - 900px)/2)) 17px;background:linear-gradient(transparent,#f4f8f8 24%)}.privacy,.boundary{text-align:center;color:#778b8f;font-size:11px;margin:0 0 7px}.boundary{margin:7px 0 0}.input-wrap{display:flex;align-items:flex-end;gap:9px;padding:10px 11px 10px 17px;background:#fff;border:1px solid #cedddd;border-radius:18px;box-shadow:0 14px 35px rgba(31,62,67,.1)}textarea{flex:1;resize:none;border:0;outline:0;min-height:37px;padding:8px 0;font:14px/1.5 inherit;background:transparent}.send,.stop{height:39px;border:0;border-radius:12px;font-weight:700}.send{width:39px;background:var(--accent);color:#fff;font-size:20px}.send:disabled{background:#ced9da}.stop{padding:0 14px;background:#fff0f0;color:#bd2929;border:1px solid #ffd0d0}.emergency{position:sticky;top:0;z-index:1000;background:#b91c1c;color:#fff;padding:12px 22px;display:flex;justify-content:center;gap:15px;box-shadow:0 8px 20px #7f1d1d40}.emergency strong{padding:3px 8px;border-radius:6px;background:#fff;color:#b91c1c}@media(max-width:760px){.quick-grid{grid-template-columns:1fr}.messages{padding-top:20px}.welcome{padding:20px 0}.welcome h1{font-size:27px}.bubble{max-width:90%}.emergency{align-items:flex-start;font-size:13px}}
.rag-quality{margin-top:13px;border:1px solid #dce7e7;border-radius:12px;background:#f9fcfc;overflow:hidden}.rag-quality>header{display:flex;justify-content:space-between;align-items:center;padding:9px 11px;border-bottom:1px solid #e6eeee;font-size:11px}.quality-badges{display:flex;align-items:center;gap:6px}.validation{padding:3px 7px;border-radius:99px;background:#e9eef0;color:#5e7478;font-size:9px}.validation-passed{background:#def4e5;color:#287442}.validation-revised,.validation-needs_review{background:#fff1d6;color:#876009}.validation-deterministic_only{background:#e6efff;color:#315ea8}.rag-plan{display:flex;flex-wrap:wrap;gap:5px 12px;padding:8px 11px;color:#647c80;font-size:10px}.coverage{display:grid;grid-template-columns:auto auto 1fr;align-items:center;gap:8px;padding:0 11px 8px;color:#667d81;font-size:10px}.coverage b{color:#087780}.coverage i{height:5px;border-radius:99px;background:#dfe9e9;overflow:hidden}.coverage em{display:block;height:100%;border-radius:inherit;background:var(--accent)}.rag-warnings{margin:0 11px 8px;padding:8px 10px;border-radius:8px;background:#fff6df;color:#805e16;font-size:10px}.rag-warnings ul{padding-left:16px}.rag-quality>.note{margin:0 11px 8px}.rag-quality>small{display:block;padding:7px 11px;background:#f0f5f5;color:#7b8e91;font-size:9px}.source-detail a{color:var(--accent);font-weight:700}.markdown :deep(.citation-link){display:inline-flex;align-items:center;margin:0 2px;padding:0 5px;border-radius:6px;background:#dff3f3;color:#08727d!important;font-size:.82em;font-weight:800;line-height:1.55;text-decoration:none;vertical-align:1px;transition:.18s}.markdown :deep(.citation-link:hover){background:var(--accent);color:#fff!important;transform:translateY(-1px)}.sources details{scroll-margin:90px}.sources details:target{border-color:var(--accent);box-shadow:0 0 0 3px rgba(var(--accent-rgb),.12)}
.agent-trace{margin:0 0 13px;border:1px solid #dbe5f4;border-radius:12px;background:#f7f9fd;overflow:hidden}.agent-trace summary{display:flex;align-items:center;gap:8px;padding:10px 12px;cursor:pointer;color:#31547d;list-style:none}.agent-trace summary::-webkit-details-marker{display:none}.agent-trace summary small{margin-left:auto;color:#8695a9;font-size:10px}.trace-mark{width:22px;height:22px;display:grid;place-items:center;border-radius:7px;background:#e4ebfa;color:#315ea8;font-weight:800}.agent-trace ol{list-style:none;margin:0;padding:2px 12px 10px}.agent-trace li{position:relative;display:grid;grid-template-columns:25px 1fr auto;gap:8px;align-items:start;padding:7px 0;color:#435b75}.agent-trace li:not(:last-child):after{content:'';position:absolute;left:12px;top:31px;bottom:-7px;width:1px;background:#d5dfed}.agent-trace li>i{width:25px;height:25px;display:grid;place-items:center;border-radius:50%;background:#e5edfb;color:#315ea8;font:normal 10px/1 inherit;z-index:1}.agent-trace li>span{min-width:0}.agent-trace li strong{display:block;font-size:12px}.agent-trace li small{display:block;color:#7b8da3;font-size:10px}.agent-trace code{display:inline-block;margin-top:3px;padding:1px 5px;border-radius:4px;background:#e9eef6;color:#5f7087;font-size:9px}.agent-trace li>em{padding-top:3px;color:#60806b;font:normal 9px/1 inherit}.agent-trace+.markdown{padding-top:2px}
</style>
