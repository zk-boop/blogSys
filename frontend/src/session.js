/**
 * 会话的唯一归属:token 注入、401 反应、跳转目标。
 *
 * <p>「带上 token;遇到 401 就登出并跳 /login」此前有三份实现 —— axios 拦截器
 * (`api/http.js`)、fetch 助手(`api/ai.js`)、router 守卫 —— 外加四处 view 复制,
 * 7 个地方能重定向,其中一处(`ArticleDetail.vue` 的 `loginRequired`)还是死代码。
 * 于是「会话过期之后会发生什么」这件事,没有任何一处能回答。
 *
 * <p><b>这个 module 不 import 任何东西。</b>状态、跳转、提示都由调用方作为 port 注入
 * (生产接线见 `session-instance.js`),因此它能在 `node --test` 下直接测,也才有
 * 「两个真 adapter 共用一个 seam」可言:生产 adapter 是 localStorage + vue-router +
 * element-plus,测试 adapter 是几个内存对象。
 *
 * @param {object} ports
 * @param {() => string} ports.readToken           当前 token(空串表示未登录)
 * @param {() => void} ports.clearAuth             清掉会话状态(登出)
 * @param {() => string} ports.currentPath         当前完整路径,用于登录后跳回来
 * @param {(path: string) => void} ports.navigate  跳转
 * @param {(message: string) => void} ports.notify 给用户的轻提示
 */
export function createSession({ readToken, clearAuth, currentPath, navigate, notify }) {

  /**
   * 登录页的位置,并带上回来的路。
   *
   * <p>此前 7 处跳转里只有 router 守卫带了 `redirect`,view 与两个 adapter 都是裸跳
   * `/login` —— 登录完就回不到原来那一页。现在「跳到哪里」只有这一个主人。
   */
  function loginTarget(from = currentPath()) {
    const back = from && !from.startsWith('/login') ? from : ''
    return back ? `/login?redirect=${encodeURIComponent(back)}` : '/login'
  }

  return {
    /**
     * 请求头。axios 与 fetch 两个 adapter 共用这一份 ——
     * 此前 `api/ai.js` 自己重建了一份一模一样的。
     */
    authHeaders() {
      const token = readToken()
      return token ? { Authorization: `Bearer ${token}` } : {}
    },

    loginTarget,

    /**
     * **唯一的 401 反应。**
     *
     * <p>已经登出过就不再重复跳转 —— 并发请求会同时拿到 401,否则用户会被推着跳好几次。
     *
     * @returns {string} 给用户看的那句话。怎么呈现由 adapter 决定:axios 走 toast,
     *   fetch adapter 走它自己的 onError 通道。
     */
    unauthorized() {
      const message = '登录已过期,请重新登录'
      if (readToken()) {
        clearAuth()
        navigate(loginTarget())
      }
      return message
    },

    /**
     * view 只问这一个问题:「登录了吗?没登录就提示 + 跳转」。
     *
     * @returns {boolean} 已登录为 true;否则 false(提示与跳转已经发生)
     */
    requireLogin() {
      if (readToken()) {
        return true
      }
      notify('请先登录')
      navigate(loginTarget())
      return false
    },
  }
}
