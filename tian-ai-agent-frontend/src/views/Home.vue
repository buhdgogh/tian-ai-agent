<template>
  <main class="home">
    <nav class="home-nav">
      <RouterLink to="/" class="brand"><span>◇</span> Tian 知识工作台</RouterLink>
      <div><RouterLink to="/tasks">任务中心</RouterLink><RouterLink to="/agent-runs">运行审计</RouterLink></div>
    </nav>

    <section class="hero">
      <div>
        <p class="eyebrow">KNOWLEDGE WORKSPACE</p>
        <h1>把资料整理成<br />可检索的知识库</h1>
        <p>为不同主题创建独立空间。上传文档后，可以直接提问、核对引用，或让智能体完成多步任务。</p>
        <RouterLink class="primary" to="/knowledge-base">管理知识库空间 <span>→</span></RouterLink>
      </div>
      <div class="scope-card">
        <h2>每次选择一个完整空间</h2>
        <div><span>01</span><p><b>创建空间</b>按项目、业务或主题组织文档。已有医疗资料保留在独立医疗空间。</p></div>
        <div><span>02</span><p><b>选择检索范围</b>从所选空间内的已索引文档召回相关内容。</p></div>
        <div><span>03</span><p><b>提问或执行任务</b>选中空间可引用文档，留空可直接对话。</p></div>
      </div>
    </section>

    <section class="entries" aria-label="工作台入口">
      <RouterLink v-for="entry in entries" :key="entry.path" :to="entry.path">
        <span>{{ entry.icon }}</span>
        <h2>{{ entry.title }}</h2>
        <p>{{ entry.description }}</p>
      </RouterLink>
    </section>
  </main>
</template>

<script setup>
const entries = [
  { path: '/knowledge-base', icon: '▤', title: '知识库空间', description: '创建空间、导入文档、管理索引和验证检索。' },
  { path: '/chat', icon: '◇', title: '知识助手', description: '直接对话，或在所选空间中问答并查看引用。' },
  { path: '/agent', icon: '⌘', title: '智能体', description: '运行多步任务，查看工具调用、工作流和审计。' }
]
</script>

<style scoped>
.home { min-height:100%; padding:24px clamp(20px,7vw,110px) 60px; background:#f3f7f6; color:#153534; }
.home-nav, .home-nav div { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:16px; }
.home-nav a { color:#285b56; text-decoration:none; }
.brand { font-weight:750; }
.brand span { margin-right:6px; color:#087f75; }
.hero { display:grid; grid-template-columns:1.35fr 1fr; align-items:center; gap:clamp(24px,6vw,90px); padding:clamp(38px,8vw,108px) 0 48px; }
.hero h1 { font-size:clamp(34px,5vw,70px); line-height:1.14; letter-spacing:-.045em; margin:12px 0 20px; }
.hero p { max-width:46ch; color:#4f6866; line-height:1.7; }
.hero .eyebrow { color:#087f75; font-size:11px; font-weight:800; letter-spacing:.17em; }
.primary { display:inline-flex; gap:16px; margin-top:24px; border-radius:10px; padding:12px 16px; background:#086c61; color:#fff; text-decoration:none; }
.scope-card { padding:22px; border:1px solid #d4e4df; border-radius:18px; background:#fff; }
.scope-card h2 { margin:0 0 12px; font-size:17px; }
.scope-card div { display:flex; align-items:flex-start; gap:12px; padding:12px 0; border-top:1px solid #e7efec; }
.scope-card div span { color:#087f75; font-weight:700; font-size:12px; padding-top:3px; }
.scope-card p { font-size:13px; }
.scope-card b { display:block; color:#153534; }
.entries { display:grid; grid-template-columns:repeat(3,1fr); gap:14px; }
.entries a { padding:20px; border:1px solid #d4e4df; border-radius:14px; color:inherit; text-decoration:none; background:#fff; }
.entries a:hover,.entries a:focus-visible { border-color:#087f75; }
.entries span { color:#087f75; }
.entries h2 { font-size:17px; margin:8px 0; }
.entries p { font-size:13px; line-height:1.6; color:#4f6866; }
@media (max-width:760px) {
  .hero,.entries { grid-template-columns:1fr; }
}
</style>
