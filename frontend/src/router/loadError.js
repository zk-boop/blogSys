import { isNavigationFailure } from 'vue-router'

/**
 * 「页面加载失败」这条兜底路径的归属。
 *
 * <p>此前它散在三处、没有主人:`router/index.js` 里一个单槽注册表 + 一句裸启发式,
 * `App.vue` 里三条重试路径,`main.js` 里从不设置 `app.config.errorHandler`。
 * `App.vue` 共 10 次提交,其中 4 次是这套机制,`docs/lessons.md` 记着两个真实故障。
 * 这里收的是其中可以判定的那部分。
 *
 * <p>它不 import 任何有状态的东西(只用 vue-router 的纯判定函数),所以能进 `node --test`。
 */

/**
 * 这次路由错误值不值得弹「页面加载失败」。
 *
 * <p>**导航失败是正常的**——重复导航、被守卫取消、被新的导航打断,这些都是用户自己操作的
 * 结果,报错只会制造噪音。其余一律当成「这一次导航没能把页面拿出来」。
 *
 * <p>此前这里是一句裸启发式:`error.type !== undefined`。它碰巧对,因为 vue-router 的
 * `NavigationFailure` 恰好带 `type` 字段;但那只是个巧合 —— 任何带 `type` 的错误都会被
 * 悄悄吞掉,而任何不带 `type` 的导航失败都会被当成崩溃。vue-router 自己导出了判定函数,
 * 用它才是那句话的本意。
 */
export function shouldReportRouteError(error) {
  return !isNavigationFailure(error)
}

/**
 * 路由错误的订阅表。
 *
 * <p>此前它是一个变量:`onModuleLoadError(handler)` 直接覆盖 —— 第二个订阅者会**静默**
 * 顶掉第一个,而顶掉的后果正是「加载失败时没人弹兜底」。现在是一个集合,第二个订阅者
 * 只是多了一个,不会顶掉谁。
 *
 * @returns {{subscribe: (handler: Function) => (() => void), notify: (error: unknown) => boolean, size: number}}
 *   `subscribe` 返回退订函数;`notify` 返回「这次错误有没有被当成失败报出去」。
 */
export function createRouteErrorChannel() {
  const handlers = new Set()

  return {
    subscribe(handler) {
      handlers.add(handler)
      return () => handlers.delete(handler)
    },

    notify(error) {
      if (!shouldReportRouteError(error)) {
        return false
      }
      for (const handler of handlers) {
        handler(error)
      }
      return true
    },

    get size() {
      return handlers.size
    },
  }
}
