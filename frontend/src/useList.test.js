import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { messageOf, useList } from './useList.js'

/** 造一个受控的取数函数:记下每次收到的参数,并按脚本返回或抛错。 */
function fetcher(script) {
  const calls = []
  let index = 0
  const fn = async (params) => {
    calls.push(params)
    const step = script[Math.min(index, script.length - 1)]
    index += 1
    if (step instanceof Error) throw step
    return step
  }
  return { fn, calls }
}

const pageOf = (ids, total = ids.length) => ({ records: ids.map((id) => ({ id })), total })

describe('useList', () => {
  describe('成功', () => {
    it('记下 records 与 total,phase 为 ready', async () => {
      const { fn } = fetcher([pageOf([1, 2])])
      const list = useList(fn)

      await list.load()

      assert.deepEqual(list.records.value.map((r) => r.id), [1, 2])
      assert.equal(list.total.value, 2)
      assert.equal(list.phase.value, 'ready')
      assert.equal(list.failure.value, null)
    })

    it('空结果是 empty,不是 failed —— 这两件事在界面上必须不同', async () => {
      const { fn } = fetcher([pageOf([])])
      const list = useList(fn)

      await list.load()

      assert.equal(list.phase.value, 'empty')
      assert.equal(list.failure.value, null)
    })

    it('取数期间是 loading', async () => {
      let release
      const list = useList(() => new Promise((resolve) => { release = () => resolve(pageOf([1])) }))

      const pending = list.load()
      assert.equal(list.phase.value, 'loading')

      release()
      await pending
      assert.equal(list.phase.value, 'ready')
    })

    it('把 page / size / keyword 交给取数函数', async () => {
      const { fn, calls } = fetcher([pageOf([1])])
      const list = useList(fn, { size: 20, initialKeyword: 'vue' })

      await list.load()

      assert.deepEqual(calls[0], { page: 1, size: 20, keyword: 'vue' })
    })
  })

  describe('失败:这是这个模块存在的理由', () => {
    it('phase 变成 failed,并留下给用户看的原因', async () => {
      const { fn } = fetcher([new Error('网络错误')])
      const list = useList(fn)

      await list.load()

      assert.equal(list.phase.value, 'failed')
      assert.equal(list.failure.value, '网络错误')
      assert.deepEqual(list.records.value, [], '不能留着上一次的列表假装是当前结果')
      assert.equal(list.total.value, 0)
    })

    it('不把异常抛出去 —— 视图不需要每处都写 catch', async () => {
      const { fn } = fetcher([new Error('炸了')])
      const list = useList(fn)

      await assert.doesNotReject(() => list.load())
    })

    it('重试成功后失败态消失', async () => {
      const { fn } = fetcher([new Error('第一次失败'), pageOf([7])])
      const list = useList(fn)

      await list.load()
      assert.equal(list.phase.value, 'failed')

      await list.load()
      assert.equal(list.failure.value, null)
      assert.equal(list.phase.value, 'ready')
      assert.deepEqual(list.records.value.map((r) => r.id), [7])
    })
  })

  describe('翻页与筛选', () => {
    it('search 回到第 1 页再取 —— 这段此前在 3 个 view 里各写了一遍', async () => {
      const { fn, calls } = fetcher([pageOf([1]), pageOf([1]), pageOf([2])])
      const list = useList(fn)

      await list.goTo(3)
      assert.equal(list.page.value, 3)

      await list.search()
      assert.equal(list.page.value, 1)
      assert.deepEqual(calls.map((c) => c.page), [3, 1])
    })

    it('goTo 设页码并取数', async () => {
      const { fn, calls } = fetcher([pageOf([1]), pageOf([2])])
      const list = useList(fn)

      await list.load()
      await list.goTo(2)

      assert.equal(list.page.value, 2)
      assert.deepEqual(calls.map((c) => c.page), [1, 2])
    })

    it('goTo 收到空的/非数字的页码时退回第 1 页', async () => {
      const { fn } = fetcher([pageOf([1])])
      const list = useList(fn)

      await list.goTo(undefined)

      assert.equal(list.page.value, 1)
    })
  })

  describe('messageOf:两种错误形状的统一读法', () => {
    it('业务错误:http.js 返回的 new Error(message)', () => {
      assert.equal(messageOf(new Error('文章不存在')), '文章不存在')
    })

    it('HTTP 错误:原始 axios error,消息在 response.data.message 里', () => {
      const axiosError = new Error('Request failed with status code 500')
      axiosError.response = { data: { message: '服务器开小差了' } }
      assert.equal(messageOf(axiosError), '服务器开小差了')
    })

    it('什么都没有时给一句人话', () => {
      assert.equal(messageOf(undefined), '加载失败,请稍后重试')
      assert.equal(messageOf({}), '加载失败,请稍后重试')
    })
  })
})
