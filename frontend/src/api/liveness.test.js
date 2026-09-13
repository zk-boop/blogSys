import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createAliveWatch } from './liveness.js'

/**
 * 手动的假计时器:记下每次起表用的(回调、毫秒数)与每次清表的 id,
 * 由测试自己决定「时间到了」。于是 30 秒这条策略不用真等 30 秒就能被验证。
 */
function fakeTimers() {
  const started = []
  const cleared = []
  const cancelled = new Set()
  let nextId = 1
  return {
    started,
    cleared,
    setTimer(fn, ms) {
      const id = nextId++
      started.push({ id, fn, ms })
      return id
    },
    clearTimer(id) {
      cleared.push(id)
      cancelled.add(id)
    },
    /**
     * 让某一次起的表到点。被 clearTimer 取消过的表不会触发 —— 真实的 clearTimeout
     * 就是这样;若这里照常触发,「停表」这件事在测试里根本验不出来。
     *
     * @returns {boolean} 回调是否真的跑了。
     */
    fire(id) {
      const entry = started.find((t) => t.id === id)
      if (!entry || cancelled.has(id)) return false
      entry.fn()
      return true
    },
  }
}

/** 组装一个守望者,并给出「它响了几次」的读数。 */
function watchOf(timers, options = {}) {
  let stalls = 0
  const watch = createAliveWatch({
    onStall: () => { stalls += 1 },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    ...options,
  })
  return { watch, stalls: () => stalls }
}

const lastId = (timers) => timers.started[timers.started.length - 1].id

describe('createAliveWatch', () => {
  it('beat 按 timeoutMs 起表', () => {
    const timers = fakeTimers()
    const { watch } = watchOf(timers)

    watch.beat()

    assert.equal(timers.started.length, 1)
    assert.equal(timers.started[0].ms, 30000)
  })

  it('阈值可以调小 —— 多久算沉默是策略,不是硬编码', () => {
    const timers = fakeTimers()
    const { watch } = watchOf(timers, { timeoutMs: 1234 })

    watch.beat()

    assert.equal(timers.started[0].ms, 1234)
  })

  it('表到点 = 沉默超过阈值 ⇒ 调 onStall', () => {
    const timers = fakeTimers()
    const { watch, stalls } = watchOf(timers)

    watch.beat()
    assert.equal(timers.fire(lastId(timers)), true)

    assert.equal(stalls(), 1)
  })

  it('每来一次心跳都重新起表 —— 沉默从最后一次「还活着」的证据算起', () => {
    const timers = fakeTimers()
    const { watch, stalls } = watchOf(timers)

    watch.beat()
    const first = lastId(timers)
    watch.beat()

    assert.deepEqual(timers.cleared, [first])
    assert.equal(timers.started.length, 2)
    assert.equal(timers.fire(first), false, '旧表已经被取消,它到点也不该再响')
    assert.equal(stalls(), 0)
  })

  it('stop 之后表不再触发', () => {
    const timers = fakeTimers()
    const { watch, stalls } = watchOf(timers)

    watch.beat()
    const id = lastId(timers)
    watch.stop()

    assert.deepEqual(timers.cleared, [id])
    assert.equal(timers.fire(id), false)
    assert.equal(stalls(), 0)
  })

  it('stop 之后迟到的帧不能把对话救活 —— 不会再起表', () => {
    const timers = fakeTimers()
    const { watch } = watchOf(timers)

    watch.beat()
    watch.stop()
    watch.beat()

    assert.equal(timers.started.length, 1, '已经收尾的对话不该因为一个迟到的心跳重新开始计沉默')
  })

  it('stop 可以重复调 —— 每个收尾路径都顺手停表,不必互相打点', () => {
    const timers = fakeTimers()
    const { watch, stalls } = watchOf(timers)

    watch.beat()
    watch.stop()
    watch.stop()

    assert.deepEqual(timers.cleared, [timers.started[0].id])
    assert.equal(timers.fire(lastId(timers)), false)
    assert.equal(stalls(), 0)
  })
})
