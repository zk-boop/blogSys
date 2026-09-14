import axios from 'axios'
import { ElMessage } from 'element-plus'

import { session } from '../session-instance'

const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

http.interceptors.request.use((config) => {
  // token 注入归 session —— 此前这里与 api/ai.js 各建了一份一模一样的请求头
  Object.assign(config.headers, session.authHeaders())
  return config
})

/**
 * 「这一条失败不必打扰读者」的唯一开关:{@code http.get(url, { silent: true })}。
 *
 * <p>默认契约仍是「失败就弹 toast」—— 那是绝大多数请求该有的样子。但正文不依赖的
 * 附加内容(作者卡、上一篇/下一篇)失败时,读者既看不懂也没法处理:一个 404 被弹成
 * 「网络错误」,只是在正文旁边制造噪音。这类调用方显式声明 silent,并把降级(不渲染
 * 那一块)留给自己负责。
 *
 * <p>登录失效(401)不适用 silent —— 它不是内容失败,而是会话状态变了,必须说。
 */
function shouts(config) {
  return !config?.silent
}

http.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      if (res.code === 401) {
        // 「登出 + 跳登录」的唯一归属。这里只负责把话说给用户听。
        session.unauthorized()
      }
      if (shouts(response.config)) {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message))
    }
    return res.data
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message
    if (status === 401) {
      session.unauthorized()
    }
    if (shouts(error.config)) {
      ElMessage.error(message || '网络错误')
    }
    // 统一错误形状。此前这条分支把**原始 axios error** 抛出去,而上面那条抛的是
    // new Error(message) —— 两种形状意味着每个调用方都得先判断自己拿到的是哪一种。
    // 现在一律是 Error(message),原始响应挂在 .response 上备查。
    const failure = new Error(message || '网络错误')
    failure.response = error.response
    return Promise.reject(failure)
  }
)

export default http
