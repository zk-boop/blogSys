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
 * @param {{onTool?, onMessage?, onDone?, onError?}} handlers
 */
export function createSseReader(handlers = {}) {
  let buffer = ''
  let event = ''
  let done = false
  let failed = false

  function consume(line) {
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
