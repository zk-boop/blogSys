<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { commentApi } from '../api'
import { session } from '../session-instance'
import { useUserStore } from '../stores/user'
import { displayAvatar, displayName } from '../utils/person'

const props = defineProps({
  comment: { type: Object, required: true },
  canDelete: { type: Boolean, default: false },
})
const emit = defineEmits(['delete', 'refresh'])

const store = useUserStore()

const replying = ref(false)
const replyText = ref('')
const replyingTo = ref(null)
const submitting = ref(false)
const expanded = ref(false)

const shownReplies = () => {
  if (!props.comment.replies?.length) return []
  return expanded.value ? props.comment.replies : props.comment.replies.slice(0, 2)
}

function startReply(comment) {
  if (!session.requireLogin()) return
  replyingTo.value = comment
  replyText.value = ''
  replying.value = true
}

async function submitReply() {
  if (!replyText.value.trim()) {
    ElMessage.warning('回复内容不能为空')
    return
  }
  submitting.value = true
  try {
    await commentApi.create(props.comment.articleId, {
      content: replyText.value,
      parentId: replyingTo.value.id,
    })
    replyText.value = ''
    replying.value = false
    ElMessage.success('回复成功')
    emit('refresh')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="comment-item">
    <router-link :to="`/user/${comment.user?.id}`">
      <el-avatar :size="32" :src="displayAvatar(comment.user)" />
    </router-link>
    <div class="comment-body">
      <div class="comment-head">
        <router-link :to="`/user/${comment.user?.id}`" class="comment-author">
          {{ displayName(comment.user) }}
        </router-link>
        <span class="comment-date">{{ comment.createdAt?.slice(0, 10) }}</span>
        <el-button v-if="store.isLoggedIn" class="reply-btn" link type="primary" @click="startReply(comment)">回复</el-button>
        <el-button v-if="canDelete" class="comment-del" type="danger" link @click="emit('delete', comment.id)">删除</el-button>
      </div>
      <p class="comment-content">{{ comment.content }}</p>

      <div v-if="replying" class="reply-input">
        <el-input
          v-model="replyText"
          type="textarea"
          :rows="2"
          maxlength="1000"
          :placeholder="`回复 @${displayName(replyingTo?.user)}`"
        />
        <div class="reply-actions">
          <el-button size="small" @click="replying = false">取消</el-button>
          <el-button size="small" type="primary" :loading="submitting" @click="submitReply">回复</el-button>
        </div>
      </div>

      <div v-if="comment.replies?.length" class="replies">
        <div v-for="reply in shownReplies()" :key="reply.id" class="reply-item">
          <router-link :to="`/user/${reply.user?.id}`">
            <el-avatar :size="24" :src="displayAvatar(reply.user)" />
          </router-link>
          <div class="reply-body">
            <div class="comment-head">
              <router-link :to="`/user/${reply.user?.id}`" class="comment-author">
                {{ displayName(reply.user) }}
              </router-link>
              <span v-if="reply.replyTo" class="reply-to">回复 @{{ displayName(reply.replyTo) }}</span>
              <span class="comment-date">{{ reply.createdAt?.slice(0, 10) }}</span>
              <el-button v-if="store.isLoggedIn" class="reply-btn" link type="primary" @click="startReply(reply)">回复</el-button>
              <el-button
                v-if="store.isAdmin || reply.user?.id === store.user?.id"
                class="comment-del"
                type="danger"
                link
                @click="emit('delete', reply.id)"
              >删除</el-button>
            </div>
            <p class="comment-content">{{ reply.content }}</p>
          </div>
        </div>
        <div v-if="comment.replies.length > 2" class="expand-bar">
          <el-button link type="primary" @click="expanded = !expanded">
            {{ expanded ? '收起回复' : `查看全部 ${comment.replies.length} 条回复` }}
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.comment-item {
  display: flex;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border-color);
}

.comment-body {
  flex: 1;
}

.comment-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.comment-author {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
}

.comment-author:hover {
  color: var(--brand-color);
}

.reply-to {
  font-size: 12px;
  color: var(--brand-color);
}

.comment-date {
  color: var(--text-muted);
  font-size: 12px;
}

.reply-btn {
  margin-left: auto;
}

.comment-del {
  margin-left: auto;
}

.comment-content {
  margin-top: 6px;
  line-height: 1.6;
  color: var(--text-primary);
  white-space: pre-wrap;
}

.reply-input {
  margin-top: 10px;
  background: var(--quote-bg);
  border-radius: 6px;
  padding: 10px;
}

.reply-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.replies {
  margin-top: 10px;
  background: var(--quote-bg);
  border-radius: 6px;
  padding: 6px 12px;
}

.reply-item {
  display: flex;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--border-color);
}

.reply-item:last-child {
  border-bottom: none;
}

.reply-body {
  flex: 1;
}

.expand-bar {
  padding: 6px 0;
}
</style>
