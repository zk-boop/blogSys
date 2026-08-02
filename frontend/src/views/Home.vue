<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { articleApi, tagApi } from '../api'
import ArticleCard from '../components/ArticleCard.vue'

const route = useRoute()
const router = useRouter()

const articles = ref([])
const tags = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const activeTag = ref(null)
const loading = ref(false)

const keyword = ref(route.query.keyword || '')

watch(
  () => route.query.keyword,
  (value) => {
    keyword.value = value || ''
    page.value = 1
    loadArticles()
  }
)

async function loadTags() {
  tags.value = await tagApi.list()
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
  loadArticles()
})
</script>

<template>
  <div>
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
</template>

<style scoped>
.search-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.search-hint {
  color: #909399;
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
</style>
