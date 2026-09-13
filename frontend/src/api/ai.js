import { session } from '../session-instance'
import { createSseReader } from './aiEvents'

const API_BASE = '/api'

/**
 * AI 对话(SSE 流式,需 fetch 直读流,无法走 axios)。
 *
 * <p>事件名与载荷形状的契约见 `docs/api.md`(服务端那边的唯一归属是 `SseProtocol`);
 * 线格式的解析归 `./aiEvents`,这里只剩「发请求、读字节、把文本喂进去」。
 * 心跳(注释帧)只是原样转给 `onAlive`,怎么解读归调用方 —— 这里不做判断。
 *
 * <p>返回 AbortController,可调用 abort() 停止。
 */
export function aiChatStream(messages, { onTool, onMessage, onDone, onError, onAlive }) {
  const controller = new AbortController()

  ;(async () => {
    try {
      const res = await fetch(`${API_BASE}/ai/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...session.authHeaders() },
        body: JSON.stringify({ messages }),
        signal: controller.signal,
      })
      if (res.status === 401) {
        // 401 反应与 axios 那侧同一个归属;这里只是换了个把它说给用户听的通道
        onError?.(session.unauthorized())
        return
      }
      if (!res.ok) {
        onError?.('AI 服务错误,请稍后重试')
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      const sse = createSseReader({ onTool, onMessage, onDone, onError, onAlive })
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        sse.push(decoder.decode(value, { stream: true }))
      }
      // 只有收到 `done` 才算回答完整。两者都没有读到流末尾 = 连接被截断,
      // 必须如实报错,而不是把半截回答当成完整回答。
      if (sse.finish() === 'truncated') {
        onError?.('连接中断,请重试')
      }
    } catch (err) {
      if (err.name === 'AbortError') return
      onError?.(err.message || '网络错误')
    }
  })()

  return controller
}
