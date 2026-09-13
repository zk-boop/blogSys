/**
 * AI 对话的本地留档:一份刷新、切页都还在的消息列表。
 *
 * <p>此前这些消息只活在一个 `ref([])` 里,于是 F5 之后什么都没了 —— 用户看到的是
 * 「我明明问过」和一片欢迎语。存下来这件事本身不难,难的是三件容易做错的事:
 *
 * <p>1. **归属**。同一个浏览器里换个人登录,不该看到上一个人的对话。所以每份记录都
 * 写着 `userId`,`load` 只认相等的那一份 —— 宁可什么都不显示,也不显示错的。
 * <p>2. **诚实**。刷新或离开页面时正在流式回答的那条会被原样存下来,半截回答如果
 * 看起来和完整回答一模一样,就是在骗用户。`revive` 因此把没写完的痕迹换成
 * `interrupted`,界面那句「回答未完成」才有的可说。
 * <p>3. **存不下也不能崩**。配额满、隐私模式下 storage 会抛异常,而聊天不该因为
 * 留档失败就跟着坏掉 —— 所有读写都是尽力而为,绝不把异常抛给调用方。
 *
 * <p><b>这个 module 不 import 任何东西。</b>storage 由调用方注入(生产接线是
 * `window.sessionStorage`,见 `views/AiChat.vue`),因此它能在 `node --test` 下
 * 用一个内存 Map 直接测。
 */

/** 存下来的上限(条)。比发出去的多得多:界面要能把上一场对话完整摊开,发不发得下另说。 */
export const HISTORY_LIMIT = 60

/**
 * 发给服务端的上限(条)。**这个数是后端定的,不是随手取的**:
 * `ChatController.ChatRequest.messages` 上有 `@Size(max = 50)`,整份历史聊长了必然
 * 400「历史消息过多」;而后端喂给模型的本来也只有最近 20 条 —— 多发的部分既过不了
 * 校验,也不影响回答。
 */
export const OUTGOING_LIMIT = 20

/**
 * 取最后 `limit` 条。
 *
 * <p>不写 `slice(-limit)`:limit 为 0 时 `-0` 会让 slice 返回**全部**元素 ——
 * 一个「不许存」的配置反而存下最多,这种反话没人想在事故里学到。
 */
function tail(list, limit) {
  return list.slice(Math.max(0, list.length - limit))
}

/**
 * 一份留档。key 与上限都能换,storage 完全由调用方决定。
 *
 * @param {{storage: {getItem: Function, setItem: Function, removeItem: Function},
 *   key?: string, limit?: number}} options
 * @returns {{load: (userId: unknown) => object[], save: (userId: unknown, messages: object[]) => void,
 *   clear: () => void}}
 */
export function createChatHistory({ storage, key = 'ai-chat', limit = HISTORY_LIMIT }) {
  return {
    /**
     * 读回某个用户的对话。
     *
     * <p>任何一处对不上都返回 `[]`,**绝不抛异常**:存储里的东西可以被用户手改、
     * 被旧版本写坏,或者干脆是上一个人留下的。拿不准就当没存过 —— 界面退化成
     * 「一场新对话」,而不是一页错误。
     */
    load(userId) {
      let raw = null
      try {
        raw = storage.getItem(key)
      } catch {
        // 隐私模式下连「读」都可能被拒。读不到就当没存过。
        return []
      }
      if (!raw) return []

      let payload
      try {
        payload = JSON.parse(raw)
      } catch {
        return []
      }

      if (!payload || typeof payload !== 'object') return []
      // 版本号:载荷形状以后会变,认不出来的版本一律当没存过,不做猜测式兼容
      if (payload.v !== 1) return []
      // 防串号的那一条。换个人登录时这里就对不上,于是他的界面是干净的 ——
      // 少了这一句,下一个登录的人会读到上一个人的整场对话。
      if (payload.userId !== userId) return []
      if (!Array.isArray(payload.messages)) return []

      // 先按上限裁掉多余的,再交给 revive:裁掉的那些没必要白跑一遍
      return revive(tail(payload.messages, limit))
    },

    /**
     * 落盘。**尽力而为**:存不下(配额满、隐私模式)就吞掉 ——
     * 留档失败不该把正在进行的聊天弄崩。
     */
    save(userId, messages) {
      try {
        // 每份记录都带着写入时的 userId,load 才有的可比
        storage.setItem(key, JSON.stringify({ v: 1, userId, messages: tail(messages, limit) }))
      } catch {
        // 丢一次留档,换聊天继续
      }
    },

    /** 清空。同样尽力而为:删不掉最多以后被 load 的校验挡掉,聊天不该因此报错。 */
    clear() {
      try {
        storage.removeItem(key)
      } catch {
        // 同上
      }
    },
  }
}

/**
 * 把「上次没写完的那条」标出来。
 *
 * <p>刷新或离开页面时,正在流式回答的那条会带着 `pending: true` 被原样存下来。
 * 半截回答如果和一次完整回答长得一样,就是在骗用户(这个仓库对这件事有明确立场,
 * 见 `api/aiEvents.js` 里「truncated 绝不能触发 onDone」)。所以载入时把它换成
 * `interrupted: true`,让界面能明说「回答未完成」。
 *
 * <p>返回**新数组**;需要改写的那条是**新对象**,入参不被原地修改。其余原样透传:
 * `load` 交进来的本来就是刚解析出来的新鲜对象,没有别人共享,没必要再复制一份。
 *
 * @param {object[]} messages
 * @returns {object[]} 非数组入参返回 `[]`
 */
export function revive(messages) {
  if (!Array.isArray(messages)) return []
  return messages
    // 不是对象的元素直接丢掉:模板会在 `message.role` 上把整页渲染弄炸,
    // 而存储是可以被手改的 —— 在门口筛掉,比让页面白屏好。
    .filter((m) => m && typeof m === 'object')
    // 只有 pending 严格等于 true 的那条要改写(别的值一概当作没有这个标记);
    // 其余原样透传,不当复制品。
    .map((m) => (m.pending === true ? { ...m, pending: false, interrupted: true } : m))
}

/**
 * 挑出发给服务端的那一份。
 *
 * <p>此前是整份 `messages` 直接发出去,聊长了必然 400(见 {@link OUTGOING_LIMIT});
 * 而且带上了 `tools`/`error`/`pending`/`interrupted` —— 后端不需要知道气泡长什么样,
 * 契约里只有 `role` 与 `content`。
 *
 * @param {object[]} messages
 * @param {number} limit
 * @returns {{role: string, content: string}[]}
 */
export function outgoing(messages, limit = OUTGOING_LIMIT) {
  const list = Array.isArray(messages) ? messages : []
  const payload = list
    // 空的 assistant 消息(刚建好、还没等到第一个字)没有内容可发,后端只会平白
    // 多花一次调用。user 消息照发:那是用户真的按了发送。
    .filter((m) => m && (m.role === 'user' || (m.role === 'assistant' && m.content)))
    // 只留契约里的两个字段 —— 界面字段一个都不往线上带
    .map((m) => ({ role: m.role, content: m.content }))
  return tail(payload, limit)
}
