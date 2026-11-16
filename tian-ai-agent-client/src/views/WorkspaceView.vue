<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeading from '../components/PageHeading.vue'
import WorkspaceTree from '../components/WorkspaceTree.vue'
import { useWorkspaceStore } from '../stores/workspace'

const store = useWorkspaceStore()
const command = ref('')
onMounted(() => { void store.refreshSandbox().catch(() => {}) })
</script>

<template>
  <main class="page stack">
    <PageHeading title="工作区" detail="挂载本地目录后，在容器中浏览文件并执行命令。">
      <button type="button" :disabled="store.checking" @click="store.check">检测 Docker</button>
    </PageHeading>
    <section class="panel stack" aria-label="Docker 状态">
      <div class="row spread">
        <h2>Docker</h2>
        <span class="badge">{{ store.checking ? '检测中' : store.docker?.available ? 'Linux 引擎可用' : '未连接' }}</span>
      </div>
      <p class="muted" role="status">{{ store.docker?.detail || '正在检查本机 Docker…' }}</p>
    </section>
    <section class="panel stack" aria-label="工作区目录">
      <div>
        <h2>挂载目录</h2>
        <p class="muted">容器通过 /workspace 读写该目录，写入会同步到本机。</p>
      </div>
      <code v-if="store.path">{{ store.path }}</code>
      <p v-else class="muted">尚未选择目录</p>
      <div class="row">
        <button type="button" :disabled="!!store.sandbox || !!store.pending" @click="store.choose">选择目录</button>
        <button v-if="!store.sandbox" type="button" class="primary"
          :disabled="!store.path || !store.docker?.available || !!store.pending" @click="store.create">
          {{ store.pending === 'create' ? '正在准备镜像和容器…' : '创建沙箱' }}
        </button>
        <button v-else type="button" class="danger" :disabled="!!store.pending" @click="store.stop">
          {{ store.pending === 'stop' ? '正在清理…' : '停止并清理' }}
        </button>
      </div>
      <p v-if="store.sandbox" class="row" role="status">
        <span class="badge">{{ store.sandbox.status === 'running' ? '运行中' : '待清理' }}</span>
        <code>{{ store.sandbox.id.slice(0, 12) }}</code> · {{ store.sandbox.image }}
      </p>
      <p v-if="store.error" class="error" role="alert">{{ store.error }}</p>
    </section>
    <div v-if="store.sandbox?.status === 'running'" class="columns">
      <section class="panel stack" aria-label="文件树">
        <div class="row spread"><h2>/workspace</h2><button type="button" :disabled="!!store.pending" @click="store.revision++">刷新</button></div>
        <div class="file-scroll"><WorkspaceTree :sandbox-id="store.sandbox.id" path="/workspace" :revision="store.revision" /></div>
      </section>
      <form class="panel form-stack" @submit.prevent="store.execute(command)">
        <h2>容器命令</h2>
        <label class="field">Shell 命令<textarea v-model="command" rows="3" placeholder="例如：pwd; ls -a" /></label>
        <div><button type="submit" class="primary" :disabled="!command.trim() || !!store.pending">{{ store.pending === 'exec' ? '执行中…' : '执行' }}</button></div>
        <div v-if="store.result" class="stack" aria-live="polite">
          <p><span class="badge">退出码 {{ store.result.exitCode }}</span></p>
          <p v-if="store.result.truncated" class="muted">部分输出超过显示上限。</p>
          <div><h3>标准输出</h3><pre>{{ store.result.stdout || '（无输出）' }}</pre></div>
          <div><h3>标准错误</h3><pre>{{ store.result.stderr || '（无输出）' }}</pre></div>
        </div>
      </form>
    </div>
  </main>
</template>

<style scoped>
.file-scroll { max-height: 45vh; overflow: auto; }
pre { margin: .35rem 0 0; padding: .55rem; max-height: 28vh; overflow: auto; background: #f2f6f6; border-radius: .35rem; }
</style>
