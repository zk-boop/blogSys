<script setup>
import { onMounted, ref, watch } from 'vue'
import { articleApi, tagApi } from '../api'
import ArticleCard from '../components/ArticleCard.vue'

const articles = ref([])
const tags = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const activeTag = ref(null)
const loading = ref(false)

async function loadTags() {
  tags.value = await tagApi.list()
}

async function loadArticles() {
  loading.value = true
  try {
    const data = await articleApi.page({ page: page.value, size: size.value, tagId: activeTag.value || undefined })
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

onMounted(() => {
  loadTags()
  loadArticles()
})
</script>

<template>
  <div>
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
      <el-empty v-else description="还没有文章,快来写第一篇吧" />
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
