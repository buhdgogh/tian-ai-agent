<template>
  <main class="login-page">
    <form class="login-card" @submit.prevent="submit">
      <div class="brand">⚕ 天医</div>
      <h1>登录</h1>
      <p>登录后查看你的对话与任务记录。</p>
      <label for="username">用户名</label>
      <input id="username" v-model="username" name="username" autocomplete="username" maxlength="128" required :disabled="busy" autofocus />
      <label for="password">密码</label>
      <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" maxlength="256" required :disabled="busy" />
      <div v-if="error" class="login-error" role="alert">{{ error }}</div>
      <button type="submit" :disabled="busy || !username.trim() || !password">{{ busy ? '正在登录…' : '登录' }}</button>
    </form>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { signIn } from '../stores/user'
import { apiError } from '../utils/medical'
import { loginDestination } from '../utils/auth'

const route = useRoute(), router = useRouter()
const username = ref(''), password = ref(''), busy = ref(false), error = ref('')
const submit = async () => {
  if (busy.value || !username.value.trim() || !password.value) return
  busy.value = true
  error.value = ''
  try {
    await signIn({ username: username.value.trim(), password: password.value })
    await router.replace(loginDestination(route.query.redirect))
  } catch (failure) {
    error.value = failure.response?.status === 401 ? '用户名或密码错误，请重新输入。' : apiError(failure)
  } finally {
    password.value = ''
    busy.value = false
  }
}
</script>

<style scoped>
.login-page{flex:1;min-height:100%;display:grid;place-items:center;padding:30px 20px;background:radial-gradient(circle at 80% 10%,#d9f0ef,transparent 55%),#f3f7f7}.login-card{width:100%;max-width:400px;padding:34px;border:1px solid #dce7e7;border-radius:22px;background:#fff;box-shadow:0 18px 50px #163e4610}.brand{color:#087985;font-size:22px;font-weight:700}.login-card h1{margin:24px 0 8px;font-size:28px}.login-card p{font-size:13px;color:#718589;margin-bottom:26px}.login-card label{display:block;margin:16px 0 7px;font-size:13px;color:#405e63}.login-card input{width:100%;padding:11px 12px;border:1px solid #cddddd;border-radius:9px;font:inherit;outline-color:#087985}.login-card button{width:100%;margin-top:24px;padding:12px;border:0;border-radius:10px;background:#087985;color:#fff;font-size:15px}.login-card button:disabled{opacity:.55;cursor:default}.login-error{margin-top:16px;color:#b33232;font-size:13px;line-height:1.6}
</style>
