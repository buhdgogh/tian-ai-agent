<template>
  <div class="medical-page">
    <div class="mode-bar">
      <div><span>回答模式</span><button v-for="item in modes" :key="item.id" :class="{active:mode===item.id}" :disabled="status==='connecting'" @click="mode=item.id">{{ item.label }}</button></div>
      <small>{{ modeHint }}</small>
    </div>
    <ChatRoom ref="chat" :messages="messages" :connection-status="status" ai-type="medical" :emergency-text="emergency"
      title="天医医疗信息助手" subtitle="先识别危险信号，再结合可追溯资料解释健康信息" welcome-icon="⚕"
      :suggestions="['这些症状有哪些需要立即就医的危险信号？','结合知识库解释这份检查结果','生成一份结构化循证安全报告']"
      @send-message="send" @stop-generation="stop" />
  </div>
</template>
<script setup>
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import {chatMedicalRag,createMedicalEvidenceReport,getSessionMessages,streamMedicalAssistant} from '../api'
import {apiError,chunkText,emergencyMessage,newChatId,normalizeHistory,normalizeSources,retryAfterSeconds} from '../utils/medical'
const props=defineProps({chatIdProp:String}),emit=defineEmits(['update-history','update-chat-id'])
const messages=ref([]),status=ref('disconnected'),emergency=ref(''),mode=ref('chat')
const locallyCreatedChatId=ref('')
let controller=null
const countdownTimers=new Set()
const modes=[{id:'chat',label:'安全问答'},{id:'rag',label:'循证检索'},{id:'report',label:'安全报告'}]
const modeHint=computed(()=>({chat:'流式回答，并实时检查紧急风险',rag:'基于已审核知识库生成带来源的回答',report:'输出风险、证据、不确定性与下一步'}[mode.value]))
const ensureId=()=>{if(props.chatIdProp)return props.chatIdProp;const id=newChatId('medical');locallyCreatedChatId.value=id;emit('update-chat-id',id);return id}
const userPayload=(text,id)=>({message:text,question:text,chatId:id,sessionId:id})
const addError=error=>{
  const retry=retryAfterSeconds(error)
  const item={content:`### 请求未完成\n\n${apiError(error)}`,isUser:false,time:Date.now()}
  messages.value.push(item)
  if(retry){let remaining=retry;item.content=`### 请求过于频繁\n\n请在 **${remaining} 秒**后重试。`;const timer=setInterval(()=>{remaining--;item.content=remaining>0?`### 请求过于频繁\n\n请在 **${remaining} 秒**后重试。`:'### 可以重试\n\n限流等待已结束，现在可以重新发送。';if(remaining<=0){clearInterval(timer);countdownTimers.delete(timer)}},1000);countdownTimers.add(timer)}
}
const checkEmergency=value=>{const hit=emergencyMessage(value);if(hit){emergency.value=hit;status.value='emergency';controller?.abort()}}
const send=async text=>{
  if(status.value==='connecting')return
  emergency.value='';const id=ensureId();messages.value.push({content:text,isUser:true,time:Date.now()});status.value='connecting'
  try{
    if(mode.value==='chat'){
      messages.value.push({content:'',isUser:false,time:Date.now()});const target=messages.value.at(-1);controller=new AbortController()
      await streamMedicalAssistant(userPayload(text,id),raw=>{const part=chunkText(raw);target.content+=part.text; if(part.sources.length)target.sources=part.sources;if(part.report)target.report=part.report;checkEmergency(part.report||target.content)},controller.signal)
      if(!target.content.trim()&&!target.sources?.length&&!target.report)target.content='### 未获取到回答\n\n服务未返回有效内容，请稍后重试。'
    }else{
      const request=mode.value==='rag'?chatMedicalRag:createMedicalEvidenceReport
      const {data}=await request(userPayload(text,id));const report=mode.value==='report'?(data?.report||data):data?.report
      const content=typeof data==='string'?data:(data?.answer||data?.content||data?.message||(report?'报告已生成。':'未获取到回答。'))
      const sources=normalizeSources(data?.sources||report?.sources)
      messages.value.push({content,isUser:false,time:Date.now(),sources,report,ragMeta:mode.value==='rag'?{plan:data?.plan,validation:data?.validation,retrieval:data?.retrieval,riskLevel:data?.riskLevel}:null,evidenceEmpty:mode.value==='rag'&&!sources.length})
      checkEmergency(data||report||content)
    }
    emit('update-history')
  }catch(error){const last=messages.value.at(-1);if(error?.name==='AbortError'){if(last&&!last.isUser)last.incomplete=true}else{if(last&&!last.isUser&&last.content)last.incomplete=true;addError(error)}}
  finally{controller=null;if(status.value!=='emergency')status.value='disconnected'}
}
const stop=()=>{controller?.abort();status.value='disconnected'}
watch(()=>props.chatIdProp,async id=>{
  // 新会话 ID 是本组件刚创建的，不应当被当成“切换历史会话”而中断当前流式请求。
  if(id&&id===locallyCreatedChatId.value){locallyCreatedChatId.value='';return}
  controller?.abort();emergency.value='';messages.value=[]
  if(!id)return
  try{const {data}=await getSessionMessages(id,'medical');messages.value=normalizeHistory(data)}catch(error){addError(error)}
},{immediate:true})
onBeforeUnmount(()=>{controller?.abort();countdownTimers.forEach(timer=>clearInterval(timer))})
</script>
<style scoped>.medical-page{height:100%;display:flex;flex-direction:column}.mode-bar{min-height:58px;padding:8px 24px;display:flex;align-items:center;justify-content:space-between;gap:15px;background:#fff;border-bottom:1px solid #e0eaea}.mode-bar>div{display:flex;align-items:center;gap:6px}.mode-bar span{font-size:12px;color:#718589;margin-right:6px}.mode-bar button{border:0;background:#f0f5f5;color:#577075;padding:7px 11px;border-radius:9px;font:12px inherit}.mode-bar button.active{background:#087f8c;color:#fff}.mode-bar small{color:#718589}@media(max-width:700px){.mode-bar{align-items:flex-start;overflow:auto}.mode-bar small,.mode-bar span{display:none}.mode-bar>div{white-space:nowrap}}</style>
