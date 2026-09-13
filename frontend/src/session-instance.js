import { ElMessage } from 'element-plus'

import { createSession } from './session'
import { useUserStore } from './stores/user'

/**
 * 生产环境的 port 接线:`session.js` 本身不认识 localStorage、vue-router 或
 * element-plus,认识它们的是这里 —— 这就是「两个真 adapter,一个 seam」里的生产 adapter。
 *
 * <p>跳转用动态 `import()` 拿 router:router 的守卫要用这个 session,
 * 静态 import 会成环。动态引入只在真正要跳转时才求值,那时两边都已初始化完毕。
 */
export const session = createSession({
  readToken: () => useUserStore().token,
  clearAuth: () => useUserStore().logout(),
  currentPath: () => window.location.pathname + window.location.search,
  navigate: (path) => {
    import('./router').then((module) => module.default.push(path))
  },
  notify: (message) => ElMessage.warning(message),
})
