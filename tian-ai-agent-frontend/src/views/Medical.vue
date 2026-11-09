<template>
  <div class="medical-page">
    <div class="mode-bar">
      <div><span>回答模式</span><button v-for="item in modes" :key="item.id" :class="{active:mode===item.id}" :disabled="status==='connecting'" @click="mode=item.id">{{ item.label }}</button></div>
      <small>{{ modeHint }}</small>
    </div>
    <ChatRoom ref="chat" :messages="messages" :connection-status="status" ai-type="medical" :emergency-text="emergency"
      :medical="medical" :title="medical ? '医疗空间问答' : '知识助手'"
      :subtitle="medical ? '基于当前医疗空间整理资料与来源' : '选择一个空间可检索其中的全部文档，也可直接对话'"
      :welcome-icon="medical ? '⚕' : '◇'" :suggestions="suggestions"
      @send-message="send" @stop-generation="stop" />
  </div>
</template>
<script setup>
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import {chatWithTools,chatWithKnowledge,createMedicalEvidenceReport,getSessionMessages,streamKnowledgeChat} from '../api'
import {apiError,chunkText,emergencyMessage,newChatId,normalizeHistory,normalizeSources,retryAfterSeconds} from '../utils/medical'
const props=defineProps({chatIdProp:String,spaceId:String,medical:Boolean,modeProp:{type:String,default:'chat'}})
const emit=defineEmits(['update-history','update-chat-id','update-mode'])
const messages=ref([]),status=ref('disconnected'),emergency=ref('')
const mode=computed({get:()=>props.modeProp,set:value=>emit('update-mode',value)})
const historyType=computed(()=>mode.value==='rag'?'rag':'medical')
const locallyCreatedChatId=ref('')
let controller=null
let requestVersion=0
const countdownTimers=new Set()
const modes=computed(()=>[
  {id:'chat',label:'对话'},
  ...(props.spaceId ? [{id:'rag',label:'引用问答'}] : []),
  {id:'tool',label:'工具问答'},
  ...(props.medical ? [{id:'report',label:'医疗安全报告'}] : [])
])
const suggestions=computed(()=>props.medical
  ? ['整理这份指南的适用范围', '结合当前空间比较两份指南的建议']
  : props.spaceId ? ['概括当前空间中与报销有关的规定', '查找相关文档并列出引用']
    : ['把这份会议记录整理成待办事项', '帮我解释一个概念并举例'])
const modeHint=computed(()=>({
  chat:props.spaceId?'根据当前空间资料流式回答':'直接对话，当前未选择知识库',
  rag:'从当前整个空间召回相关片段，并给出引用来源',
  tool:'按需调用受控工具',
  report:'整理医疗风险、证据和下一步'
}[mode.value]))
const ensureId=()=>{if(props.chatIdProp)return props.chatIdProp;const id=newChatId('chat');locallyCreatedChatId.value=id;emit('update-chat-id',id);return id}
const userPayload=(text,id)=>({message:text,chatId:id,spaceId:props.spaceId})
const addError=error=>{
  const retry=retryAfterSeconds(error)
  const item={content:`### 请求未完成\n\n${apiError(error)}`,isUser:false,time:Date.now()}
  messages.value.push(item)
  if(retry){let remaining=retry;item.content=`### 请求过于频繁\n\n请在 **${remaining} 秒**后重试。`;const timer=setInterval(()=>{remaining--;item.content=remaining>0?`### 请求过于频繁\n\n请在 **${remaining} 秒**后重试。`:'### 可以重试\n\n限流等待已结束，现在可以重新发送。';if(remaining<=0){clearInterval(timer);countdownTimers.delete(timer)}},1000);countdownTimers.add(timer)}
}
const checkEmergency=value=>{const hit=props.medical&&emergencyMessage(value);if(hit){emergency.value=hit;status.value='emergency';controller?.abort()}}
const send=async text=>{
  if(status.value==='connecting')return
  const version=++requestVersion
  emergency.value='';const id=ensureId();messages.value.push({content:text,isUser:true,time:Date.now()});status.value='connecting'
  controller=new AbortController()
  try{
    if(mode.value==='chat'){
      messages.value.push({content:'',isUser:false,time:Date.now()});const target=messages.value.at(-1)
      await streamKnowledgeChat(userPayload(text,id),raw=>{if(version!==requestVersion)return;const part=chunkText(raw);target.content+=part.text; if(part.sources.length)target.sources=part.sources;if(part.report)target.report=part.report;checkEmergency(part.report||target.content)},controller.signal)
      if(version!==requestVersion)return
      if(!target.content.trim()&&!target.sources?.length&&!target.report)target.content='### 未获取到回答\n\n服务未返回有效内容，请稍后重试。'
    }else{
      const request=mode.value==='rag'?chatWithKnowledge:mode.value==='tool'?chatWithTools:createMedicalEvidenceReport
      const {data}=await request(userPayload(text,id),controller.signal)
      if(version!==requestVersion)return
      const report=mode.value==='report'?(data?.report||data):data?.report
      const content=typeof data==='string'?data:(data?.answer||data?.content||data?.message||(report?'报告已生成。':'未获取到回答。'))
      const sources=normalizeSources(data?.sources||report?.sources)
      const ragMeta=mode.value==='rag'&&(data?.retrieval_mode||data?.evidence_level||data?.riskLevel||data?.requires_human_review)
        ?{mode:data.retrieval_mode,level:data.evidence_level,reason:data.evidence_reason,requiresReview:data.requires_human_review,riskLevel:data.riskLevel}
        :null
      messages.value.push({content:content.trim()||'未获取到回答。',isUser:false,time:Date.now(),sources,report,ragMeta,evidenceEmpty:mode.value==='rag'&&Boolean(data?.retrieval_mode)&&!sources.length})
      checkEmergency(data||report||content)
    }
    emit('update-history')
  }catch(error){if(version!==requestVersion)return;const last=messages.value.at(-1);if(last&&!last.isUser&&!last.content&&!last.sources?.length&&!last.report)messages.value.pop();else if(last&&!last.isUser)last.incomplete=true;if(error?.name!=='AbortError'&&error?.code!=='ERR_CANCELED')addError(error)}
  finally{if(version===requestVersion){controller=null;if(status.value!=='emergency')status.value='disconnected'}}
}
const stop=()=>{controller?.abort();status.value='disconnected'}
watch([()=>props.chatIdProp,()=>props.spaceId,historyType],async ([id,space,type])=>{
  // 新会话 ID 是本组件刚创建的，不应当被当成“切换历史会话”而中断当前流式请求。
  if(id&&id===locallyCreatedChatId.value){locallyCreatedChatId.value='';return}
  const version=++requestVersion
  controller?.abort();controller=null;status.value='disconnected';emergency.value='';messages.value=[]
  if(!id)return
  try{const {data}=await getSessionMessages(id,type,space);if(version===requestVersion)messages.value=normalizeHistory(data)}catch(error){if(version===requestVersion)addError(error)}
},{immediate:true})
onBeforeUnmount(()=>{requestVersion++;controller?.abort();countdownTimers.forEach(timer=>clearInterval(timer))})
</script>
<style scoped>.medical-page{height:100%;display:flex;flex-direction:column}.mode-bar{min-height:58px;padding:8px 24px;display:flex;align-items:center;justify-content:space-between;gap:15px;background:#fff;border-bottom:1px solid #e0eaea}.mode-bar>div{display:flex;align-items:center;gap:6px}.mode-bar span{font-size:12px;color:#718589;margin-right:6px}.mode-bar button{border:0;background:#f0f5f5;color:#577075;padding:7px 11px;border-radius:9px;font:12px inherit}.mode-bar button.active{background:#087f8c;color:#fff}.mode-bar small{color:#718589}@media(max-width:700px){.mode-bar{align-items:flex-start;overflow:auto}.mode-bar small,.mode-bar span{display:none}.mode-bar>div{white-space:nowrap}}</style>
