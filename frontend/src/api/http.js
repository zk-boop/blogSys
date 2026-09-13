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

http.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      if (res.code === 401) {
        // 「登出 + 跳登录」的唯一归属。这里只负责把话说给用户听。
        session.unauthorized()
      }
      ElMessage.error(res.message || '请求失败')
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
    ElMessage.error(message || '网络错误')
    return Promise.reject(error)
  }
)

export default http
