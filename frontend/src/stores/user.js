import { defineStore } from 'pinia'
import { authApi } from '../api'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null'),
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    isAdmin: (state) => state.user?.role === 'ADMIN',
  },
  actions: {
    setAuth(token, user) {
      this.token = token
      this.user = user
      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(user))
    },
    setUser(user) {
      this.user = user
      localStorage.setItem('user', JSON.stringify(user))
    },
    /**
     * 用服务端的事实刷新本地快照。
     *
     * <p>此前这个方法**全库无调用者**,于是 `isAdmin` 完全来自登录那一刻写进 localStorage
     * 的快照 —— 管理员改了某人的角色、或封禁了某人之后,对方客户端在重新登录前仍按旧角色
     * 渲染。(这不是越权:`JwtAuthFilter` 对封禁用户会直接拦截,所以影响限于界面陈旧。)
     * 现在它在应用启动时被调用一次(见 `App.vue` 的 `onMounted`)。
     */
    async fetchMe() {
      if (!this.token) return
      try {
        this.setUser(await authApi.me())
      } catch {
        // 什么都不做。401 的反应归 session(src/session.js)：它已经清了会话并跳转,
        // 这里再 logout 一次是第二个主人。而其它错误(网络抖动、500)更不该把用户踢出去 ——
        // 此前这里一律 `this.logout()`,一次网络故障就等于强制登出。
        // 刷新失败的正确结果是「快照保持原样」,而不是「当作没登录」。
      }
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    },
  },
})
