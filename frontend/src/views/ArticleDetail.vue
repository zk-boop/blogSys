<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { articleApi, commentApi, likeApi, recommendApi, userApi } from '../api'
import { session } from '../session-instance'
import { useUserStore } from '../stores/user'
import { renderMarkdown, extractToc } from '../utils/markdown'
import { displayAvatar, displayName } from '../utils/person'
import { readingStats } from '../utils/textStats'
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
const recommendations = ref([])
/** 作者卡的数据。取不到就保持 null —— 这张卡不渲染,也不留错误态。 */
const authorProfile = ref(null)
/** 上一篇 / 下一篇。形状由接口定死(`{ prev, next }`,每项可能为 null)。 */
const neighbors = ref({ prev: null, next: null })

const rendered = computed(() => renderMarkdown(article.value?.content))

/**
 * 字数与时长是**近似估算**(口径见 `utils/textStats.js`),用来给读者一个
 * 「现在读还是待会儿读」的判断。正文还没到时是 `{ chars: 0, minutes: 0 }`,
 * 模板据此不显示这一块。
 */
const stats = computed(() => readingStats(article.value?.content))

/** 两侧都没有(或接口失败)时整块不显示 —— 一个空的「上一篇/下一篇」框只是噪音。 */
const hasNeighbors = computed(() => Boolean(neighbors.value.prev || neighbors.value.next))

async function loadDetail() {
  const data = await articleApi.detail(articleId.value)
  article.value = data
  liked.value = data.liked
  likeCount.value = data.likeCount
  favorited.value = data.favorited
  toc.value = extractToc(data.content)
  // 不 await:作者卡是附赠内容,不该拖慢正文出现的时间。作者 id 从正文里拿,
  // 所以它只能在这里发起,而不是 onMounted 里。
  loadAuthor()
}

/**
 * 作者卡。失败(作者被封禁 → 404、网络错误)一律静默:卡片不渲染,正文与评论照旧。
 *
 * <p>已经拿到同一个人的资料就不再取 —— `loadDetail` 在评论提交后也会被调用,
 * 没有这一条等于每发一条评论就重查一次作者。
 */
async function loadAuthor() {
  const id = article.value?.author?.id
  if (!id || authorProfile.value?.id === id) return
  try {
    authorProfile.value = await userApi.profile(id, { silent: true })
  } catch {
    /* 静默降级:这张卡主体内容不依赖,读者也不需要知道它没来 */
    authorProfile.value = null
  }
}

/**
 * 相邻文章。接口失败时整块隐藏 —— 它是导航,不是内容,
 * 没有它读者照样读得完这一篇。
 */
async function loadNeighbors() {
  try {
    const data = await articleApi.neighbors(articleId.value)
    neighbors.value = { prev: data?.prev ?? null, next: data?.next ?? null }
  } catch {
    /* 静默降级 */
    neighbors.value = { prev: null, next: null }
  }
}

async function loadComments() {
  comments.value = await commentApi.list(articleId.value)
}

async function loadRecommendations() {
  recommendations.value = await recommendApi.byArticle(articleId.value)
}

async function toggleLike() {
  if (!session.requireLogin()) return
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
  if (!session.requireLogin()) return
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
  loadRecommendations()
  loadNeighbors()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>

<template>
  <div class="detail-page">
    <template v-if="article">
      <div class="detail-wrap">
        <div class="detail-main">
      <el-card class="detail-card" shadow="never">
        <h1 class="detail-title">{{ article.title }}</h1>
        <div class="detail-meta">
          <router-link :to="`/user/${article.author?.id}`" class="author">
            <el-avatar :size="28" :src="displayAvatar(article.author)" />
            {{ displayName(article.author) }}
          </router-link>
          <span>{{ article.createdAt?.slice(0, 10) }}</span>
          <span>浏览 {{ article.viewCount }}</span>
          <!-- 估算口径见 utils/textStats.js。chars 为 0(正文没拿到/全空白)时整块不显示。 -->
          <span v-if="stats.chars">{{ stats.chars }} 字 · 约 {{ stats.minutes }} 分钟</span>
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
        <!--
          作者卡:整卡可点进博主主页。`authorProfile` 取不到(404 / 网络错误 / 还没回来)
          时整张卡不存在 —— 不占位、不报错,因为正文不依赖它。
        -->
        <router-link v-if="authorProfile" :to="`/user/${authorProfile.id}`" class="author-card">
          <el-avatar :size="44" :src="displayAvatar(authorProfile)" />
          <div class="author-card-who">
            <div class="author-card-name">{{ displayName(authorProfile) }}</div>
            <div class="author-card-username">@{{ authorProfile.username }}</div>
          </div>
          <div class="author-card-stats">
            <span>文章 {{ authorProfile.articleCount ?? 0 }}</span>
            <span>注册于 {{ authorProfile.createdAt?.slice(0, 10) }}</span>
          </div>
        </router-link>
        <img v-if="article.cover" :src="article.cover" class="detail-cover" alt="cover" />
        <p v-if="article.summary" class="detail-summary">{{ article.summary }}</p>
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
        <!--
          上一篇 / 下一篇:更早发布的在左,更晚发布的在右。某一侧为 null 就只渲染另一侧,
          两侧都没有(含接口失败)时整块不出现。
        -->
        <div v-if="hasNeighbors" class="neighbor-bar">
          <router-link
            v-if="neighbors.prev"
            :to="`/article/${neighbors.prev.id}`"
            class="neighbor neighbor-prev"
          >
            <span class="neighbor-label">← 上一篇</span>
            <span class="neighbor-title">{{ neighbors.prev.title }}</span>
          </router-link>
          <router-link
            v-if="neighbors.next"
            :to="`/article/${neighbors.next.id}`"
            class="neighbor neighbor-next"
          >
            <span class="neighbor-label">下一篇 →</span>
            <span class="neighbor-title">{{ neighbors.next.title }}</span>
          </router-link>
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

      <el-card v-if="recommendations.length" class="recommend-card" shadow="never">
        <template #header>相关推荐</template>
        <div class="recommend-list">
          <router-link
            v-for="item in recommendations"
            :key="item.id"
            :to="`/article/${item.id}`"
            class="recommend-item"
          >
            <!-- 缩略图优先,缺失时退回原图:这块是附赠内容,宁可放大图也不要留一个空洞 -->
            <img
              v-if="item.coverThumb || item.cover"
              :src="item.coverThumb || item.cover"
              class="recommend-cover"
              alt="cover"
            />
            <div class="recommend-body">
              <div class="recommend-head">
                <div class="recommend-title">{{ item.title }}</div>
                <!-- 相关度是排序依据的数字,弱化成小标;字段缺失(或不是数字)就不渲染 -->
                <span v-if="typeof item.recommendScore === 'number'" class="recommend-score">
                  相关度 {{ Math.round(item.recommendScore) }}
                </span>
              </div>
              <p v-if="item.summary" class="recommend-summary">{{ item.summary }}</p>
              <div class="recommend-meta">
                <span>{{ displayName(item.author) }}</span>
                <el-tag v-for="tag in item.tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
                <span>浏览 {{ item.viewCount }}</span>
              </div>
            </div>
          </router-link>
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
    </template>
    <el-empty v-else description="文章不存在或已删除" />

    <Lightbox v-if="lightboxSrc" :src="lightboxSrc" @close="lightboxSrc = ''" />
  </div>
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

/* 作者卡:横条,右侧是「文章数 / 注册时间」。弱边框 + 悬停高亮,提示整卡可点。 */
.author-card {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-secondary);
  transition: border-color 0.2s, background 0.2s;
}

.author-card:hover {
  border-color: var(--brand-color);
  background: var(--hover-bg);
}

.author-card-who {
  min-width: 0;
}

.author-card-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.author-card-username {
  font-size: 12.5px;
  color: var(--text-muted);
}

.author-card-stats {
  margin-left: auto;
  display: flex;
  gap: 16px;
  font-size: 12.5px;
  color: var(--text-muted);
  white-space: nowrap;
}

/* 摘要:正文之前的「先看这一段」。两行截断,弱化,不抢标题。 */
.detail-summary {
  margin: 12px 0 4px;
  padding-left: 10px;
  border-left: 3px solid var(--border-color);
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
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
  margin-top: 24px;
}

/* 上一篇 / 下一篇:一行两个,`next` 靠右,只有一侧时它自然占满。 */
.neighbor-bar {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
}

.neighbor {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  transition: border-color 0.2s, background 0.2s;
}

.neighbor:hover {
  border-color: var(--brand-color);
  background: var(--hover-bg);
}

.neighbor-next {
  text-align: right;
}

.neighbor-label {
  font-size: 12px;
  color: var(--text-muted);
}

.neighbor-title {
  font-size: 14px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.neighbor:hover .neighbor-title {
  color: var(--brand-color);
}

.recommend-card {
  margin-top: 16px;
  border-radius: 0;
}

.recommend-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  transition: background 0.2s;
}

.recommend-item:hover {
  background: rgba(64, 158, 255, 0.06);
}

.recommend-cover {
  flex-shrink: 0;
  width: 120px;
  height: 68px;
  object-fit: cover;
  border-radius: 6px;
}

.recommend-body {
  flex: 1;
  min-width: 0;
}

.recommend-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.recommend-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 相关度:排序依据的暴露,不是重点 —— 所以小、灰、不加底色。 */
.recommend-score {
  flex-shrink: 0;
  margin-left: auto;
  font-size: 11.5px;
  color: var(--text-muted);
}

.recommend-summary {
  margin-top: 4px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.recommend-item:hover .recommend-title {
  color: var(--brand-color);
}

.recommend-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-muted);
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

/* 窄屏:作者卡的两行信息与推荐项都改成竖排,免得把正文挤成一条缝。 */
@media (max-width: 640px) {
  .author-card {
    flex-wrap: wrap;
  }

  .author-card-stats {
    margin-left: 0;
    width: 100%;
  }

  .recommend-cover {
    width: 84px;
    height: 56px;
  }
}
</style>
