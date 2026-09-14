/**
 * 字数与阅读时长 —— 只回答一个问题:这篇现在值不值得读。
 *
 * <p>这是**近似估算**,不是统计。`chars` 与 `minutes` 都允许有偏差,谁都不该拿它
 * 做「工作量」之类的判断。
 *
 * <p>为什么不精确解析 Markdown:算得准需要一份 Markdown 解析器,而算出来的东西
 * 仍然只是估算 —— 代码块、表格、图片替代文字算不算「读者要读的字」本就没有唯一答案,
 * 换一份解析实现就换一个数字。为了一个屏角上的小字,把渲染管线拖进来不划算。
 * 这里宁可用一条一眼能看懂、谁都能复算的规则:去掉所有空白字符后数长度
 * (标点与 Markdown 标记都算),再按 400 字/分钟折算。口径写在注释里,比精确更值钱。
 */

/** 中文阅读速度的估计值(字/分钟)。改它等于改全站的「约 N 分钟」。 */
const CHARS_PER_MINUTE = 400

/**
 * @param {string} markdown 文章正文(Markdown 源文)。
 * @returns {{chars: number, minutes: number}} 非字符串、空串、纯空白一律返回
 *   `{ chars: 0, minutes: 0 }` —— 界面据此整块不显示,而不是显示「0 字 · 约 0 分钟」。
 */
export function readingStats(markdown) {
  if (typeof markdown !== 'string') {
    return { chars: 0, minutes: 0 }
  }
  // \s 覆盖空格、换行、Tab 与全角空格(U+3000 也在 \s 里)。
  const chars = markdown.replace(/\s/g, '').length
  if (chars === 0) {
    // 空串与「纯空白」在这里合流。注意不能落到下面那行:`max(1, …)` 会把空文章
    // 说成「约 1 分钟」。
    return { chars: 0, minutes: 0 }
  }
  // 至少 1 分钟:再短的文章也占掉一次打开的动作,显示「0 分钟」没有意义。
  return { chars, minutes: Math.max(1, Math.round(chars / CHARS_PER_MINUTE)) }
}
