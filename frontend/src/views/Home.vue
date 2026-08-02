<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { articleApi, tagApi } from '../api'
import ArticleCard from '../components/ArticleCard.vue'
import { formatCount } from '../utils/format'

const route = useRoute()
const router = useRouter()

const articles = ref([])
const tags = ref([])
const hotArticles = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const activeTag = ref(null)
const loading = ref(true)

const keyword = ref(route.query.keyword || '')

watch(
  () => route.query.keyword,
  (value) => {
    keyword.value = value || ''
    page.value = 1
    loadArticles()
  }
)

const maxTagCount = computed(() => {
  if (!tags.value.length) return 1
  return Math.max(...tags.value.map((t) => t.count))
})

async function loadTags() {
  tags.value = await tagApi.list()
}

async function loadHot() {
  hotArticles.value = await articleApi.hot()
}

async function loadArticles() {
  loading.value = true
  try {
    const data = await articleApi.page({
      page: page.value,
      size: size.value,
      tagId: activeTag.value || undefined,
      keyword: keyword.value || undefined,
    })
    articles.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

watch([page, activeTag], loadArticles)

function selectTag(tagId) {
  activeTag.value = tagId
  page.value = 1
}

function clearKeyword() {
  router.push({ path: '/', query: {} })
}

onMounted(() => {
  loadTags()
  loadHot()
  loadArticles()
})
</script>

<template>
  <div class="home-grid">
    <div class="main-col">
      <div v-if="keyword" class="search-bar">
        <el-tag closable type="primary" @close="clearKeyword">搜索:{{ keyword }}</el-tag>
        <span class="search-hint">共 {{ total }} 条结果</span>
      </div>

      <div class="tag-bar">
        <el-tag
          :type="activeTag === null ? 'primary' : 'info'"
          class="tag-item"
          @click="selectTag(null)"
        >全部</el-tag>
        <el-tag
          v-for="tag in tags"
          :key="tag.id"
          :type="activeTag === tag.id ? 'primary' : 'info'"
          class="tag-item"
          @click="selectTag(tag.id)"
        >{{ tag.name }}</el-tag>
      </div>

      <div v-loading="loading">
        <template v-if="articles.length">
          <ArticleCard v-for="article in articles" :key="article.id" :article="article" />
        </template>
        <el-skeleton v-else-if="loading" animated :rows="5" style="padding: 12px" />
        <el-empty v-else :description="keyword ? '没有找到相关文章' : '还没有文章,快来写第一篇吧'" />
      </div>

      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next, total"
          background
        />
      </div>
    </div>

    <aside class="side-col">
      <el-card class="side-card" shadow="never">
        <template #header>热门文章</template>
        <div v-if="hotArticles.length" class="hot-list">
          <router-link
            v-for="(article, index) in hotArticles"
            :key="article.id"
            :to="`/article/${article.id}`"
            class="hot-item"
          >
            <span :class="['hot-rank', { top: index < 3 }]">{{ index + 1 }}</span>
            <span class="hot-title">{{ article.title }}</span>
            <span class="hot-views">{{ formatCount(article.viewCount) }}</span>
          </router-link>
        </div>
        <el-empty v-else description="暂无热门文章" :image-size="60" />
      </el-card>

      <el-card class="side-card" shadow="never">
        <template #header>标签云</template>
        <div v-if="tags.length" class="tag-cloud">
          <a
            v-for="tag in tags"
            :key="tag.id"
            class="cloud-tag"
            :class="{ active: activeTag === tag.id }"
            :style="{ fontSize: (12 + (tag.count / maxTagCount) * 8) + 'px' }"
            @click.prevent="selectTag(tag.id)"
          >{{ tag.name }}<em>{{ tag.count }}</em></a>
        </div>
        <el-empty v-else description="暂无标签" :image-size="60" />
      </el-card>
    </aside>
  </div>
</template>

<style scoped>
.home-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.main-col {
  min-width: 0;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.search-hint {
  color: var(--text-muted);
  font-size: 13px;
}

.tag-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.tag-item {
  cursor: pointer;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.side-col {
  display: none;
}

@media (min-width: 1024px) {
  .home-grid {
    grid-template-columns: minmax(0, 1fr) 300px;
  }

  .side-col {
    display: flex;
    flex-direction: column;
    gap: 16px;
    position: sticky;
    top: 80px;
  }
}

.side-card {
  border-radius: 8px;
}

.hot-list {
  display: flex;
  flex-direction: column;
}

.hot-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  border-bottom: 1px dashed var(--border-color);
}

.hot-item:last-child {
  border-bottom: none;
}

.hot-item:hover .hot-title {
  color: var(--brand-color);
}

.hot-rank {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  background: #f0f2f5;
  color: var(--text-secondary);
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}

.hot-rank.top {
  background: var(--brand-color);
  color: #fff;
}

.hot-title {
  flex: 1;
  font-size: 13.5px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hot-views {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-muted);
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  line-height: 1.4;
}

.cloud-tag {
  color: var(--text-secondary);
  padding: 2px 6px;
  border-radius: 4px;
}

.cloud-tag:hover {
  color: var(--brand-color);
  background: #f2f6ff;
}

.cloud-tag.active {
  color: var(--brand-color);
  background: #f2f6ff;
  font-weight: 600;
}

.cloud-tag em {
  font-style: normal;
  font-size: 11px;
  color: var(--text-muted);
  margin-left: 2px;
}
</style>
