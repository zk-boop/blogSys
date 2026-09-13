import { computed, ref } from 'vue'

/**
 * 一页数据的请求状态。
 *
 * <p>此前 6 个 view 各自持有一份相同的五元组(`page`/`size`/`keyword`/`loading`/`total`)、
 * 各自一份几乎逐字相同的 `load` 函数体、各自一块逐字相同的分页模板。真正的代价不是重复,
 * 而是**失败无法表达**:15 个 view 里只有 1 个 `catch`、零个错误态,而 `http.js` 早已弹过
 * toast 并 reject。请求失败时 `loading` 被清掉、数组仍是 `[]`,于是首页把它渲染成
 * 「还没有文章,快来写第一篇吧」,并附上一个号召按钮。
 *
 * <p>这里把状态收成一处,并给失败一个明确的表达:{@link phase}。
 *
 * @param {(params: {page: number, size: number, keyword: string}) =>
 *   Promise<{records: unknown[], total: number}>} fetchPage
 *   只提供「怎么取这一页」。各 view 自己的筛选条件(标签、状态、用户 id、当前 tab)
 *   由它闭包带走 —— 模块不该知道这些概念。
 * @param {{size?: number, initialKeyword?: string}} [options]
 */
export function useList(fetchPage, options = {}) {
  const records = ref([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(options.size ?? 10)
  const keyword = ref(options.initialKeyword ?? '')
  const loading = ref(false)
  /** `null` 表示没失败;否则是给用户看的一句话。 */
  const failure = ref(null)

  /**
   * 四个互斥的界面状态。视图据此分支,于是「失败」不再需要被渲染成「没有数据」——
   * 这两件事此前在界面上长得一模一样。
   */
  const phase = computed(() => {
    if (loading.value) return 'loading'
    if (failure.value) return 'failed'
    return records.value.length ? 'ready' : 'empty'
  })

  async function load() {
    loading.value = true
    failure.value = null
    try {
      const data = await fetchPage({ page: page.value, size: size.value, keyword: keyword.value })
      records.value = data?.records ?? []
      total.value = data?.total ?? 0
    } catch (error) {
      // 清空并把原因留下:继续显示上一次的列表会让人以为那就是当前结果,
      // 而显示「没有数据」则是在为一次服务端故障背书。
      records.value = []
      total.value = 0
      failure.value = messageOf(error)
    } finally {
      loading.value = false
    }
  }

  /** 筛选条件变化时的统一动作:回到第 1 页再取。这段此前在 3 个 view 里各写了一遍。 */
  async function search() {
    page.value = 1
    await load()
  }

  /** 翻页。分页组件的 `current-change` 接它 —— 于是 `page` 只有一个写入者。 */
  async function goTo(target) {
    page.value = Number(target) || 1
    await load()
  }

  return { records, total, page, size, keyword, loading, failure, phase, load, search, goTo }
}

/**
 * 两种错误形状的统一读法。
 *
 * <p>`api/http.js` 对业务错误返回 `new Error(message)`,对 HTTP 错误返回原始的 axios
 * error(消息藏在 `error.response.data.message` 里)。这里是那处不一致的**适配点** ——
 * 视图只需要问「给用户看什么」,不必知道错误是从哪条分支出来的。
 */
export function messageOf(error) {
  return error?.response?.data?.message || error?.message || '加载失败,请稍后重试'
}
