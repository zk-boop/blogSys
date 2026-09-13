/**
 * 一个 router outlet 该用的重挂载标识(D7 的修法)。
 *
 * <p>起因:App.vue 的 `<component :is>` 原来没有 `:key`,于是**同一个 route record**
 * 下只有 params 变化时(如 `/article/1` → `/article/2`),Vue 复用了同一个组件实例 ——
 * URL 更新了,正文停留在旧数据。在意的 view 只能各自发明 watcher 去补偿,而全库只有
 * Home 补了;其余 9 个 module 各假定了一次「不会变」。
 *
 * <p>规则:标识由**这个 outlet 自己那一层的记录**,加上它消费的 params 与 query 组成。
 *
 * <ul>
 *   <li>同记录、params 变 ⇒ 标识变 ⇒ 重挂载 —— 这是 D7</li>
 *   <li>同记录、query 变 ⇒ 标识变 ⇒ 重挂载 —— 于是 Home 那条补偿 watcher 可以删掉</li>
 *   <li>只有子记录变(`/admin/users` → `/admin/articles`)⇒ **顶层标识不变** ⇒
 *       布局不重挂载。这一条是有意的:否则每切一个后台标签页都要连带重播一次过渡动画、
 *       并重跑布局自己的挂载逻辑</li>
 * </ul>
 *
 * <p>代价说明:query 一律参与标识,所以一个内容其实不依赖 query 的记录,在 query 变化时
 * 也会重挂载(今天只有 Home 用 query,故无实际影响)。若将来某个记录不希望如此,
 * 应在规则里按 `meta` 显式声明,而不是让调用方各自绕过 —— 身份必须只有这一个主人。
 *
 * @param {import('vue-router').RouteLocationNormalized} route 当前路由
 * @param {number} depth outlet 所在层级:App.vue 里渲染顶层记录的 outlet 是 0,
 *   布局组件内部的子 outlet 是 1
 * @returns {string} 可直接用作 `:key` 的字符串
 */
export function outletKey(route, depth = 0) {
  const record = route?.matched?.[depth]
  if (!record) {
    // 没有匹配到记录(理论上不该发生)—— 退回整条 URL,宁可多挂载也不留着旧内容
    return route?.fullPath ?? ''
  }

  const parts = [record.path]

  // 只取**这个记录自己声明的** params:子记录的 params 变化不该把父布局一起重挂载
  for (const name of Object.keys(route.params ?? {}).sort()) {
    if (record.path.includes(`:${name}`)) {
      parts.push(`${name}=${route.params[name]}`)
    }
  }

  for (const name of Object.keys(route.query ?? {}).sort()) {
    parts.push(`${name}=${[].concat(route.query[name]).join(',')}`)
  }

  return parts.join('|')
}
