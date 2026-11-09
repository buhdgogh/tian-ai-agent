<template>
  <aside class="sidebar" aria-label="对话历史">
    <div class="sidebar-head">
      <div><h2>对话记录</h2><p>{{ medical ? '医疗空间' : '当前知识库范围' }}</p></div>
      <button class="ui-button primary" type="button" @click="emit('new-chat')">
        <WorkspaceIcon name="plus" /> 新对话
      </button>
    </div>
    <p v-if="!historyList.length" class="history-empty">暂无对话</p>
    <ul v-else class="history-list">
      <li v-for="chat in historyList" :key="chat.id" :class="{ active: currentChatId === chat.id }">
        <button type="button" class="history-title" :title="chat.title" :aria-current="currentChatId === chat.id ? 'true' : undefined"
          @click="emit('select-chat', chat)">
          <WorkspaceIcon name="agent" /><span>{{ chat.title || '未命名对话' }}</span>
        </button>
        <button class="history-delete" type="button" :aria-label="'删除' + (chat.title || '对话')" title="删除对话"
          @click="emit('delete-chat', chat)">×</button>
      </li>
    </ul>
    <button v-if="currentChatId" class="clear-button" type="button" @click="emit('clear-chat')">清空当前对话</button>
  </aside>
</template>

<script setup>
import WorkspaceIcon from './WorkspaceIcon.vue'

defineProps({
  currentChatId: String,
  medical: Boolean,
  historyList: { type: Array, default: () => [] }
})
const emit = defineEmits(['new-chat', 'select-chat', 'delete-chat', 'clear-chat'])
</script>

<style scoped>
.sidebar {
  display: flex;
  flex: 0 0 248px;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  gap: 14px;
  padding: 17px 14px;
  border-right: 1px solid var(--line);
  background: var(--surface);
}
.sidebar-head { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
h2 { font-size: 14px; font-weight: 750; }
p { font-size: 11px; color: var(--muted); }
.sidebar-head .ui-button { min-height: 32px; padding: 5px 8px; font-size: 12px; }
.sidebar-head svg { width: 14px; height: 14px; }
.history-list { overflow: auto; min-height: 0; list-style: none; }
.history-list li { display: flex; align-items: center; gap: 2px; margin-bottom: 4px; border-radius: 8px; }
.history-list li:hover { background: var(--surface-muted); }
.history-list li.active { background: var(--accent-soft); color: var(--accent-ink); }
.history-title,.history-delete { border: 0; background: transparent; color: inherit; }
.history-title { display: flex; flex: 1; align-items: center; gap: 8px; min-width: 0; padding: 8px; text-align: left; font-size: 12px; }
.history-title svg { flex: none; width: 15px; height: 15px; opacity: .8; }
.history-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-delete { flex: none; padding: 1px 8px; border-radius: 5px; color: var(--muted); font-size: 18px; }
.history-delete:hover { color: var(--danger); background: var(--danger-soft); }
.clear-button { margin-top:auto; padding:7px 0; border:0; background:transparent; color:var(--muted); text-align:left; font-size:12px; }
.clear-button:hover { color:var(--danger); }
.history-empty { padding: 10px; border-radius: 8px; background: var(--surface-muted); text-align: center; }
@media (max-width: 720px) {
  .sidebar { flex: none; gap: 6px; padding: 8px 12px; border-right: 0; border-bottom: 1px solid var(--line); }
  .sidebar-head p,.history-empty { display: none; }
  .history-list { display: flex; gap: 5px; }
  .history-list li { flex: 0 0 auto; max-width: 180px; margin: 0; background: var(--surface-muted); }
}
</style>
