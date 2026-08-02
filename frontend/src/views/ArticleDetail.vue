<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { articleApi, commentApi, likeApi } from '../api'
import { useUserStore } from '../stores/user'
import { renderMarkdown } from '../utils/markdown'
import CommentItem from '../components/CommentItem.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const articleId = computed(() => Number(route.params.id))
const article = ref(null)
const comments = ref([])
const commentText = ref('')
const liked = ref(false)
const likeCount = ref(0)
const submitting = ref(false)
const liking = ref(false)

const rendered = computed(() => renderMarkdown(article.value?.content))

async function loadDetail() {
  const data = await articleApi.detail(articleId.value)
  article.value = data
  liked.value = data.liked
  likeCount.value = data.likeCount
}

async function loadComments() {
  comments.value = await commentApi.list(articleId.value)
}

async function toggleLike() {
  if (!store.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  liking.value = true
  try {
    const data = await likeApi.toggle(articleId.value)
    liked.value = data.liked
    likeCount.value = data.likeCount
  } finally {
    liking.value = false
  }
}

async function submitComment() {
  if (!commentText.value.trim()) {
    ElMessage.warning('评论内容不能为空')
    return
  }
  submitting.value = true
  try {
    await commentApi.create(articleId.value, { content: commentText.value })
    commentText.value = ''
    ElMessage.success('评论成功')
    await loadComments()
    await loadDetail()
  } finally {
    submitting.value = false
  }
}

async function deleteArticle() {
  await ElMessageBox.confirm('确定删除这篇文章吗?此操作不可恢复。', '删除文章', { type: 'warning' })
  await articleApi.remove(articleId.value)
  ElMessage.success('已删除')
  router.push('/')
}

async function deleteComment(id) {
  await ElMessageBox.confirm('确定删除这条评论吗?其下回复将一并删除。', '删除评论', { type: 'warning' })
  await commentApi.remove(id)
  ElMessage.success('已删除')
  await loadComments()
  await loadDetail()
}

const canDeleteArticle = computed(
  () => store.isLoggedIn && (store.isAdmin || article.value?.author?.id === store.user?.id)
)

const canDeleteComment = (comment) =>
  store.isLoggedIn && (store.isAdmin || comment.user?.id === store.user?.id)

function loginRequired() {
  if (store.isLoggedIn) return true
  ElMessage.warning('请先登录')
  router.push('/login')
  return false
}

onMounted(() => {
  loadDetail()
  loadComments()
})
</script>

<template>
  <div v-if="article">
    <el-card class="detail-card" shadow="never">
      <h1 class="detail-title">{{ article.title }}</h1>
      <div class="detail-meta">
        <router-link :to="`/user/${article.author?.id}`" class="author">
          <el-avatar :size="28" :src="article.author?.avatar" />
          {{ article.author?.nickname || article.author?.username }}
        </router-link>
        <span>{{ article.createdAt?.slice(0, 10) }}</span>
        <span>浏览 {{ article.viewCount }}</span>
        <el-tag v-for="tag in article.tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
        <div class="ops">
          <el-button v-if="canDeleteArticle" type="danger" link @click="deleteArticle">删除</el-button>
          <el-button
            v-if="store.isLoggedIn && article.author?.id === store.user?.id"
            type="primary"
            link
            @click="router.push(`/write/${article.id}`)"
          >编辑</el-button>
        </div>
      </div>
      <img v-if="article.cover" :src="article.cover" class="detail-cover" alt="cover" />
      <article class="markdown-body" v-html="rendered" />
      <div class="like-bar">
        <el-button
          :type="liked ? 'primary' : 'default'"
          round
          :loading="liking"
          @click="toggleLike"
        >{{ liked ? '已点赞' : '点赞' }} {{ likeCount }}</el-button>
      </div>
    </el-card>

    <el-card class="comment-card" shadow="never">
      <template #header>评论 ({{ comments.length }})</template>
      <div v-if="store.isLoggedIn" class="comment-input">
        <el-input
          v-model="commentText"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          placeholder="写下你的评论…"
        />
        <div class="comment-submit">
          <el-button type="primary" :loading="submitting" @click="submitComment">发表评论</el-button>
        </div>
      </div>
      <el-empty v-else description="登录后即可评论">
        <el-button type="primary" @click="router.push('/login')">去登录</el-button>
      </el-empty>

      <div v-if="comments.length" class="comment-list">
        <CommentItem
          v-for="comment in comments"
          :key="comment.id"
          :comment="comment"
          :can-delete="canDeleteComment(comment)"
          @delete="deleteComment"
          @refresh="loadComments"
        />
      </div>
    </el-card>
  </div>
  <el-empty v-else description="文章不存在或已删除" />
</template>

<style scoped>
.detail-card {
  border-radius: 8px;
  margin-bottom: 20px;
}

.detail-title {
  font-size: 26px;
  color: #303133;
  margin-bottom: 14px;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #909399;
  font-size: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
  flex-wrap: wrap;
}

.author {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #606266;
}

.author:hover {
  color: #409eff;
}

.ops {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.detail-cover {
  width: 100%;
  max-height: 320px;
  object-fit: cover;
  border-radius: 8px;
  margin: 16px 0 4px;
}

.markdown-body {
  padding: 20px 4px;
  line-height: 1.75;
  font-size: 15px;
}

.like-bar {
  display: flex;
  justify-content: center;
  padding-top: 8px;
}

.comment-card {
  border-radius: 8px;
}

.comment-input {
  margin-bottom: 16px;
}

.comment-submit {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.comment-list {
  border-top: 1px solid #ebeef5;
}
</style>

<style>
.markdown-body h1,
.markdown-body h2,
.markdown-body h3 {
  margin: 1.2em 0 0.6em;
  color: #303133;
}

.markdown-body p {
  margin: 0.8em 0;
}

.markdown-body code {
  background: #f0f2f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}

.markdown-body pre {
  background: #282c34;
  color: #abb2bf;
  padding: 14px 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 1em 0;
}

.markdown-body pre code {
  background: transparent;
  padding: 0;
  color: inherit;
}

.markdown-body blockquote {
  border-left: 4px solid #409eff;
  background: #f0f7ff;
  margin: 1em 0;
  padding: 8px 14px;
  color: #606266;
}

.markdown-body img {
  max-width: 100%;
}

.markdown-body table {
  border-collapse: collapse;
  margin: 1em 0;
}

.markdown-body th,
.markdown-body td {
  border: 1px solid #dcdfe6;
  padding: 8px 12px;
}
</style>
