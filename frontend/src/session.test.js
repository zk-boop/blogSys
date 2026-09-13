import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createSession } from './session.js'

/** 测试用的 port:全是内存对象 —— 这正是「两个真 adapter 一个 seam」里那个测试 adapter。 */
function harness({ token = '', path = '/article/3' } = {}) {
  const calls = { navigated: [], notified: [], cleared: 0 }
  let current = token
  const session = createSession({
    readToken: () => current,
    clearAuth: () => {
      calls.cleared += 1
      current = ''
    },
    currentPath: () => path,
    navigate: (target) => calls.navigated.push(target),
    notify: (message) => calls.notified.push(message),
  })
  return { session, calls, setToken: (value) => { current = value } }
}

describe('createSession', () => {
  describe('token 注入:两个 adapter 共用同一份', () => {
    it('已登录时带 Bearer', () => {
      const { session } = harness({ token: 'abc123' })
      assert.deepEqual(session.authHeaders(), { Authorization: 'Bearer abc123' })
    })

    it('未登录时不带 Authorization(而不是带一个空的)', () => {
      const { session } = harness({ token: '' })
      assert.deepEqual(session.authHeaders(), {})
    })
  })

  describe('跳转目标:只有这一个主人', () => {
    it('带上回来的路 —— 此前只有 router 守卫带,view 与两个 adapter 都是裸跳 /login', () => {
      const { session } = harness({ path: '/article/3' })
      assert.equal(session.loginTarget(), `/login?redirect=${encodeURIComponent('/article/3')}`)
    })

    it('查询串也一起带上', () => {
      const { session } = harness({ path: '/?keyword=vue' })
      assert.equal(session.loginTarget(), `/login?redirect=${encodeURIComponent('/?keyword=vue')}`)
    })

    it('已经在登录页时不再套一层 redirect,否则会越滚越长', () => {
      const { session } = harness({ path: '/login' })
      assert.equal(session.loginTarget(), '/login')
    })

    it('可以显式指定来源(router 守卫用 to.fullPath,而不是当前地址)', () => {
      const { session } = harness({ path: '/whatever' })
      assert.equal(session.loginTarget('/write'), `/login?redirect=${encodeURIComponent('/write')}`)
    })
  })

  describe('401 反应:清会话 + 跳登录', () => {
    it('清掉会话、跳到登录页,并返回给用户看的那句话', () => {
      const { session, calls } = harness({ token: 'abc', path: '/me' })

      const message = session.unauthorized()

      assert.equal(message, '登录已过期,请重新登录')
      assert.equal(calls.cleared, 1)
      assert.deepEqual(calls.navigated, [`/login?redirect=${encodeURIComponent('/me')}`])
    })

    it('已经登出过就不再跳转 —— 并发请求会同时拿到 401,不能被推着跳好几次', () => {
      const { session, calls } = harness({ token: 'abc' })

      session.unauthorized()
      session.unauthorized()
      session.unauthorized()

      assert.equal(calls.cleared, 1, '只该清一次')
      assert.equal(calls.navigated.length, 1, '只该跳一次')
    })

    it('本来就没登录时什么都不做,但仍然给出一句话', () => {
      const { session, calls } = harness({ token: '' })

      assert.equal(session.unauthorized(), '登录已过期,请重新登录')
      assert.equal(calls.cleared, 0)
      assert.deepEqual(calls.navigated, [])
    })
  })

  describe('requireLogin:view 只问这一个问题', () => {
    it('已登录时返回 true,且不提示、不跳转', () => {
      const { session, calls } = harness({ token: 'abc' })

      assert.equal(session.requireLogin(), true)
      assert.deepEqual(calls.notified, [])
      assert.deepEqual(calls.navigated, [])
    })

    it('未登录时提示 + 跳转(带 redirect)+ 返回 false', () => {
      const { session, calls } = harness({ token: '', path: '/article/9' })

      assert.equal(session.requireLogin(), false)
      assert.deepEqual(calls.notified, ['请先登录'])
      assert.deepEqual(calls.navigated, [`/login?redirect=${encodeURIComponent('/article/9')}`])
    })
  })
})
