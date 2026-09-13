import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { createMemoryHistory, createRouter } from 'vue-router'

import { createRouteErrorChannel, shouldReportRouteError } from './loadError.js'

/**
 * 导航失败对象是**真的**,不是手搓的假货:用 memory history 推两次同一个地址,
 * vue-router 就会给出一个真实的 `NavigationFailure`。
 * 这是必要的 —— 这条规则的价值全在「能不能认出真的导航失败」上。
 */
async function realNavigationFailure() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/a', component: { template: '<div/>' } }],
  })
  await router.push('/a')
  const failure = await router.push('/a')
  assert.ok(failure, '前提:重复导航应当返回一个失败对象')
  return failure
}

describe('shouldReportRouteError', () => {
  it('真实的导航失败不报 —— 它是用户自己操作的结果,报错只是噪音', async () => {
    assert.equal(shouldReportRouteError(await realNavigationFailure()), false)
  })

  it('懒加载 chunk 拉不下来时要报', () => {
    const error = new TypeError('Failed to fetch dynamically imported module: /src/views/Home.vue')
    assert.equal(shouldReportRouteError(error), true)
  })

  it('不再是「带 type 就吞掉」—— 一个带 type 的普通错误照样要报', () => {
    // 旧启发式是 `error.type !== undefined`,它会把这个错误悄悄吞掉
    const error = new Error('boom')
    error.type = 'some-domain-error'
    assert.equal(shouldReportRouteError(error), true)
  })

  it('null / undefined 也不当成要报的失败', () => {
    // 它们不是导航失败,但也没有可报的东西;交给调用方(null 进不了 onError)
    assert.equal(shouldReportRouteError(undefined), true)
  })
})

describe('createRouteErrorChannel', () => {
  it('两个订阅者都在 —— 第二个不再静默顶掉第一个', () => {
    const channel = createRouteErrorChannel()
    const seen = []
    channel.subscribe(() => seen.push('first'))
    channel.subscribe(() => seen.push('second'))

    assert.equal(channel.size, 2)
    assert.equal(channel.notify(new Error('chunk load failed')), true)
    assert.deepEqual(seen, ['first', 'second'], '两个都该被通知')
  })

  it('退订之后不再收到', () => {
    const channel = createRouteErrorChannel()
    const seen = []
    const unsubscribe = channel.subscribe(() => seen.push('x'))

    unsubscribe()
    channel.notify(new Error('x'))

    assert.equal(channel.size, 0)
    assert.deepEqual(seen, [])
  })

  it('导航失败不进这条通道,也不惊动任何订阅者', async () => {
    const channel = createRouteErrorChannel()
    const seen = []
    channel.subscribe(() => seen.push('x'))

    assert.equal(channel.notify(await realNavigationFailure()), false)
    assert.deepEqual(seen, [])
  })
})
