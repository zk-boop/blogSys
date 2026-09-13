import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { displayAvatar, displayName } from './person.js'

describe('displayName', () => {
  it('昵称优先', () => {
    assert.equal(displayName({ nickname: '管理员', username: 'admin' }), '管理员')
  })

  it('没有昵称就用用户名 —— 这条规则此前在 9 个 module 里各写了一遍', () => {
    assert.equal(displayName({ nickname: '', username: 'alice' }), 'alice')
    assert.equal(displayName({ username: 'alice' }), 'alice')
  })

  it('两者都没有时返回空串,不编一个名字', () => {
    assert.equal(displayName({}), '')
    assert.equal(displayName(null), '')
    assert.equal(displayName(undefined), '')
  })
})

describe('displayAvatar', () => {
  it('有头像就用它', () => {
    assert.equal(displayAvatar({ avatar: '/uploads/me.jpg', nickname: '甲' }), '/uploads/me.jpg')
  })

  it('dicebear 占位地址一律丢弃,换成确定性的首字母图', () => {
    const src = displayAvatar({ avatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=x', nickname: '甲' })
    assert.ok(src.startsWith('data:image/svg+xml'), `实际: ${src.slice(0, 40)}`)
  })

  it('没有头像时用名字生成首字母图,且兜底的名字与 displayName 是同一个', () => {
    // 两者若各算各的,同一个人的名字与头像就可能来自不同的兜底
    const fallbackOnly = { nickname: '', username: 'alice' }
    const svg = decodeURIComponent(displayAvatar(fallbackOnly))

    assert.ok(svg.includes('>A<'), `首字母应当取自 username。实际: ${svg.slice(0, 120)}`)
  })

  it('一个人都没有时也不抛异常', () => {
    assert.ok(displayAvatar(null).startsWith('data:image/svg+xml'))
  })
})
