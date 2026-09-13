/**
 * SSE 事件解析 —— 浏览器这一侧的线格式归属。
 *
 * <p>四种事件名与载荷形状由后端 `com.blogsys.ai.SseProtocol` 独家拥有,契约写在
 * `docs/api.md` 那一张表里;这里按同样的名字读。此前这份知识长在 `api/ai.js` 的
 * 流程里,和 fetch、reader、解码搅在一起,于是它既不可测也无法被单独审阅。
 *
 * <p>抽成纯函数之后:喂文本进去、看事件出来,`node --test` 直接覆盖(见
 * `aiEvents.test.js`)—— 包括最要紧的那条:**没有收到 `done` 就是被截断**。
 *
 * @param {{onTool?, onMessage?, onDone?, onError?, onAlive?}} handlers `onAlive` 收的是
 *   注释帧(心跳),不是数据:服务端每 10 秒发一个,用来让「连接还活着」变成一个可观察
 *   的事实 —— 在心跳出现之前,「AI 正在想」与「连接已经死了」在浏览器上不可区分。
 */
export function createSseReader(handlers = {}) {
  let buffer = ''
  let event = ''
  let done = false
  let failed = false

  function consume(line) {
    // 以 `:` 开头的行是 SSE 注释,服务端拿它当心跳。它只说明「连接还在」,
    // 所以既不产生任何数据事件,也不算收尾凭证(finish 仍然只看 done/error)——
    // 否则一次心跳就能把半截回答伪装成完整回答。
    if (line.startsWith(':')) {
      handlers.onAlive?.()
      return
    }
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      return
    }
    if (!line.startsWith('data:')) {
      return
    }
    const data = line.slice(5).trim()
    if (!data) {
      return
    }
    let payload
    try {
      payload = JSON.parse(data)
    } catch {
      return
    }
    if (event === 'message' && payload.content) {
      handlers.onMessage?.(payload.content)
    } else if (event === 'tool' && payload.name) {
      handlers.onTool?.(payload)
    } else if (event === 'error' && payload.message) {
      failed = true
      handlers.onError?.(payload.message)
    } else if (event === 'done') {
      done = true
    }
  }

  return {
    /** 喂一段解码后的文本。可以喂任意小的碎片 —— 行会被攒起来。 */
    push(text) {
      buffer += text
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        consume(line)
      }
    },

    /**
     * 流结束。把残留的最后一帧处理掉,并回答**这场对话是怎么结束的**。
     *
     * @returns {'done'|'error'|'truncated'} `truncated` 表示两者都没收到 —— 连接被截断。
     *   只有 `done` 才会触发 `onDone`:此前它在流末尾无条件触发,于是一个被截断的回答
     *   与一个完整回答在界面上长得一模一样。
     */
    finish() {
      if (buffer) {
        consume(buffer)
        buffer = ''
      }
      if (done) {
        handlers.onDone?.()
        return 'done'
      }
      return failed ? 'error' : 'truncated'
    },
  }
}
