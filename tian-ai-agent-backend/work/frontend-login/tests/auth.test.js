import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer as createHttpServer } from 'node:http'
import { createServer } from 'vite'
import { loginDestination } from '../src/utils/auth.js'

test('login redirects stay inside the application', () => {
  assert.equal(loginDestination('/medical?tab=rag'), '/medical?tab=rag')
  for (const value of [undefined, ['//example.com'], '//example.com', 'https://example.com', '/\\example.com', '/login?redirect=/tasks']) {
    assert.equal(loginDestination(value), '/')
  }
})

test('explicit login and logout use the server session without automatic login', async t => {
  const server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true, hmr: { server: createHttpServer() } },
    plugins: [{
      name: 'auth-api-fixture', enforce: 'pre',
      resolveId(source, importer) {
        if (source === '../api' && importer?.endsWith('/stores/user.js')) return '\0fixture-auth'
      },
      load(id) {
        if (id === '\0fixture-auth') return 'export const getCurrentUser = () => globalThis.__authApi.me(); export const login = body => globalThis.__authApi.login(body); export const logout = () => globalThis.__authApi.logout()'
      }
    }]
  })
  try {
    const users = await server.ssrLoadModule('/src/stores/user.js')
    await t.test('anonymous requests never log in automatically', async () => {
      users.clearCurrentUser()
      let loginCalls = 0
      globalThis.__authApi = {
        me: async () => { throw { response: { status: 401 } } },
        login: async () => { loginCalls++ }
      }
      await assert.rejects(users.loadCurrentUser())
      assert.equal(users.currentUser.value, null)
      assert.equal(loginCalls, 0)
    })
    await t.test('successful login stores identity and logout clears it', async () => {
      const account = { userId: 'zhangsan', username: 'zhangsan' }
      let logoutCalls = 0
      globalThis.__authApi = {
        login: async body => {
          assert.deepEqual(body, { username: 'zhangsan', password: '123456' })
          return { data: account }
        },
        logout: async () => { logoutCalls++ },
        me: async () => { throw new Error('cached identity should be reused') }
      }
      await users.signIn({ username: 'zhangsan', password: '123456' })
      assert.equal((await users.loadCurrentUser()).userId, 'zhangsan')
      assert.equal(users.currentUser.value.password, undefined)
      await users.signOut()
      assert.equal(logoutCalls, 1)
      assert.equal(users.currentUser.value, null)
    })
    await t.test('wrong password does not create a client identity', async () => {
      globalThis.__authApi = { login: async () => { throw { response: { status: 401 } } } }
      await assert.rejects(users.signIn({ username: 'zhangsan', password: 'wrong' }))
      assert.equal(users.currentUser.value, null)
    })
    await t.test('an old current-user response cannot restore identity after logout', async () => {
      let resolveMe
      globalThis.__authApi = {
        me: () => new Promise(resolve => { resolveMe = resolve }),
        logout: async () => {}
      }
      const pending = users.loadCurrentUser(true)
      await users.signOut()
      resolveMe({ data: { userId: 'zhangsan' } })
      await assert.rejects(pending, /登录状态已改变/)
      assert.equal(users.currentUser.value, null)
    })
  } finally {
    delete globalThis.__authApi
    await server.close()
  }
})
