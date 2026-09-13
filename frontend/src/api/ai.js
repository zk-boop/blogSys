import { session } from '../session-instance'

const API_BASE = '/api'

/**
 * AI 对话(SSE 流式,需 fetch 直读流,无法走 axios)。
 * 事件:tool(工具调用)、message(content 增量)、done、error。
 * 返回 AbortController,可调用 abort() 停止。
 */
export function aiChatStream(messages, { onTool, onMessage, onDone, onError }) {
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
      let buffer = ''
      let event = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            event = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dispatch(event, line.slice(5).trim(), { onTool, onMessage, onDone, onError })
          }
        }
      }
      if (buffer.startsWith('data:')) {
        dispatch(event, buffer.slice(5).trim(), { onTool, onMessage, onDone, onError })
      }
      onDone?.()
    } catch (err) {
      if (err.name === 'AbortError') return
      onError?.(err.message || '网络错误')
    }
  })()

  return controller
}

function dispatch(event, data, handlers) {
  if (!data) return
  let payload
  try {
    payload = JSON.parse(data)
  } catch {
    return
  }
  if (event === 'tool' && payload.name) {
    handlers.onTool?.(payload)
  } else if (event === 'message' && payload.content) {
    handlers.onMessage?.(payload.content)
  } else if (event === 'error' && payload.message) {
    handlers.onError?.(payload.message)
  }
}
