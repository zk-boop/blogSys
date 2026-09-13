<script setup>
import { nextTick, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiChatStream } from '../api/ai'
import { renderMarkdown } from '../utils/markdown'

const messages = ref([])
const input = ref('')
const sending = ref(false)
const listRef = ref(null)
const controller = ref(null)

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
  const assistant = reactive({ role: 'assistant', content: '', tools: [], error: null })
  pushMessage(assistant)
  sending.value = true

  const history = messages.value
    .filter((m) => m.role === 'user' || (m.role === 'assistant' && m.content))
    .map((m) => ({ role: m.role, content: m.content }))

  controller.value = aiChatStream(history, {
    onTool: (tool) => {
      assistant.tools.push(tool)
      scrollToBottom()
    },
    onMessage: (delta) => {
      assistant.content += delta
      scrollToBottom()
    },
    onDone: () => {
      sending.value = false
    },
    onError: (message) => {
      assistant.error = message
      sending.value = false
      scrollToBottom()
    },
  })
}

function stop() {
  controller.value?.abort()
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
</script>

<template>
  <div class="chat-page">
    <div class="chat-header">
      <div class="header-title">
        <span class="dot" />
        AI 助手
      </div>
      <div class="header-sub">站内搜索 · 数据统计 · 文章推荐</div>
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
