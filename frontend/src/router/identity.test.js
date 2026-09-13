import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { outletKey } from './identity.js'

/**
 * 这些用例证明的是**规则**,不是 vue-router 的行为:
 * 记录形状是手写的,「标识变了 Vue 就会重挂载」是框架保证。
 * 端到端的那一半(URL 变了正文是否真的换)靠一次性真实浏览器核对,见本次提交说明。
 *
 * 之所以仍然值得写:出错的从来不是「Vue 会不会重挂载」,而是**标识算得对不对** ——
 * 尤其是「哪些变化该重挂载、哪些不该」这条边界。
 */

/** 造一个 route 形状的对象,只带 outletKey 用到的字段。 */
function route(path, { records = [], params = {}, query = {}, fullPath } = {}) {
  return {
    matched: records.map((p) => ({ path: p })),
    params,
    query,
    fullPath: fullPath ?? path,
  }
}

const ARTICLE = ['/article/:id']

describe('outletKey', () => {
  describe('D7:同一个记录、params 变了必须换实例', () => {
    it('文章 id 变化时标识不同', () => {
      const a = outletKey(route('/article/1', { records: ARTICLE, params: { id: '1' } }))
      const b = outletKey(route('/article/2', { records: ARTICLE, params: { id: '2' } }))
      assert.notEqual(a, b)
    })

    it('用户 id 变化时标识不同', () => {
      const a = outletKey(route('/user/4', { records: ['/user/:id'], params: { id: '4' } }))
      const b = outletKey(route('/user/5', { records: ['/user/:id'], params: { id: '5' } }))
      assert.notEqual(a, b)
    })

    it('写文章与编辑文章是同一个组件的两个记录,标识必须不同', () => {
      const a = outletKey(route('/write', { records: ['/write'] }))
      const b = outletKey(route('/write/7', { records: ['/write/:id'], params: { id: '7' } }))
      assert.notEqual(a, b)
    })

    it('同一条 URL 两次得到同一个标识 —— 不能无端重挂载', () => {
      const make = () => outletKey(route('/article/1', { records: ARTICLE, params: { id: '1' } }))
      assert.equal(make(), make())
    })
  })

  describe('query 参与标识,于是 Home 的补偿 watcher 可以删掉', () => {
    it('搜索词变化时标识不同', () => {
      const a = outletKey(route('/', { records: ['/'], fullPath: '/' }))
      const b = outletKey(route('/', { records: ['/'], query: { keyword: 'vue' }, fullPath: '/?keyword=vue' }))
      assert.notEqual(a, b)
    })

    it('query 的顺序不影响标识', () => {
      const a = outletKey(route('/', { records: ['/'], query: { a: '1', b: '2' } }))
      const b = outletKey(route('/', { records: ['/'], query: { b: '2', a: '1' } }))
      assert.equal(a, b)
    })
  })

  describe('边界:只有子记录变时,父布局不重挂载', () => {
    const ADMIN = ['/admin', '/admin/users']
    const ADMIN2 = ['/admin', '/admin/articles']

    it('顶层 outlet 看到的是同一个标识', () => {
      const users = outletKey(route('/admin/users', { records: ADMIN }), 0)
      const articles = outletKey(route('/admin/articles', { records: ADMIN2 }), 0)
      assert.equal(users, articles, '后台切标签页不该把整个布局重挂载一次')
    })

    it('子 outlet 看到的标识不同 —— 叶子仍然要换', () => {
      const users = outletKey(route('/admin/users', { records: ADMIN }), 1)
      const articles = outletKey(route('/admin/articles', { records: ADMIN2 }), 1)
      assert.notEqual(users, articles)
    })

    it('子记录的 params 不会把父布局一起换掉', () => {
      const a = outletKey(route('/a/1', { records: ['/a', '/a/:id'], params: { id: '1' } }), 0)
      const b = outletKey(route('/a/2', { records: ['/a', '/a/:id'], params: { id: '2' } }), 0)
      assert.equal(a, b)
    })
  })

  it('没有匹配到记录时退回整条 URL,宁可多挂载也不留旧内容', () => {
    assert.equal(outletKey({ matched: [], params: {}, query: {}, fullPath: '/weird?x=1' }), '/weird?x=1')
  })
})
