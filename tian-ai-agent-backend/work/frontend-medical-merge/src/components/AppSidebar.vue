<template>
  <aside class="sidebar" :class="aiType">
    <div class="sidebar-top">
      <div class="logo-container" @click="goHome">
        <div class="logo-icon">{{ aiType === 'medical' ? '⚕' : '🔬' }}</div>
        <div class="logo-text">
          <h2>天医医疗助手</h2>
          <p>健康问答、循证检索与深度分析</p>
        </div>
      </div>
      
      <button class="new-chat-btn" :disabled="isCreating" @click="startNewChat">
        <span class="plus-icon">+</span> {{ isCreating ? '正在创建…' : '开启新对话' }}
      </button>
    </div>

    <div class="history-container">
      <div class="history-title">对话记录</div>
      <div class="history-group">
        <div class="group-label">历史会话</div>
        <div class="history-list">
          <div
            v-for="(chat, index) in historyList" 
            :key="chat.id || index"
            class="history-item"
            :class="{ active: currentChatId === chat.id }"
            @click="selectChat(chat)"
          >
            <span class="chat-icon">💬</span>
            <span class="chat-title">{{ chat.title }}</span>
            <button class="chat-delete" title="删除对话" aria-label="删除对话" @click.stop="deleteChat(chat)">×</button>
          </div>
        </div>
      </div>
    </div>

    <div class="sidebar-bottom">
      <p class="group-label">当前用户：{{ username || '正在加载…' }}</p>
      <button class="clear-btn" @click="clearChat">
        <span class="trash-icon">🗑️</span> 清空当前对话
      </button>
    </div>
  </aside>
</template>

<script setup>
import { useRouter } from 'vue-router'

const props = defineProps({
  username: String,
  isCreating: Boolean,
  currentChatId: String,
  historyList: {
    type: Array,
    default: () => []
  },
  aiType: {
    type: String,
    default: 'default'
  }
})

const emit = defineEmits(['new-chat', 'select-chat', 'delete-chat', 'clear-chat'])
const router = useRouter()

const goHome = () => {
  router.push('/')
}

const startNewChat = () => {
  emit('new-chat')
}

const selectChat = (chat) => {
  emit('select-chat', chat)
}

const deleteChat = (chat) => {
  emit('delete-chat', chat)
}

const clearChat = () => {
  emit('clear-chat')
}
</script>

<style scoped>
.sidebar {
  width: 260px;
  height: 100%;
  background-color: #ffffff;
  border-right: 1px solid rgba(0, 0, 0, 0.05);
  display: flex; 
  flex-direction: column;
  flex-shrink: 0;
  z-index: 100;
  color: #1e293b;
}

.sidebar-top {
  padding: 24px 16px;
}

.logo-container {
  display: flex;
  align-items: center;
  margin-bottom: 32px;
  cursor: pointer;
}

.logo-icon {
  font-size: 32px;
  margin-right: 12px;
}

.logo-text h2 {
  font-size: 20px;
  font-weight: 700;
  color: #1e293b;
}

.logo-text p {
  font-size: 12px;
  color: #64748b;
  margin-top: 2px;
}

.new-chat-btn {
  width: 100%;
  height: 48px;
  background: var(--btn-bg, #1890ff);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
  box-shadow: 0 4px 12px var(--btn-shadow, rgba(24, 144, 255, 0.2));
}

.sidebar.medical {
  --btn-bg: #0b7285;
  --btn-shadow: rgba(11, 114, 133, 0.2);
  --active-bg: #ecfeff;
  --active-text: #0e7490;
}

.sidebar.agent {
  --btn-bg: #1890ff;
  --btn-shadow: rgba(24, 144, 255, 0.2);
  --active-bg: #eff6ff;
  --active-text: #1d4ed8;
}

.new-chat-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px var(--btn-shadow, rgba(24, 144, 255, 0.3));
  filter: brightness(1.05);
}

.plus-icon {
  font-size: 20px;
  margin-right: 8px;
}

.history-container {
  flex: 1;
  overflow-y: auto;
  padding: 0 12px;
}

.history-title {
  font-size: 12px;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 24px 12px 12px;
}

.group-label {
  font-size: 11px;
  color: #94a3b8;
  margin: 16px 12px 8px;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.history-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  color: #475569;
}

.history-item:hover {
  background-color: #f1f5f9;
  color: #1e293b;
}

.history-item.active {
  background-color: var(--active-bg, #eff6ff);
  color: var(--active-text, #1d4ed8);
  font-weight: 500;
}

.chat-icon {
  margin-right: 12px;
  font-size: 16px;
  opacity: 0.7;
}

.chat-title {
  flex: 1;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-delete {
  opacity: 0;
  transition: opacity 0.2s;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #94a3b8;
  font-size: 18px;
  line-height: 1;
}

.history-item:hover .chat-delete,
.chat-delete:focus-visible {
  opacity: 1;
}

.chat-delete:hover {
  background: #fee2e2;
  color: #dc2626;
}

.sidebar-bottom {
  padding: 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.05);
}

.clear-btn {
  width: 100%;
  height: 40px;
  background: transparent;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  color: #64748b;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.clear-btn:hover {
  background-color: #fff1f2;
  border-color: #fecdd3;
  color: #e11d48;
}

.trash-icon {
  margin-right: 8px;
}
</style>
