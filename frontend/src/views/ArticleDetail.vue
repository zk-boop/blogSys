<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { articleApi, commentApi, likeApi } from '../api'
import { useUserStore } from '../stores/user'
import { renderMarkdown, extractToc } from '../utils/markdown'
import { avatarSrc } from '../utils/avatar'
import CommentItem from '../components/CommentItem.vue'
import Lightbox from '../components/Lightbox.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const articleId = computed(() => Number(route.params.id))
const article = ref(null)
const comments = ref([])
const commentText = ref('')
const liked = ref(false)
const likeCount = ref(0)
const favorited = ref(false)
const submitting = ref(false)
const liking = ref(false)
const toc = ref([])
const activeToc = ref('')
const lightboxSrc = ref('')

const rendered = computed(() => renderMarkdown(article.value?.content))

async function loadDetail() {
  const data = await articleApi.detail(articleId.value)
  article.value = data
  liked.value = data.liked
  likeCount.value = data.likeCount
  favorited.value = data.favorited
  toc.value = extractToc(data.content)
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

async function toggleFavorite() {
  if (!store.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  const data = await articleApi.favorite(articleId.value)
  favorited.value = data.favorited
  ElMessage.success(data.favorited ? '已收藏' : '已取消收藏')
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

function jumpTo(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onBodyClick(event) {
  const target = event.target
  if (target && target.tagName === 'IMG') {
    lightboxSrc.value = target.currentSrc || target.src
  }
}

function onScroll() {
  const offset = 120
  let current = ''
  for (const item of toc.value) {
    const el = document.getElementById(item.id)
    if (el && el.getBoundingClientRect().top <= offset) {
      current = item.id
    }
  }
  activeToc.value = current
}

onMounted(() => {
  loadDetail()
  loadComments()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>

<template>
  <div v-if="article" class="detail-wrap">
    <div class="detail-main">
      <el-card class="detail-card" shadow="never">
        <h1 class="detail-title">{{ article.title }}</h1>
        <div class="detail-meta">
          <router-link :to="`/user/${article.author?.id}`" class="author">
            <el-avatar :size="28" :src="avatarSrc(article.author?.avatar, article.author?.nickname || article.author?.username)" />
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
        <article class="markdown-body" v-html="rendered" @click="onBodyClick" />
        <div class="like-bar">
          <el-button
            :type="liked ? 'primary' : 'default'"
            round
            :loading="liking"
            @click="toggleLike"
          >{{ liked ? '已点赞' : '点赞' }} {{ likeCount }}</el-button>
          <el-button
            :type="favorited ? 'warning' : 'default'"
            round
            @click="toggleFavorite"
          >{{ favorited ? '已收藏' : '收藏' }}</el-button>
        </div>
      </el-card>

      <el-card class="comment-card" shadow="never">
        <template #header>评论 ({{ article.commentCount }})</template>
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

    <aside v-if="toc.length > 1" class="toc-side">
      <div class="toc">
        <div class="toc-title">目录</div>
        <a
          v-for="item in toc"
          :key="item.id"
          :class="['toc-item', 'toc-level-' + item.level, { active: activeToc === item.id }]"
          @click.prevent="jumpTo(item.id)"
        >{{ item.text }}</a>
      </div>
    </aside>
  </div>
  <el-empty v-else description="文章不存在或已删除" />

  <Lightbox v-if="lightboxSrc" :src="lightboxSrc" @close="lightboxSrc = ''" />
</template>

<style scoped>
.detail-wrap {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}

.detail-main {
  min-width: 0;
  max-width: 860px;
}

.detail-card {
  border-radius: 8px;
  margin-bottom: 20px;
}

.detail-title {
  font-size: 28px;
  color: var(--text-primary);
  margin-bottom: 14px;
  line-height: 1.35;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-muted);
  font-size: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-color);
  flex-wrap: wrap;
}

.author {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
}

.author:hover {
  color: var(--brand-color);
}

.ops {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.detail-cover {
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  margin: 16px 0 8px;
}

.markdown-body {
  padding: 8px 0;
  line-height: 1.75;
  font-size: 15.5px;
}

.like-bar {
  display: flex;
  justify-content: center;
  gap: 12px;
  padding-top: 20px;
  border-top: 1px solid var(--border-color);
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
  border-top: 1px solid var(--border-color);
}

.toc-side {
  display: none;
}

@media (min-width: 1280px) {
  .detail-wrap {
    grid-template-columns: minmax(0, 1fr) 220px;
  }

  .toc-side {
    display: block;
  }

  .toc {
    position: sticky;
    top: 80px;
    max-height: calc(100vh - 100px);
    overflow-y: auto;
    background: var(--card-bg);
    border: 1px solid var(--border-color);
    border-radius: 8px;
    padding: 14px;
  }

  .toc-title {
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 10px;
    font-size: 14px;
  }

  .toc-item {
    display: block;
    padding: 4px 8px;
    font-size: 13px;
    color: var(--text-secondary);
    border-radius: 4px;
    cursor: pointer;
    line-height: 1.5;
    margin-bottom: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .toc-item:hover {
    color: var(--brand-color);
    background: var(--hover-bg);
  }

  .toc-item.active {
    color: var(--brand-color);
    background: var(--active-bg);
    font-weight: 600;
  }

  .toc-level-2 {
    padding-left: 16px;
  }

  .toc-level-3 {
    padding-left: 28px;
    font-size: 12px;
  }
}
</style>
