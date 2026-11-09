<template>
  <main class="task-page">
    <header>
      <button @click="router.push('/')">← 返回首页</button>
      <div><p>TASK CENTER</p><h1>任务中心</h1><span>查看工作流、文档索引和知识库评测的执行结果</span></div>
      <button class="refresh" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新任务' }}</button>
    </header>

    <nav class="task-types" aria-label="任务类型">
      <RouterLink v-for="(label, value) in taskTypes" :key="value" :class="{ active: type === value }"
        :to="{ path: '/tasks', query: { type: value } }">{{ label }}</RouterLink>
      <RouterLink v-if="taskId" :to="{ path: '/tasks', query: { type } }">查看该类型全部任务</RouterLink>
    </nav>
    <section v-if="type === 'AGENT_WORKFLOW' && !taskId" class="create">
      <div><h2>创建工作流</h2><p>系统会拆解目标、执行步骤并按需自动复核。</p></div>
      <textarea v-model="objective" placeholder="描述需要分步执行的任务，例如检索资料、比较方案或整理报告"></textarea>
      <label class="review-toggle"><input v-model="reviewEnabled" type="checkbox"/><span>启用自动质量审查</span></label>
      <button :disabled="!objective.trim()||creating" @click="create">{{ creating?'正在创建…':'启动工作流' }}</button>
    </section>

    <section class="list-head"><h2>执行记录</h2><span>{{ tasks.length }} 项任务</span></section>
    <section v-if="tasks.length" class="tasks">
      <article v-for="task in tasks" :key="task.id">
        <div class="task-top">
          <div><b>{{ task.input?.objective || task.input?.name || task.input?.fileName || taskTypes[task.type] }}</b><small>{{ task.id }} · {{ task.type || 'AGENT_WORKFLOW' }}</small></div>
          <span :class="'status '+String(task.status||'UNKNOWN').toLowerCase()">{{ statusText(task.status) }}</span>
        </div>
        <div class="progress"><i :style="{width:progressValue(task)+'%'}"></i></div>
        <div class="task-meta"><span>当前阶段：{{ task.stage || '—' }}</span><span v-if="task.input?.spaceId">空间：{{ task.input.spaceId }}</span><span v-if="workflow(task).iteration">规划轮次：{{ workflow(task).iteration }}</span><span>进度：{{ progressValue(task) }}%</span></div>

        <section v-if="planSteps(task).length" class="dynamic-plan">
          <h3>动态执行计划 <span>{{ planSteps(task).length }} 个步骤</span></h3>
          <div v-for="(step,index) in planSteps(task)" :key="step.id||index" class="plan-step" :class="{ done: executionAt(task,step) }">
            <b>{{ step.order || index + 1 }}</b>
            <div>
              <strong>{{ [step.id, step.role].filter(Boolean).join(' · ') || '步骤 ' + (index + 1) }}</strong>
              <small>{{ step.description }}</small>
              <small v-if="step.dependsOn?.length"> · 依赖 {{ step.dependsOn.join('、') }}</small>
              <div v-if="executionAt(task,step)" class="markdown" v-html="md(executionAt(task,step).result)"></div>
            </div>
            <em>{{ executionAt(task,step) ? '已执行' : statusText(step.status) }}</em>
          </div>
        </section>
        <div v-else-if="task.type === 'AGENT_WORKFLOW' && running(task.status)" class="planning">执行计划将在结果中展示，当前进度见上方阶段。</div>

        <section v-if="task.type === 'RAG_EVALUATION' && workflow(task).metrics" class="integrated-result evaluation">
          <h3>评测结果 · {{ workflow(task).caseCount }} 条用例 · Top {{ workflow(task).topK }}</h3>
          <table>
            <thead><tr><th>检索阶段</th><th>Hit@K</th><th>MRR@K</th><th>Recall@K</th><th>首条平均得分</th></tr></thead>
            <tbody>
              <tr v-for="(metric, stage) in { '最终结果': workflow(task).metrics, ...workflow(task).stageMetrics }" :key="stage">
                <th>{{ stageNames[stage] || stage }}</th><td>{{ percent(metric.hitRateAtK) }}</td>
                <td>{{ decimal(metric.mrrAtK) }}</td><td>{{ percent(metric.recallAtK) }}</td><td>{{ decimal(metric.averageTopScore) }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="workflow(task).metrics.citationCoverage != null">引用覆盖率 {{ percent(workflow(task).metrics.citationCoverage) }}</p>
          <details>
            <summary>查看用例结果</summary>
            <ol>
              <li v-for="(item, index) in workflow(task).cases" :key="index">
                <b>{{ item.question }}</b>
                <p>{{ item.hit ? '首次命中排名 ' + item.firstRelevantRank : '未命中' }}</p>
                <p v-if="item.retrievedDocumentIds?.length">召回文档 {{ item.retrievedDocumentIds.join('、') }}</p>
                <div v-if="item.answer" class="markdown" v-html="md(item.answer)"></div>
              </li>
            </ol>
          </details>
        </section>

        <section v-if="task.type === 'DOCUMENT_INDEX' && task.result" class="integrated-result">
          <h3>索引结果</h3>
          <pre>{{ stringify(task.result) }}</pre>
        </section>

        <section v-if="workflow(task).executionResult" class="integrated-result">
          <h3>整合结果</h3><div class="markdown" v-html="md(stringify(workflow(task).executionResult))"></div>
        </section>
        <section v-if="reviewOf(task)" :class="['review',reviewOf(task).passed?'passed':'attention']">
          <div><b>{{ reviewOf(task).passed ? '自动质量审查通过' : '自动质量审查未通过' }}</b><span>{{ reviewOf(task).message || '未提供审查说明' }}</span></div>
          <strong v-if="reviewOf(task).requiresHumanReview" class="human">{{ reviewOf(task).reviewerRole === 'DOCTOR_OR_PHARMACIST' ? '需要医生或药师审核' : '需要人工复核' }}</strong>
          <small v-if="reviewOf(task).reviewerRole && reviewOf(task).reviewerRole!=='NONE'">审核角色：{{ reviewOf(task).reviewerRole }}</small>
          <ul v-if="reviewOf(task).issues?.length"><li v-for="issue in reviewOf(task).issues" :key="issue">{{ issue }}</li></ul>
          <p v-if="reviewOf(task).revisionInstruction">修订要求：{{ reviewOf(task).revisionInstruction }}</p>
          <p v-if="reviewOf(task).replanRequired">审查要求重新规划。</p>
        </section>
        <p v-if="task.error" class="task-error">{{ task.error }}</p>
        <details v-if="task.events?.length" class="events"><summary>执行事件（{{ task.events.length }}）</summary><ol><li v-for="(event,index) in task.events" :key="index">{{ event }}</li></ol></details>
        <RouterLink v-if="task.type === 'AGENT_WORKFLOW'" class="audit-link" :to="{ path: '/agent-runs', query: { taskId: task.id } }">查看运行审计</RouterLink>
        <button v-if="running(task.status)" class="cancel" @click="remove(task)">终止任务</button>
      </article>
    </section>
    <div v-else class="empty">{{ loading ? '正在加载…' : taskId ? '暂无该任务的记录' : '暂无此类任务' }}</div>
    <div v-if="notice" class="notice">{{ notice }}</div>
  </main>
</template>
<script setup>
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {marked} from 'marked'
import DOMPurify from 'dompurify'
import {cancelTask,createWorkflow,getTask,getTasks} from '../api'
import {apiError,newChatId} from '../utils/medical'
const props=defineProps({spaceId:String})
const router=useRouter(),tasks=ref([]),objective=ref(''),reviewEnabled=ref(true),loading=ref(false),creating=ref(false),notice=ref('')
const route=useRoute()
const taskTypes={AGENT_WORKFLOW:'工作流',DOCUMENT_INDEX:'文档索引',RAG_EVALUATION:'知识库评测'}
const stageNames={DENSE:'向量检索',HYBRID:'混合检索',HYBRID_MMR:'混合检索与 MMR'}
const type=computed(()=>typeof route.query.type==='string'&&Object.hasOwn(taskTypes,route.query.type)?route.query.type:'AGENT_WORKFLOW')
const taskId=computed(()=>typeof route.query.taskId==='string'?route.query.taskId:'')
const percent=value=>value==null?'—':(value*100).toFixed(1)+'%'
const decimal=value=>value==null?'—':Number(value).toFixed(3)
let poll=null,noticeTimer=null
let loadVersion=0
const running=status=>['QUEUED','PENDING','RUNNING','PROCESSING'].includes(String(status||'').toUpperCase())
const statusText=status=>({QUEUED:'等待执行',PENDING:'等待执行',RUNNING:'执行中',PROCESSING:'执行中',COMPLETED:'已完成',SUCCESS:'已完成',FAILED:'失败',CANCELLED:'已终止'}[String(status||'').toUpperCase()]||status||'未知')
const showNotice=text=>{notice.value=text;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>notice.value='',5000)}
const parse=value=>{if(typeof value!=='string')return value||{};try{return JSON.parse(value)}catch{return{executionResult:value}}}
const workflow=task=>parse(task.result)
const planSteps=task=>Array.isArray(workflow(task).plan?.steps)?workflow(task).plan.steps:[]
const executionAt=(task,step)=>workflow(task).executions?.find(execution=>execution.order===step.order)
const stringify=value=>typeof value==='string'?value:JSON.stringify(value,null,2)
const progressValue=task=>Math.max(0,Math.min(100,Number(task.progress)||(['COMPLETED','SUCCESS'].includes(String(task.status).toUpperCase())?100:0)))
const reviewOf=task=>workflow(task).review||task.review||null
const load=async(force=false)=>{
  if(loading.value&&force!==true)return
  const version=++loadVersion
  loading.value=true
  try{
    if(taskId.value){
      const {data}=await getTask(taskId.value)
      if(version===loadVersion)tasks.value=data?[data]:[]
    }else{
      const {data}=await getTasks({type:type.value})
      if(version===loadVersion)tasks.value=Array.isArray(data)?data:[]
    }
  }catch(error){
    if(version===loadVersion)showNotice(apiError(error))
  }finally{
    if(version===loadVersion)loading.value=false
  }
}
const create=async()=>{creating.value=true;try{const {data}=await createWorkflow({objective:objective.value.trim(),chatId:newChatId('workflow'),spaceId:props.spaceId,reviewEnabled:reviewEnabled.value});objective.value='';showNotice('工作流已创建。');await router.push({path:'/tasks',query:{type:'AGENT_WORKFLOW',taskId:data.id}})}catch(error){showNotice(apiError(error))}finally{creating.value=false}}
const remove=async task=>{if(!window.confirm('确定终止这项任务吗？'))return;try{await cancelTask(task.id);await load(true)}catch(error){showNotice(apiError(error))}}
watch([type,taskId],()=>{
  tasks.value=[]
  notice.value=''
  load(true)
},{immediate:true})
onMounted(()=>{poll=setInterval(()=>{if(tasks.value.some(task=>running(task.status)))load()},1500)})
onBeforeUnmount(()=>{loadVersion++;clearInterval(poll);clearTimeout(noticeTimer)})
const md=value=>DOMPurify.sanitize(marked.parse(String(value||''),{gfm:true,breaks:true,async:false}),{USE_PROFILES:{html:true}})
</script>
<style scoped>
.task-types{display:flex;flex-wrap:wrap;gap:8px;margin-top:24px}
.task-types a,.audit-link{display:inline-block;padding:7px 10px;border:1px solid #d4e2e2;border-radius:8px;color:#087985;text-decoration:none;font-size:12px}
.task-types .active{background:#087985;color:#fff}
.audit-link{margin:12px 8px 0 0}
.evaluation table{width:100%;border-collapse:collapse;font-size:12px;text-align:left}
.evaluation th,.evaluation td{padding:8px;border-bottom:1px solid #e2ebea}
.evaluation p,.evaluation details,.evaluation li{margin-top:8px;font-size:12px;overflow-wrap:anywhere}
.evaluation ol{padding-left:20px}
.integrated-result pre{white-space:pre-wrap;overflow-wrap:anywhere}
.task-page{min-height:100%;padding:36px clamp(20px,5vw,70px) 70px;background:#f3f7f7;color:#18383d;overflow:auto}.task-page>header{display:flex;align-items:center;gap:25px}.task-page>header>div{flex:1}.task-page>header p{font-size:10px;letter-spacing:1.6px;color:#16808a;font-weight:800}.task-page h1{font-size:32px;margin:5px 0}.task-page>header span,.create p{font-size:12px;color:#789095}.task-page button{border:1px solid #d4e2e2;background:#fff;border-radius:10px;padding:9px 13px;color:#486368}.refresh{color:#087985!important}.create{display:grid;grid-template-columns:230px minmax(260px,1fr) auto auto;gap:16px;align-items:center;margin:32px 0;padding:22px;border-radius:18px;background:#fff;border:1px solid #dce8e8}.create h2,.list-head h2{font-size:17px}.create textarea{height:70px;resize:none;padding:12px;border:1px solid #d3e1e1;border-radius:11px;font:13px/1.5 inherit}.create>button{background:#087985;color:#fff;border:0}.review-toggle{display:flex;align-items:center;gap:7px;color:#58716e;font-size:11px}.review-toggle input{accent-color:#087f75}.list-head{display:flex;justify-content:space-between;align-items:center;margin:25px 0 12px}.list-head span{font-size:12px;color:#789095}.tasks{display:grid;gap:14px}.tasks>article{position:relative;padding:20px;border-radius:16px;background:#fff;border:1px solid #dce7e7}.task-top{display:flex;justify-content:space-between;gap:15px}.task-top small{display:block;margin-top:3px;color:#93a4a7}.status{padding:5px 9px;border-radius:99px;background:#edf2f3;color:#64777b;font-size:10px}.status.running,.status.processing{background:#e0f3f3;color:#087985}.status.completed,.status.success{background:#e3f4e8;color:#287342}.status.failed{background:#fee7e7;color:#a62b2b}.progress{height:5px;margin:16px 0 8px;background:#e6eeee;border-radius:99px;overflow:hidden}.progress i{display:block;height:100%;background:#087f8c}.task-meta{display:flex;gap:18px;color:#7d9194;font-size:10px}.dynamic-plan,.integrated-result{margin-top:16px;padding:14px;border:1px solid #e2ebea;border-radius:12px}.dynamic-plan h3,.integrated-result h3{font-size:12px;color:#315d5a;margin-bottom:10px}.dynamic-plan h3 span{margin-left:6px;color:#91a19e;font-weight:400}.plan-step{display:grid;grid-template-columns:24px 1fr auto;gap:9px;padding:9px 0;border-top:1px solid #edf2f1}.plan-step>b{width:22px;height:22px;display:grid;place-items:center;border-radius:7px;background:#edf3f2;color:#718582;font-size:10px}.plan-step>div>strong{display:block;font-size:11px}.plan-step small,.plan-step em{color:#899a98;font:normal 9px inherit}.plan-step.done>b,.plan-step.active>b{background:#dff2ee;color:#08786d}.plan-step.active em{color:#08786d}.plan-step .markdown{margin-top:7px;padding:8px;background:#f6f9f8;border-radius:7px;color:#536d69;font-size:10px}.planning{margin-top:14px;padding:11px;border-radius:9px;background:#f0f6f5;color:#66807c;font-size:11px}.integrated-result .markdown{font-size:12px;line-height:1.65}.markdown :deep(ul),.markdown :deep(ol){padding-left:18px}.markdown :deep(pre){overflow:auto;padding:9px;background:#173033;color:#e7f2f1;border-radius:8px}.review{display:grid;grid-template-columns:1fr auto;gap:7px 14px;margin-top:12px;padding:12px;border-radius:10px;font-size:11px}.review>div span{display:block;margin-top:3px}.review .human{padding:4px 8px;border-radius:7px;background:#a85c00;color:#fff}.review ul,.review p{grid-column:1/-1;padding-left:17px}.review.passed{background:#eaf7ee;color:#277242}.review.attention{background:#fff2dc;color:#875b08}.task-error{margin-top:10px;padding:9px;background:#feeaea;color:#9d2929;border-radius:8px;font-size:11px}.events{margin-top:10px;color:#708481;font-size:10px}.events ol{padding:8px 0 0 22px}.cancel{margin-top:12px;color:#b32e2e!important}.empty{text-align:center;padding:70px;color:#91a2a5}.notice{position:fixed;top:20px;left:50%;z-index:20;transform:translateX(-50%);background:#18383d;color:#fff;padding:10px 15px;border-radius:9px}@media(max-width:900px){.create{grid-template-columns:1fr}.task-page>header{align-items:flex-start}.task-page>header>button:first-child{display:none}.task-meta{flex-wrap:wrap}.review{grid-template-columns:1fr}}
</style>
