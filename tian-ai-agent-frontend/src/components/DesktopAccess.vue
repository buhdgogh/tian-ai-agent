<template>
  <dialog ref="dialog" class="desktop-access ui-panel" aria-labelledby="desktop-access-title" @cancel.prevent="cancel">
    <form @submit.prevent="connect">
      <h2 id="desktop-access-title">连接 Tian 知识工作台</h2>
      <p>填写管理员提供的后端地址和访问令牌。连接成功后，你可以在模型设置中添加自己的 API Key。</p>
      <label for="desktop-api-url">后端 API 地址</label>
      <input id="desktop-api-url" v-model="address" class="ui-field" type="url" maxlength="500" required
        placeholder="https://agent.example.com/api" :disabled="busy" />
      <label for="desktop-api-token">访问令牌</label>
      <input id="desktop-api-token" v-model="token" class="ui-field" type="password" autocomplete="off"
        :placeholder="required ? '输入工作台访问令牌' : '同一后端可留空沿用当前令牌'" :disabled="busy" />
      <p v-if="error" class="ui-notice error" role="alert">{{ error }}</p>
      <div class="actions">
        <button v-if="!required" class="ui-button" type="button" :disabled="busy" @click="cancel">取消</button>
        <button class="ui-button primary" type="submit" :disabled="busy">{{ busy ? '正在验证…' : '验证并连接' }}</button>
      </div>
    </form>
  </dialog>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { connectDesktop, desktopApiSuggestion, savedDesktopApi } from '../api'

const props = defineProps({ required: Boolean })
const emit = defineEmits(['cancel', 'connected'])
const dialog = ref(null)
const address = ref(savedDesktopApi || desktopApiSuggestion)
const token = ref('')
const busy = ref(false)
const error = ref('')

onMounted(() => dialog.value.showModal())

function cancel() {
  if (!props.required && !busy.value) emit('cancel')
}

async function connect() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    emit('connected', await connectDesktop(address.value, token.value))
  } catch (failure) {
    error.value = failure.message
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.desktop-access { width: min(450px, calc(100vw - 36px)); padding: 22px; color: var(--ink); }
.desktop-access::backdrop { background: #16203870; }
form { display: grid; gap: 10px; }
h2 { margin: 0; font-size: 18px; }
p { margin: 0; font-size: 13px; }
label { font-size: 13px; font-weight: 650; }
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
</style>
