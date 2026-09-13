import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  createChatHistory,
  HISTORY_LIMIT,
  OUTGOING_LIMIT,
  outgoing,
  revive,
} from './chatHistory.js'

/**
 * 假 storage:内部就是一个 Map,只实现真 storage 里真正被用到的那三个方法。
 *
 * <p>`throwOn` 用来复现真 storage 会抛异常的那两种场合(配额满、隐私模式)——
 * 「存不下也不该把聊天弄崩」这条策略因此能在毫秒内被验证,不用去填满 disk quota。
 */
function fakeStorage({ throwOn = [] } = {}) {
  const map = new Map()
  const blow = (op) => {
    if (throwOn.includes(op)) throw new Error(`${op} 不可用`)
  }
  return {
    getItem(key) {
      blow('getItem')
      return map.has(key) ? map.get(key) : null
    },
    setItem(key, value) {
      blow('setItem')
      map.set(key, value)
    },
    removeItem(key) {
      blow('removeItem')
      map.delete(key)
    },
  }
}

/** 生产接线:默认 key、默认上限,只是 storage 换成了假的。 */
function harness(options) {
  const storage = fakeStorage(options)
  return { storage, history: createChatHistory({ storage }) }
}

const user = (content) => ({ role: 'user', content })
const assistant = (content) => ({ role: 'assistant', content, tools: [], error: null, pending: false })

describe('createChatHistory', () => {
  describe('存下来、读回去', () => {
    it('save 之后 load 拿回同一批消息', () => {
      const { history } = harness()
      const messages = [user('站内有哪些热门文章?'), assistant('这几篇最近很热…')]

      history.save('u1', messages)

      assert.deepEqual(history.load('u1'), messages)
    })

    it('载荷带版本号与归属 —— 后面所有校验都建立在这两个字段上', () => {
      const { storage, history } = harness()
      const messages = [user('你好')]

      history.save('u1', messages)

      assert.deepEqual(JSON.parse(storage.getItem('ai-chat')), {
        v: 1,
        userId: 'u1',
        messages,
      })
    })

    it('key 可以换 —— 两套记录互不覆盖', () => {
      const storage = fakeStorage()
      const a = createChatHistory({ storage, key: 'a' })
      const b = createChatHistory({ storage, key: 'b' })

      a.save('u1', [user('给 a 的')])
      b.save('u1', [user('给 b 的')])

      assert.deepEqual(a.load('u1'), [user('给 a 的')])
      assert.deepEqual(b.load('u1'), [user('给 b 的')])
    })
  })

  describe('防串号:同一个浏览器里换个人登录', () => {
    it('换个 userId 去 load,什么都拿不到', () => {
      const { history } = harness()
      history.save('u1', [user('上一个人的问题')])

      assert.deepEqual(history.load('u2'), [],
        '宁可不显示,也不要显示错的人的对话')
    })

    it('未登录(null)读不到任何人的记录,登录的人也读不到 null 那份', () => {
      const { history } = harness()
      history.save('u1', [user('登录后问的')])
      assert.deepEqual(history.load(null), [])

      history.save(null, [user('没登录时问的')])
      assert.deepEqual(history.load('u1'), [])
    })
  })

  describe('存储里是坏数据时一律当没存过', () => {
    it('坏 JSON 返回 [],不抛', () => {
      const { storage, history } = harness()
      storage.setItem('ai-chat', '{这不是 JSON')

      assert.deepEqual(history.load('u1'), [])
    })

    it('v 对不上返回 [] —— 认不出的版本不做猜测式兼容', () => {
      const { storage, history } = harness()
      storage.setItem('ai-chat', JSON.stringify({ v: 2, userId: 'u1', messages: [user('x')] }))

      assert.deepEqual(history.load('u1'), [])
    })

    it('messages 不是数组返回 []', () => {
      const { storage, history } = harness()
      storage.setItem('ai-chat', JSON.stringify({ v: 1, userId: 'u1', messages: '不是数组' }))

      assert.deepEqual(history.load('u1'), [])
    })

    it('载荷是 null / 数组这样的合法 JSON 也返回 []', () => {
      const { storage, history } = harness()
      for (const raw of ['null', '[]', '"字符串"', '7']) {
        storage.setItem('ai-chat', raw)
        assert.deepEqual(history.load('u1'), [], `raw=${raw}`)
      }
    })

    it('从来没有记录时返回 [],而不是抛异常', () => {
      const { history } = harness()

      assert.deepEqual(history.load('u1'), [])
    })
  })

  describe('上限', () => {
    it(`存了 100 条、上限 ${HISTORY_LIMIT} → 读回来的是最后 ${HISTORY_LIMIT} 条(不是前 ${HISTORY_LIMIT} 条)`, () => {
      const { history } = harness()
      const messages = Array.from({ length: 100 }, (_, i) => user(`第 ${i} 条`))

      history.save('u1', messages)
      const loaded = history.load('u1')

      assert.equal(loaded.length, HISTORY_LIMIT)
      assert.deepEqual(loaded[0], user('第 40 条'), '留下的必须是最近的那批 —— 和模型对话靠的是近因')
      assert.deepEqual(loaded[loaded.length - 1], user('第 99 条'))
    })

    it('save 本身就只写最后 limit 条 —— 存储不该随着聊天无限长', () => {
      const { storage, history } = harness()
      history.save('u1', Array.from({ length: 200 }, (_, i) => user(`第 ${i} 条`)))

      assert.equal(JSON.parse(storage.getItem('ai-chat')).messages.length, HISTORY_LIMIT)
    })

    it('上限可以调小,裁的仍然是尾部', () => {
      const storage = fakeStorage()
      const history = createChatHistory({ storage, limit: 2 })

      history.save('u1', [user('1'), user('2'), user('3')])

      assert.deepEqual(history.load('u1'), [user('2'), user('3')])
    })
  })

  describe('尽力而为:storage 抛异常也不往外抛', () => {
    it('save 抛异常(配额满)→ 不抛,聊天照常', () => {
      const { history } = harness({ throwOn: ['setItem'] })

      assert.doesNotThrow(() => history.save('u1', [user('存不下的这条')]))
      assert.deepEqual(history.load('u1'), [], '没存下就是没存下,但绝不能因此弄崩聊天')
    })

    it('load 抛异常(读被拒)→ 返回 []', () => {
      const { history } = harness({ throwOn: ['getItem'] })

      assert.deepEqual(history.load('u1'), [])
    })

    it('clear 抛异常 → 不抛', () => {
      const { history } = harness({ throwOn: ['removeItem'] })

      assert.doesNotThrow(() => history.clear())
    })
  })

  describe('clear', () => {
    it('clear 之后 load 回到空', () => {
      const { history } = harness()
      history.save('u1', [user('清空前的')])

      history.clear()

      assert.deepEqual(history.load('u1'), [])
    })

    it('clear 只清掉自己的 key,不碰 storage 里别的东西 —— 比如 token', () => {
      const storage = fakeStorage()
      storage.setItem('token', 'jwt-abc')
      const history = createChatHistory({ storage })

      history.save('u1', [user('x')])
      history.clear()

      assert.equal(storage.getItem('token'), 'jwt-abc')
    })
  })

  describe('revive:没写完的那条要被标出来', () => {
    it('pending: true 的消息变成 interrupted: true 且 pending: false', () => {
      const messages = [{ role: 'assistant', content: '半截回答', pending: true }]

      assert.deepEqual(revive(messages), [
        { role: 'assistant', content: '半截回答', pending: false, interrupted: true },
      ])
    })

    it('没带 pending(已经收尾)的消息原样透传', () => {
      const done = assistant('完整回答')
      const question = user('问题')

      const revived = revive([question, done])

      assert.deepEqual(revived, [question, done])
      assert.equal(revived[0], question, '不需要改写的对象不复制 —— 交进来的本来就是新鲜对象')
      assert.equal(revived[1], done)
    })

    it('不原地修改入参:返回的是新数组、新对象', () => {
      const original = { role: 'assistant', content: '半截回答', pending: true }
      const messages = [original]

      const revived = revive(messages)

      assert.notEqual(revived, messages)
      assert.notEqual(revived[0], original)
      assert.equal(original.pending, true, '入参保持原样,调用方不该被偷偷改掉')
      assert.deepEqual(messages, [{ role: 'assistant', content: '半截回答', pending: true }])
    })

    it('只改 pending 恰好是 true 的那条,别的真值不算数', () => {
      const messages = [{ role: 'assistant', content: 'a', pending: 1 }, { role: 'assistant', content: 'b' }]

      assert.deepEqual(revive(messages), messages)
    })

    it('非数组入参返回 []', () => {
      assert.deepEqual(revive(null), [])
      assert.deepEqual(revive(undefined), [])
      assert.deepEqual(revive('不是数组'), [])
      assert.deepEqual(revive({ role: 'user', content: 'x' }), [])
    })

    it('不是对象的元素被丢掉 —— 否则模板会在 message.role 上把整页弄炸', () => {
      assert.deepEqual(revive([null, user('留下'), 7, '字符串']), [user('留下')])
    })
  })

  describe('outgoing:只发该发的', () => {
    it('丢掉没有内容的 assistant 消息(刚建好还没吐字的那条)', () => {
      const messages = [user('问题'), { role: 'assistant', content: '' }]

      assert.deepEqual(outgoing(messages), [user('问题')])
    })

    it('只留 role 与 content —— tools/error/pending/interrupted 一个都不往线上带', () => {
      const messages = [
        user('问题'),
        {
          role: 'assistant',
          content: '回答',
          tools: [{ name: 'getHotArticles' }],
          error: null,
          pending: false,
          interrupted: false,
        },
      ]

      assert.deepEqual(outgoing(messages), [user('问题'), { role: 'assistant', content: '回答' }])
    })

    it(`超过 limit 时留最后 N 条(默认 ${OUTGOING_LIMIT} 条)`, () => {
      const messages = Array.from({ length: 40 }, (_, i) => user(`第 ${i} 条`))

      const payload = outgoing(messages)

      assert.equal(payload.length, OUTGOING_LIMIT,
        '后端 ChatRequest.messages 有 @Size(max = 50),整份历史直接发必然 400「历史消息过多」')
      assert.deepEqual(payload[0], user('第 20 条'))
      assert.deepEqual(payload[payload.length - 1], user('第 39 条'))
    })

    it('limit 可以显式指定', () => {
      assert.deepEqual(outgoing([user('1'), user('2'), user('3')], 2), [user('2'), user('3')])
    })

    it('非数组入参返回 []', () => {
      assert.deepEqual(outgoing(null), [])
      assert.deepEqual(outgoing(undefined), [])
    })
  })
})
