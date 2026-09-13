import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createSseReader } from './aiEvents.js'

/** 记录所有回调,便于断言「谁被调了、调了几次」。 */
function recorder() {
  const calls = []
  const reader = createSseReader({
    onTool: (payload) => calls.push(['tool', payload.name]),
    onMessage: (content) => calls.push(['message', content]),
    onDone: () => calls.push(['done']),
    onError: (message) => calls.push(['error', message]),
    onAlive: () => calls.push(['alive']),
  })
  return { reader, calls }
}

const frame = (event, data) => `event:${event}\ndata:${data}\n\n`

describe('createSseReader', () => {
  it('message 增量按顺序交出去', () => {
    const { reader, calls } = recorder()
    reader.push(frame('message', '{"content":"你"}') + frame('message', '{"content":"好"}'))
    reader.finish()

    assert.deepEqual(calls, [['message', '你'], ['message', '好']])
  })

  it('碎片怎么切都不影响 —— 一行可以被拆到两次 push 里', () => {
    const { reader, calls } = recorder()
    const text = frame('message', '{"content":"完整"}')
    reader.push(text.slice(0, 9))
    reader.push(text.slice(9, 20))
    reader.push(text.slice(20))
    reader.finish()

    assert.deepEqual(calls, [['message', '完整']])
  })

  it('tool 的前后两帧都交出去(第一次没有 result)', () => {
    const { reader, calls } = recorder()
    reader.push(frame('tool', '{"name":"getHotArticles","args":{}}'))
    reader.push(frame('tool', '{"name":"getHotArticles","args":{},"result":"{}"}'))
    reader.finish()

    assert.deepEqual(calls, [['tool', 'getHotArticles'], ['tool', 'getHotArticles']])
  })

  describe('done 是唯一的正常收尾凭证', () => {
    it('收到 done ⇒ onDone 恰好一次,finish 返回 done', () => {
      const { reader, calls } = recorder()
      reader.push(frame('done', '{}'))

      assert.equal(reader.finish(), 'done')
      assert.deepEqual(calls, [['done']])
    })

    it('没有 done 也没有 error(连接被截断)⇒ 不触发 onDone,返回 truncated', () => {
      const { reader, calls } = recorder()
      reader.push(frame('message', '{"content":"半截回答"}'))

      assert.equal(reader.finish(), 'truncated')
      assert.deepEqual(calls, [['message', '半截回答']],
        '截断时绝不能触发 onDone —— 那会让半截回答看起来像完整回答')
    })

    it('收到 error ⇒ 返回 error,且不触发 onDone', () => {
      const { reader, calls } = recorder()
      reader.push(frame('error', '{"message":"AI 服务未配置"}'))

      assert.equal(reader.finish(), 'error')
      assert.deepEqual(calls, [['error', 'AI 服务未配置']])
    })

    it('error 之后又来了 done,done 仍然算数', () => {
      const { reader, calls } = recorder()
      reader.push(frame('error', '{"message":"x"}') + frame('done', '{}'))

      assert.equal(reader.finish(), 'done')
      assert.deepEqual(calls, [['error', 'x'], ['done']])
    })
  })

  it('没有结尾空行的最后一帧也要处理 —— 流可能正好在那儿断', () => {
    const { reader, calls } = recorder()
    reader.push('event:message\ndata:{"content":"尾巴"}')

    assert.equal(reader.finish(), 'truncated')
    assert.deepEqual(calls, [['message', '尾巴']])
  })

  it('非 JSON 的 data 行被忽略,不会把整场对话弄崩', () => {
    const { reader, calls } = recorder()
    reader.push('event:message\ndata:not-json\n\n' + frame('done', '{}'))

    assert.equal(reader.finish(), 'done')
    assert.deepEqual(calls, [['done']])
  })

  it('注释行是心跳,只唤起 onAlive;其它与协议无关的行被忽略', () => {
    const { reader, calls } = recorder()
    reader.push(': keep-alive\nretry: 3000\nid: 7\n\n' + frame('done', '{}'))

    assert.equal(reader.finish(), 'done')
    assert.deepEqual(calls, [['alive'], ['done']])
  })

  describe('心跳是「还活着」的信号,不是数据', () => {
    it('心跳只唤起 onAlive —— 不产生 message/error,也不妨碍 done 收尾', () => {
      const { reader, calls } = recorder()
      reader.push(': ping\n\n' + frame('message', '{"content":"在"}') + frame('done', '{}'))

      assert.equal(reader.finish(), 'done')
      assert.deepEqual(calls, [['alive'], ['message', '在'], ['done']],
        '整份调用记录已经说明:alive 与 message 各一次,error 一次也没有')
    })

    it('只收到心跳 ⇒ finish 仍然是 truncated —— 心跳不能伪装成收尾', () => {
      const { reader, calls } = recorder()
      reader.push(': ping\n\n: ping\n\n')

      assert.equal(reader.finish(), 'truncated')
      assert.deepEqual(calls, [['alive'], ['alive']],
        '心跳若被当成收尾,一个断掉的对话就会看起来像一次完整回答')
    })
  })

  it('event 名认不出来时什么都不做(未知帧不静默变成错误)', () => {
    const { reader, calls } = recorder()
    reader.push(frame('usage', '{"total_tokens":10}') + frame('done', '{}'))

    assert.equal(reader.finish(), 'done')
    assert.deepEqual(calls, [['done']])
  })
})
