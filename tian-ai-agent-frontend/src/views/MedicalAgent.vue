<template>
  <div class="agent-page">
    <div class="agent-head">
      <div><b>深度循证分析</b><span>确定性急症检查 · 动态规划 · 多源检索 · 质量复核</span></div>
      <i v-if="status === 'connecting'">正在执行动态医疗信息任务</i>
    </div>
    <ChatRoom :messages="messages" :connection-status="status" ai-type="agent" theme-color="#315ea8" theme-color-rgb="49,94,168"
      :emergency-text="emergency" title="深度循证分析" subtitle="面向复杂医疗信息任务，动态检索并核对多来源证据" welcome-icon="🔬"
      :suggestions="['梳理这些症状的危险信号并给出就医准备清单','检索证据并比较两种常见处理方案','对这份健康建议执行临床安全复核']"
      @send-message="send" @stop-generation="stop"/>
  </div>
</template>
<script setup>
import {onBeforeUnmount,ref,watch} from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import {getSessionMessages,streamMedicalAgent} from '../api'
import {apiError,emergencyMessage,newChatId,normalizeHistory,parseAgentEvent} from '../utils/medical'
const props=defineProps({chatIdProp:String}),emit=defineEmits(['update-history','update-chat-id'])
const messages=ref([]),status=ref('disconnected'),emergency=ref('')
const locallyCreatedChatId=ref('')
let controller=null
const ensureId=()=>{if(props.chatIdProp)return props.chatIdProp;const value=newChatId('agent');locallyCreatedChatId.value=value;emit('update-chat-id',value);return value}
const send=async text=>{
  if(status.value==='connecting')return
  emergency.value=''
  messages.value.push({content:text,isUser:true,time:Date.now()})
  messages.value.push({content:'',isUser:false,time:Date.now(),trace:[]})
  const target=messages.value.at(-1)
  status.value='connecting'
  controller=new AbortController()
  const chatId=ensureId()
  try{
    await streamMedicalAgent({message:text,chatId},raw=>{
      const part=parseAgentEvent(raw)
      if(part.trace.length)target.trace.push(...part.trace.map((event,index)=>({...event,id:`${Date.now()}-${index}`})))
      if(part.text)target.content+=(target.content?'\n\n':'')+part.text
      if(part.sources.length)target.sources=part.sources
      if(part.report)target.report=part.report
      const hit=emergencyMessage(part.report||target.content)
      if(hit){emergency.value=hit;status.value='emergency';controller?.abort()}
    },controller.signal)
    if(!target.content.trim()&&!target.sources?.length&&!target.report)target.content='### Agent 未返回结果\n\n本次执行没有产生有效内容，请稍后重试。'
    emit('update-history')
  }catch(error){
    if(error?.name==='AbortError')target.incomplete=true
    else if(target.content){target.incomplete=true;target.content+=`\n\n---\n\n**流式连接中断：** ${apiError(error)}`}
    else target.content=`### Agent 执行未完成\n\n${apiError(error)}`
  }finally{
    controller=null
    if(status.value!=='emergency')status.value='disconnected'
  }
}
const stop=()=>{controller?.abort();status.value='disconnected'}
watch(()=>props.chatIdProp,async value=>{
  // 忽略本轮发送刚创建并回传的 ID，避免 watcher 中止自己的请求并清空消息。
  if(value&&value===locallyCreatedChatId.value){locallyCreatedChatId.value='';return}
  controller?.abort();messages.value=[];emergency.value=''
  if(!value)return
  try{const {data}=await getSessionMessages(value,'agent');messages.value=normalizeHistory(data)}catch(error){messages.value=[{content:apiError(error),isUser:false,time:Date.now()}]}
},{immediate:true})
onBeforeUnmount(()=>controller?.abort())
</script>
<style scoped>
.agent-page{height:100%;display:flex;flex-direction:column}.agent-head{min-height:66px;padding:10px 24px;background:#fff;border-bottom:1px solid #e2e8f0;display:flex;align-items:center;justify-content:space-between}.agent-head b{display:block;color:#234b80}.agent-head span{font-size:11px;color:#7a8da5}.agent-head>i{padding:6px 10px;border-radius:99px;background:#e5edff;color:#315ea8;font:normal 11px inherit}@media(max-width:700px){.agent-head>i{display:none}}
</style>
