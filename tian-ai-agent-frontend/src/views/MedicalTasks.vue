<template>
  <main class="task-page">
    <header>
      <button @click="router.push('/')">← 返回首页</button>
      <div><p>MEDICAL WORKFLOW CONTROL</p><h1>任务与人工审核</h1><span>动态展示规划步骤、执行记录、自动审查与人工审核要求</span></div>
      <button class="refresh" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新任务' }}</button>
    </header>

    <section class="create">
      <div><h2>创建医疗工作流</h2><p>系统会先执行确定性急症检查，再动态生成 1～5 个步骤。</p></div>
      <textarea v-model="objective" placeholder="描述需要循证检索与安全复核的目标（请勿填写身份信息）"></textarea>
      <label class="review-toggle"><input v-model="reviewEnabled" type="checkbox"/><span>启用自动质量审查</span></label>
      <button :disabled="!objective.trim()||creating" @click="create">{{ creating?'正在创建…':'启动工作流' }}</button>
    </section>

    <section class="list-head"><h2>执行记录</h2><span>{{ tasks.length }} 项任务</span></section>
    <section v-if="tasks.length" class="tasks">
      <article v-for="task in tasks" :key="task.id">
        <div class="task-top">
          <div><b>{{ task.input?.objective || '医疗信息工作流' }}</b><small>{{ task.id }} · {{ task.type || 'AGENT_WORKFLOW' }}</small></div>
          <span :class="'status '+String(task.status||'UNKNOWN').toLowerCase()">{{ statusText(task.status) }}</span>
        </div>
        <div class="progress"><i :style="{width:progressValue(task)+'%'}"></i></div>
        <div class="task-meta"><span>当前阶段：{{ task.stage || '等待规划' }}</span><span>规划轮次：{{ workflow(task).iteration || 0 }}</span><span>进度：{{ progressValue(task) }}%</span></div>

        <section v-if="planSteps(task).length" class="dynamic-plan">
          <h3>动态执行计划 <span>{{ planSteps(task).length }} 个步骤</span></h3>
          <div v-for="(step,index) in planSteps(task)" :key="step.id||index" class="plan-step" :class="stepState(task,index)">
            <b>{{ index+1 }}</b>
            <div><strong>{{ stepTitle(step,index) }}</strong><small v-if="stepDescription(step)">{{ stepDescription(step) }}</small><p v-if="executionAt(task,index)" class="markdown" v-html="md(executionText(executionAt(task,index)))"></p></div>
            <em>{{ stepStateLabel(task,index) }}</em>
          </div>
        </section>
        <div v-else-if="running(task.status)" class="planning">正在等待 Planner 生成动态步骤…</div>

        <section v-if="workflow(task).executionResult" class="integrated-result">
          <h3>整合结果</h3><div class="markdown" v-html="md(stringify(workflow(task).executionResult))"></div>
        </section>
        <section v-if="reviewOf(task)" :class="['review',reviewOf(task).passed?'passed':'attention']">
          <div><b>{{ reviewOf(task).passed ? '自动质量审查通过' : '自动质量审查未通过' }}</b><span>{{ reviewOf(task).message || '未提供审查说明' }}</span></div>
          <strong v-if="reviewOf(task).requiresHumanReview" class="human">需要医生或药师审核</strong>
          <small v-if="reviewOf(task).reviewerRole && reviewOf(task).reviewerRole!=='NONE'">审核角色：{{ reviewOf(task).reviewerRole }}</small>
          <ul v-if="reviewOf(task).issues?.length"><li v-for="issue in reviewOf(task).issues" :key="issue">{{ issue }}</li></ul>
          <p v-if="reviewOf(task).revisionInstruction">修订要求：{{ reviewOf(task).revisionInstruction }}</p>
          <p v-if="reviewOf(task).replanRequired">审查要求重新规划。</p>
        </section>
        <p v-if="task.error" class="task-error">{{ task.error }}</p>
        <details v-if="task.events?.length" class="events"><summary>执行事件（{{ task.events.length }}）</summary><ol><li v-for="(event,index) in task.events" :key="index">{{ event }}</li></ol></details>
        <button v-if="running(task.status)" class="cancel" @click="remove(task)">终止任务</button>
      </article>
    </section>
    <div v-else class="empty">暂无医疗工作流任务</div>
    <div v-if="notice" class="notice">{{ notice }}</div>
  </main>
</template>
<script setup>
import {onBeforeUnmount,onMounted,ref} from 'vue'
import {useRouter} from 'vue-router'
import {marked} from 'marked'
import DOMPurify from 'dompurify'
import {cancelTask,createMedicalWorkflow,getTasks} from '../api'
import {apiError,newChatId} from '../utils/medical'
const router=useRouter(),tasks=ref([]),objective=ref(''),reviewEnabled=ref(true),loading=ref(false),creating=ref(false),notice=ref('')
let poll=null,noticeTimer=null
const ownerId=(()=>{const key='tian_medical_owner_id';let value=localStorage.getItem(key);if(!value){value=newChatId('web');localStorage.setItem(key,value)}return value})()
const running=status=>['QUEUED','PENDING','RUNNING','PROCESSING'].includes(String(status||'').toUpperCase())
const statusText=status=>({QUEUED:'等待执行',PENDING:'等待执行',RUNNING:'执行中',PROCESSING:'执行中',COMPLETED:'已完成',SUCCESS:'已完成',FAILED:'失败',CANCELLED:'已终止'}[String(status||'').toUpperCase()]||status||'未知')
const showNotice=text=>{notice.value=text;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>notice.value='',5000)}
const parse=value=>{if(typeof value!=='string')return value||{};try{return JSON.parse(value)}catch{return{executionResult:value}}}
const workflow=task=>parse(task.result)
const planSteps=task=>Array.isArray(workflow(task).plan?.steps)?workflow(task).plan.steps:[]
const executions=task=>Array.isArray(workflow(task).executions)?workflow(task).executions:[]
const executionAt=(task,index)=>executions(task)[index]
const stepTitle=(step,index)=>typeof step==='string'?step:(step.title||step.name||step.objective||step.action||`步骤 ${index+1}`)
const stepDescription=step=>typeof step==='object'?(step.description||step.reason||step.instruction||''):''
const executionText=value=>typeof value==='string'?value:(value.output||value.result||value.content||value.message||JSON.stringify(value,null,2))
const stringify=value=>typeof value==='string'?value:JSON.stringify(value,null,2)
const progressValue=task=>Math.max(0,Math.min(100,Number(task.progress)||(['COMPLETED','SUCCESS'].includes(String(task.status).toUpperCase())?100:0)))
const stepState=(task,index)=>executionAt(task,index)?'done':running(task.status)&&index===executions(task).length?'active':'pending'
const stepStateLabel=(task,index)=>executionAt(task,index)?'已执行':running(task.status)&&index===executions(task).length?'执行中':'等待'
const reviewOf=task=>workflow(task).review||task.review||null
const load=async()=>{loading.value=true;try{const {data}=await getTasks({ownerId,type:'AGENT_WORKFLOW'});tasks.value=Array.isArray(data)?data:(data?.items||data?.tasks||[])}catch(error){showNotice(apiError(error))}finally{loading.value=false}}
const create=async()=>{creating.value=true;try{await createMedicalWorkflow({objective:objective.value.trim(),chatId:newChatId('workflow'),ownerId,reviewEnabled:reviewEnabled.value});objective.value='';showNotice('工作流已创建。');await load()}catch(error){showNotice(apiError(error))}finally{creating.value=false}}
const remove=async task=>{if(!window.confirm('确定终止这个工作流任务吗？'))return;try{await cancelTask(task.id);await load()}catch(error){showNotice(apiError(error))}}
onMounted(()=>{load();poll=setInterval(()=>{if(tasks.value.some(task=>running(task.status)))load()},1500)})
onBeforeUnmount(()=>{clearInterval(poll);clearTimeout(noticeTimer)})
const md=value=>DOMPurify.sanitize(marked.parse(String(value||''),{gfm:true,breaks:true,async:false}),{USE_PROFILES:{html:true}})
</script>
<style scoped>
.task-page{min-height:100%;padding:36px clamp(20px,5vw,70px) 70px;background:#f3f7f7;color:#18383d;overflow:auto}.task-page>header{display:flex;align-items:center;gap:25px}.task-page>header>div{flex:1}.task-page>header p{font-size:10px;letter-spacing:1.6px;color:#16808a;font-weight:800}.task-page h1{font-size:32px;margin:5px 0}.task-page>header span,.create p{font-size:12px;color:#789095}.task-page button{border:1px solid #d4e2e2;background:#fff;border-radius:10px;padding:9px 13px;color:#486368}.refresh{color:#087985!important}.create{display:grid;grid-template-columns:230px minmax(260px,1fr) auto auto;gap:16px;align-items:center;margin:32px 0;padding:22px;border-radius:18px;background:#fff;border:1px solid #dce8e8}.create h2,.list-head h2{font-size:17px}.create textarea{height:70px;resize:none;padding:12px;border:1px solid #d3e1e1;border-radius:11px;font:13px/1.5 inherit}.create>button{background:#087985;color:#fff;border:0}.review-toggle{display:flex;align-items:center;gap:7px;color:#58716e;font-size:11px}.review-toggle input{accent-color:#087f75}.list-head{display:flex;justify-content:space-between;align-items:center;margin:25px 0 12px}.list-head span{font-size:12px;color:#789095}.tasks{display:grid;gap:14px}.tasks>article{position:relative;padding:20px;border-radius:16px;background:#fff;border:1px solid #dce7e7}.task-top{display:flex;justify-content:space-between;gap:15px}.task-top small{display:block;margin-top:3px;color:#93a4a7}.status{padding:5px 9px;border-radius:99px;background:#edf2f3;color:#64777b;font-size:10px}.status.running,.status.processing{background:#e0f3f3;color:#087985}.status.completed,.status.success{background:#e3f4e8;color:#287342}.status.failed{background:#fee7e7;color:#a62b2b}.progress{height:5px;margin:16px 0 8px;background:#e6eeee;border-radius:99px;overflow:hidden}.progress i{display:block;height:100%;background:#087f8c}.task-meta{display:flex;gap:18px;color:#7d9194;font-size:10px}.dynamic-plan,.integrated-result{margin-top:16px;padding:14px;border:1px solid #e2ebea;border-radius:12px}.dynamic-plan h3,.integrated-result h3{font-size:12px;color:#315d5a;margin-bottom:10px}.dynamic-plan h3 span{margin-left:6px;color:#91a19e;font-weight:400}.plan-step{display:grid;grid-template-columns:24px 1fr auto;gap:9px;padding:9px 0;border-top:1px solid #edf2f1}.plan-step>b{width:22px;height:22px;display:grid;place-items:center;border-radius:7px;background:#edf3f2;color:#718582;font-size:10px}.plan-step>div>strong{display:block;font-size:11px}.plan-step small,.plan-step em{color:#899a98;font:normal 9px inherit}.plan-step.done>b,.plan-step.active>b{background:#dff2ee;color:#08786d}.plan-step.active em{color:#08786d}.plan-step .markdown{margin-top:7px;padding:8px;background:#f6f9f8;border-radius:7px;color:#536d69;font-size:10px}.planning{margin-top:14px;padding:11px;border-radius:9px;background:#f0f6f5;color:#66807c;font-size:11px}.integrated-result .markdown{font-size:12px;line-height:1.65}.markdown :deep(ul),.markdown :deep(ol){padding-left:18px}.markdown :deep(pre){overflow:auto;padding:9px;background:#173033;color:#e7f2f1;border-radius:8px}.review{display:grid;grid-template-columns:1fr auto;gap:7px 14px;margin-top:12px;padding:12px;border-radius:10px;font-size:11px}.review>div span{display:block;margin-top:3px}.review .human{padding:4px 8px;border-radius:7px;background:#a85c00;color:#fff}.review ul,.review p{grid-column:1/-1;padding-left:17px}.review.passed{background:#eaf7ee;color:#277242}.review.attention{background:#fff2dc;color:#875b08}.task-error{margin-top:10px;padding:9px;background:#feeaea;color:#9d2929;border-radius:8px;font-size:11px}.events{margin-top:10px;color:#708481;font-size:10px}.events ol{padding:8px 0 0 22px}.cancel{margin-top:12px;color:#b32e2e!important}.empty{text-align:center;padding:70px;color:#91a2a5}.notice{position:fixed;top:20px;left:50%;z-index:20;transform:translateX(-50%);background:#18383d;color:#fff;padding:10px 15px;border-radius:9px}@media(max-width:900px){.create{grid-template-columns:1fr}.task-page>header{align-items:flex-start}.task-page>header>button:first-child{display:none}.task-meta{flex-wrap:wrap}.review{grid-template-columns:1fr}}
</style>
