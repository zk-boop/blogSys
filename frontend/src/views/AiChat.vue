<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiChatStream } from '../api/ai'
import { createAliveWatch } from '../api/liveness'
import { createChatHistory, outgoing } from '../chatHistory'
import { useUserStore } from '../stores/user'
import { renderMarkdown } from '../utils/markdown'

const store = useUserStore()

/**
 * 消息的本地留档:刷新、切页都要活下来。
 *
 * <p>用 sessionStorage 而不是 localStorage,因为要的正好是它的形状:
 * **每个标签页各自一份** —— 两个标签页同时开着 /ai 不会互相覆盖对方的对话
 * (localStorage 是全域共享的,两个标签页会一直抢同一份记录);F5 与切页都在;
 * 关掉标签页就消失,不在磁盘上久留。想让它跨浏览器重启也留着,把这里换成
 * `window.localStorage` 就行(一行)—— chatHistory.js 不认识任何一种 storage。
 */
/**
 * sessionStorage 也可能取不到:某些隐私设置下连这个 getter 本身都会抛 SecurityError。
 * 留档的立场是「存不下也不能崩」(见 chatHistory.js),所以退化成 null ——
 * 而它对这个值的态度就是「当没存过」,所有读写本来就在 try 里。
 */
function safeSessionStorage() {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

const history = createChatHistory({ storage: safeSessionStorage() })

/**
 * 这份留档属于谁。**在 setup 时定下来,而不是每次落盘现问一次** —— 用户在回答中途点了
 * 退出登录时 `store.user` 已经是 null,那时若现问一次,这一整份记录会被写成「无主」,
 * 本人下次登录就读不回来了:一次登出等于丢了一整场对话。登出与换人都会让这个视图
 * 卸载重挂,所以这个值在本视图活着的期间是稳的。
 */
const ownerId = store.user?.id ?? null

// 初始值来自留档:刷新页面、切走再切回来,上一场对话都还在。
// 载荷坏了、归属对不上(换了个人登录)都由 load 内部处理成 []
const messages = ref(history.load(ownerId))
const input = ref('')
const sending = ref(false)
const listRef = ref(null)
const controller = ref(null)
/**
 * 当前这场对话的存活守望者。故意不是 ref:它只被回调读写,进模板没有意义,
 * 包成响应式反而会让人以为界面要跟着它重渲染。
 */
let aliveWatch = null

/**
 * 落盘。deep 是必须的:流式回答是 `assistant.content += delta` **原地**改字段,
 * 没有 deep 就只有 push 新消息那一刻写一次,整段半截回答全丢。
 * 代价是每个增量都会触发一次「序列化整份历史 + 写 storage」(一次回答几十上百次);
 * 上限 60 条下这点开销可以接受。真要节流,节流点就在这个回调里(加一个 trailing
 * 的 debounce)—— 但 onBeforeUnmount 里那次显式 save 必须仍然立即执行。
 */
const stopHistoryWatch = watch(
  messages,
  () => history.save(ownerId, messages.value),
  { deep: true },
)

const suggestions = [
  '站内有哪些热门文章?',
  '帮我找找关于 Java 的文章',
  '站点目前的数据怎么样?',
  '有没有推荐的深度好文?',
]

function pushMessage(message) {
  messages.value.push(message)
  scrollToBottom()
}

function scrollToBottom() {
  nextTick(() => {
    listRef.value?.scrollTo({ top: listRef.value.scrollHeight, behavior: 'smooth' })
  })
}

function render(content) {
  return renderMarkdown(content)
}

async function send(question) {
  const text = (question ?? input.value).trim()
  if (!text || sending.value) return
  input.value = ''
  pushMessage({ role: 'user', content: text })
  // pending 是「这条还没写完」的记号。它会被一起存下来,载入时由 chatHistory 的 revive
  // 换成 interrupted —— 见下面 onStall / onDone / onError / markInterrupted 几处收尾。
  const assistant = reactive({ role: 'assistant', content: '', tools: [], error: null, pending: true })
  pushMessage(assistant)
  sending.value = true

  // 只发最近 OUTGOING_LIMIT 条,且只带 role/content。此前是整份 messages 直接发出去,
  // 聊长了必然被后端的 @Size(max = 50) 挡成 400「历史消息过多」;后端喂给模型的本来
  // 也只有最近 20 条(见 chatHistory.js 的 OUTGOING_LIMIT)。
  const payload = outgoing(messages.value)

  // 「AI 正在想」与「连接已经断了」在界面上本来是同一幅画面:都在等一个不来的字节。
  // 服务端每 10 秒一个心跳,于是沉默变成可观察的事实 —— 表响就说明这场对话已经断了,
  // 继续转圈只是在骗用户(以及让「停止」按钮看起来还有意义)。
  //
  // 句柄用这场对话自己的那个 watch(闭包里捕获),而不是作用域上那个变量:
  // 停的必须是**这一场**的表。
  const watch = createAliveWatch({
    onStall: () => {
      // 半截回答留在气泡里不删,但必须明说它不完整 —— 否则它和一次完整回答长得一样。
      // 这里只落 error、不落 interrupted:连接断了还附带一条「重试」的出路,那句话
      // 已经把「没写完」说清楚了,再叠一句「回答未完成」是同一件事说两遍。
      assistant.error = '连接已中断,请重试'
      assistant.pending = false
      sending.value = false
      watch.stop()
      // 表响 = 连接已经死了,那条 fetch 还挂在读一个不会来的字节。主动 abort 把它收掉,
      // 顺带保证它的迟到回调不会再动界面(否则用户重发时会被上一场的结果打断)。
      controller.value?.abort()
      scrollToBottom()
    },
  })
  aliveWatch = watch

  controller.value = aiChatStream(payload, {
    onAlive: () => watch.beat(),
    onTool: (tool) => {
      assistant.tools.push(tool)
      scrollToBottom()
    },
    onMessage: (delta) => {
      assistant.content += delta
      scrollToBottom()
    },
    onDone: () => {
      watch.stop()
      // done 是唯一的「写完了」凭证,这条从此不带 pending(落盘后也不会被 revive 标成中断)
      assistant.pending = false
      sending.value = false
    },
    onError: (message) => {
      watch.stop()
      assistant.error = message
      // 出错同样是收尾:表停了、sending 掉了,pending 也得跟着掉 ——
      // 否则这条会被存成一个永远「正在回答」的样子
      assistant.pending = false
      sending.value = false
      scrollToBottom()
    },
  })
}

/**
 * 给「正在回答的那条」收尾:半截回答不能留着 pending 这个谎,更不能和完整回答长得一样。
 *
 * <p>「停止」与「离开页面」都走这里 —— 两处说的是同一件事(这场对话到此为止,答案
 * 没写完),拆成两份实现迟早会漂移。
 */
function markInterrupted() {
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant' && last.pending === true) {
    last.pending = false
    last.interrupted = true
  }
}

function stop() {
  controller.value?.abort()
  aliveWatch?.stop()
  markInterrupted()
  sending.value = false
  ElMessage.info('已停止生成')
}

function retry() {
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant' && last.error) {
    messages.value.pop()
    const question = messages.value[messages.value.length - 1]?.content
    if (question) {
      messages.value.pop()
      send(question)
    }
  }
}

/** 清空当前对话。不可撤销,所以照 App.vue 的 logout() 那样先问一句。 */
async function clearChat() {
  try {
    await ElMessageBox.confirm('确定清空当前对话吗?', '提示', { type: 'warning' })
  } catch {
    // 用户点了取消(或按了 Esc)。这是预期结局而不是错误 —— 不接住它,每次取消都会
    // 多一个没人处理的 promise 拒绝,控制台上那行红字会让人以为是清空失败了。
    return
  }

  // 正在回答就一起停掉:留一条没有下文、也没人再听的半截回答,比什么都不留更糟;
  // 表更不该继续为空转。这里不用 markInterrupted —— 整份列表马上就没了,标了也白标。
  controller.value?.abort()
  aliveWatch?.stop()
  sending.value = false

  messages.value = []
  // 等深度 watcher 把「空历史」这一版写完再 clear:否则它那次写入会排在 clear 之后,
  // 存储里留下一份空记录(load 出来同样是 [],但「清空」就该什么都不剩)。
  await nextTick()
  history.clear()
}

// 带着上一场对话进来时(刷新、切页回来)直接落到最新一条。停在顶部会让人以为
// 「我刚问的那句没了」,而那恰好是这次改动要消灭的观感。
onMounted(() => {
  if (messages.value.length) scrollToBottom()
})

onBeforeUnmount(() => {
  // 离开页面就是结束这场对话。表停掉、连接主动断掉 —— 服务端也会因此发现没人再听,
  // 不必等它把整段回答生成完。
  aliveWatch?.stop()
  controller.value?.abort()

  if (sending.value) {
    markInterrupted()
    // 不指望上面那个深度 watcher 一定来得及跑:它要等一次 flush,而卸载就在眼前。
    // 这一条必须此刻显式写下去 —— 半截回答不能以完整回答的样子留在存储里。
    // 归属用 setup 时定下的 ownerId:此刻可能已经登出,store.user 是 null。
    history.save(ownerId, messages.value)
  }

  stopHistoryWatch()
})
</script>

<template>
  <div class="chat-page">
    <div class="chat-header">
      <div class="header-main">
        <div class="header-title">
          <span class="dot" />
          AI 助手
        </div>
        <div class="header-sub">站内搜索 · 数据统计 · 文章推荐</div>
      </div>
      <!-- 没有消息时按钮没有意义,而清空是不可撤销的:少一个能误点的东西 -->
      <el-button v-if="messages.length" text size="small" @click="clearChat">清空对话</el-button>
    </div>

    <div ref="listRef" class="chat-list">
      <div v-if="!messages.length" class="welcome">
        <div class="welcome-title">你好,我是 blogSys AI 助手</div>
        <div class="welcome-sub">我可以搜索文章、查看用户资料、统计站内数据、推荐文章,试试下面这些:</div>
        <div class="suggestions">
          <el-button v-for="s in suggestions" :key="s" plain size="small" @click="send(s)">
            {{ s }}
          </el-button>
        </div>
      </div>

      <div v-for="(message, idx) in messages" :key="idx" class="msg-row" :class="message.role">
        <div class="bubble">
          <template v-if="message.role === 'assistant'">
            <div v-if="message.content" class="markdown-body" v-html="render(message.content)" />
            <div v-if="message.error" class="error-text">{{ message.error }}</div>
            <!--
              上次没写完的那条(刷新或离开页面时正在流式回答)。它不是错误,所以不用红色,
              但必须说出来 —— 半截回答看起来和完整回答一样,就是在骗用户。
            -->
            <div v-if="message.interrupted" class="interrupted-text">回答未完成</div>
            <div v-if="sending && idx === messages.length - 1" class="typing">
              <span class="typing-dot" /><span class="typing-dot" /><span class="typing-dot" />
            </div>
            <div class="msg-actions">
              <el-button v-if="message.error" size="small" text type="primary" @click="retry">重试</el-button>
            </div>
          </template>
          <template v-else>
            <div class="user-text">{{ message.content }}</div>
          </template>
        </div>
      </div>
    </div>

    <div class="chat-input-bar">
      <div class="input-row">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入问题,Enter 发送(Shift+Enter 换行)"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="send-col">
          <el-button v-if="sending" type="danger" plain class="send-btn" @click="stop">停止</el-button>
          <el-button v-else type="primary" class="send-btn" :disabled="!input.trim()" @click="send()">发送</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px);
  max-width: 900px;
  margin: 0 auto;
  padding: 0 16px;
}

.chat-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 0 10px;
  border-bottom: 1px solid var(--border-color, #e4e7ed);
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--brand-color, #409eff);
  display: inline-block;
}

.header-sub {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-muted, #909399);
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 4px;
}

.welcome {
  text-align: center;
  margin-top: 12vh;
}

.welcome-title {
  font-size: 22px;
  font-weight: 600;
}

.welcome-sub {
  margin-top: 10px;
  color: var(--text-muted, #909399);
  font-size: 14px;
}

.suggestions {
  margin-top: 24px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
}

.msg-row {
  display: flex;
  margin-bottom: 16px;
}

.msg-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.7;
  font-size: 14px;
}

.msg-row.assistant .bubble {
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e4e7ed);
  border-radius: 10px 10px 10px 2px;
}

.msg-row.user .bubble {
  background: var(--brand-color, #409eff);
  color: #fff;
  border-radius: 10px 10px 2px 10px;
}

.user-text {
  white-space: pre-wrap;
}

.error-text {
  color: #f56c6c;
  margin-top: 6px;
}

/* 「回答未完成」走 .error-text 同一个路子(同一行、同样的间距),但颜色是弱提示色:
   它不是错误,只是没写完 —— 红字会让人以为出了问题、去找不存在的原因。 */
.interrupted-text {
  color: var(--text-muted, #909399);
  margin-top: 6px;
  font-size: 12px;
}

.msg-actions {
  margin-top: 4px;
}

.typing {
  display: flex;
  gap: 5px;
  padding: 6px 2px;
}

.typing-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-muted, #909399);
  animation: blink 1.2s infinite;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 80%, 100% {
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}

.markdown-body :deep(pre) {
  overflow-x: auto;
  padding: 10px;
  border-radius: 6px;
}

.markdown-body :deep(code) {
  font-size: 13px;
}

.chat-input-bar {
  padding: 12px 0 16px;
  border-top: 1px solid var(--border-color, #e4e7ed);
}

.input-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.input-row .el-textarea {
  flex: 1;
}

.send-col {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.send-btn {
  height: 52px;
  width: 76px;
  margin: 0;
}
</style>
