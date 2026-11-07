import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer as createHttpServer } from 'node:http'
import { createServer } from 'vite'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, nextTick } from 'vue'

test('logout 404 offers a retry that calls logout again and then opens login', async () => {
  const fixture = { logoutCalls: 0, destinations: [] }
  globalThis.__logoutUi = fixture
  const server = await createServer({
    configFile: false,
    ssr: { noExternal: ['vue-router'] },
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true, hmr: { server: createHttpServer() } },
    plugins: [{
      name: 'logout-ui-fixture', enforce: 'pre',
      resolveId(source, importer) {
        if (!importer?.endsWith('/src/App.vue')) return
        if (source === 'vue-router') return '\0logout-router'
        if (source === './stores/user') return '\0logout-user'
        if (source === './api') return '\0logout-api'
        if (source.startsWith('./components/')) return '\0logout-child'
      },
      load(id) {
        if (id.endsWith('/src/App.vue')) {
          const { descriptor } = parse(readFileSync(id, 'utf8'), { filename: id })
          return compileScript(descriptor, { id: 'logout-ui-test', inlineTemplate: true }).content
        }
        if (id === '\0logout-router') return 'export const useRoute=()=>({path:"/",name:"Home"}); export const useRouter=()=>({replace:async path=>globalThis.__logoutUi.destinations.push(path)})'
        if (id === '\0logout-user') return `import {ref} from 'vue';
          export const currentUser=ref({userId:'zhangsan',username:'zhangsan'});
          export const loadCurrentUser=async()=>currentUser.value;
          export const signOut=async()=>{
            if(++globalThis.__logoutUi.logoutCalls===1)throw {response:{status:404},config:{url:'/auth/logout'}};
            currentUser.value=null;
          };`
        if (id === '\0logout-api') return 'export const getSessions=async()=>{throw new Error("Retry must not load chat history")}; export const createSession=()=>{}; export const deleteSession=()=>{};'
        if (id === '\0logout-child') return 'export default {render:()=>null}'
      }
    }]
  })
  let app
  try {
    const { default: App } = await server.ssrLoadModule('/src/App.vue')
    const node = (tag, text = '') => ({ tag, text, children: [], props: {} })
    const renderer = createRenderer({
      createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
      setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text },
      patchProp: (el, key, oldValue, value) => { el.props[key] = value },
      insert: (el, parent) => { parent.children.push(el); el.parent = parent },
      remove: el => { if (el.parent) el.parent.children = el.parent.children.filter(item => item !== el) },
      parentNode: el => el.parent, nextSibling: () => null
    })
    const root = node('root')
    app = renderer.createApp(App)
    app.component('router-view', { render: () => null })
    app.mount(root)
    const nodes = el => [el, ...el.children.flatMap(nodes)]
    const button = text => nodes(root).find(el => el.tag === 'button' && el.text === text)
    await button('退出登录').props.onClick()
    await nextTick()
    assert.equal(fixture.logoutCalls, 1)
    assert.equal(fixture.destinations.length, 0)
    assert.ok(nodes(root).some(el => el.text.includes('退出登录接口不存在')))
    assert.ok(button('重试退出'))
    await button('重试退出').props.onClick()
    await nextTick()
    assert.equal(fixture.logoutCalls, 2)
    assert.deepEqual(fixture.destinations, ['/login'])
  } finally {
    app?.unmount()
    delete globalThis.__logoutUi
    await server.close()
  }
})
