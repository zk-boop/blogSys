import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { readingStats } from './textStats.js'

describe('readingStats', () => {
  it('非字符串一律全 0 —— 详情页据此不显示这一块', () => {
    for (const value of [null, undefined, 123, true, {}, [], () => {}]) {
      assert.deepEqual(readingStats(value), { chars: 0, minutes: 0 }, `实际: ${JSON.stringify(value)}`)
    }
  })

  it('空串全 0', () => {
    assert.deepEqual(readingStats(''), { chars: 0, minutes: 0 })
  })

  it('纯空白也全 0 —— 没有字就不该给「至少 1 分钟」', () => {
    // 空格、换行、Tab、以及全角空格(U+3000):中文输入法下前者很容易混进来
    assert.deepEqual(readingStats('  \n\t \u3000 '), { chars: 0, minutes: 0 })
  })

  it('中文:chars 是去空白后的字符数,标点也算', () => {
    // 「今天,天气不错。」= 6 个汉字 + 1 个 ASCII 逗号 + 1 个全角句号
    assert.equal(readingStats('今天,天气不错。').chars, 8)
  })

  it('空白不计入 chars', () => {
    assert.equal(readingStats('a b\nc\td').chars, 4)
  })

  it('Markdown 标记照数不误 —— 不去标记是因为那会带来第二份 Markdown 解析', () => {
    // 为什么不去标记:去标记得先把 Markdown 解析一遍(引依赖、还得决定代码块/
    // 表格算不算),而这一步换来的仍只是估算。多出来的 1 个字符正是 `#`。
    const marked = '# 标题\n\n正文一段。'
    const plain = '标题正文一段。'

    assert.equal(readingStats(marked).chars, 8)
    assert.equal(readingStats(marked).chars, readingStats(plain).chars + 1)
  })

  it('minutes 按 400 字/分钟折算,且短文本至少 1 分钟', () => {
    assert.equal(readingStats('字'.repeat(400)).minutes, 1)
    assert.equal(readingStats('字'.repeat(800)).minutes, 2)
    assert.equal(readingStats('短').minutes, 1)
  })
})
