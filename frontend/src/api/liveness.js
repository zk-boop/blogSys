/**
 * 「服务器还在吗」的守望者。
 *
 * <p>SSE 里最难堪的一件事:「AI 正在想」与「连接已经死了」在浏览器上长得一模一样 ——
 * 两者都是「一直没有新数据」。服务端每 10 秒发一个注释帧(`: ping`)做心跳,沉默于是
 * 变成一个可观察的事实:按心跳的节奏重新起表,表真的响了就说明服务器已经缺了好几拍。
 *
 * <p>计时器从外面注入({@link setTimer}/{@link clearTimer}),所以 `liveness.test.js`
 * 不必依赖真实时间:假计时器记下回调、由测试自己决定什么时候「到点」,30 秒这条策略
 * 能在毫秒内被验证。
 *
 * @param {{onStall: () => void, timeoutMs?: number,
 *   setTimer?: (fn: () => void, ms: number) => unknown,
 *   clearTimer?: (id: unknown) => void}} options
 *   `onStall` 在服务器沉默超过 `timeoutMs` 时被调用一次 —— 此刻连接已经断了。
 * @returns {{beat: () => void, stop: () => void}}
 */
export function createAliveWatch({
  onStall,
  // 30 秒 = 连丢三次心跳。一次网络抖动、一次稍长的模型推理或一次 GC 停顿都可能
  // 让单次心跳迟到,但连续三次都不到就不该再解释成「慢」—— 那时的等待是个错觉。
  timeoutMs = 30000,
  setTimer = setTimeout,
  clearTimer = clearTimeout,
}) {
  let timer = null
  let stopped = false

  return {
    /** 收到一帧(心跳)就重新起表:沉默要从最后一次「还活着」的证据算起。 */
    beat() {
      // 停表之后不再起表。迟到的帧不能把一场已经结束的对话救活 ——
      // 用户点了「停止」或回答已经收尾之后,界面不该再冒出一条「连接中断」。
      if (stopped) return
      if (timer !== null) clearTimer(timer)
      timer = setTimer(onStall, timeoutMs)
    },

    /** 对话收尾(完成、出错、被用户停掉)时停表。幂等。 */
    stop() {
      stopped = true
      if (timer !== null) clearTimer(timer)
      timer = null
    },
  }
}
