<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { userApi } from '../api'
import ArticleCard from '../components/ArticleCard.vue'
import { avatarSrc } from '../utils/avatar'

const route = useRoute()
const userId = () => Number(route.params.id)
const invalidId = () => !/^\d+$/.test(route.params.id)

const user = ref(null)
const articles = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

async function loadProfile() {
  user.value = await userApi.profile(userId())
}

async function loadArticles() {
  loading.value = true
  try {
    const data = await userApi.articles(userId(), { page: page.value, size: size.value })
    articles.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!invalidId()) {
    loadProfile()
    loadArticles()
  }
})
</script>

<template>
  <div>
    <el-empty v-if="invalidId()" description="用户不存在或已注销" />
    <el-card v-else-if="user" class="profile-head" shadow="never">
      <el-avatar :size="80" :src="avatarSrc(user.avatar, user.nickname || user.username)" />
      <div class="info">
        <h2 class="nickname">{{ user.nickname }}</h2>
        <div class="username">@{{ user.username }}</div>
        <div class="stats">
          <span>文章 {{ user.articleCount }}</span>
          <span>注册于 {{ user.createdAt?.slice(0, 10) }}</span>
        </div>
      </div>
    </el-card>
    <el-empty v-else description="用户不存在或已注销" />

    <h3 class="section-title">TA 的文章</h3>
    <div v-loading="loading">
      <template v-if="articles.length">
        <ArticleCard v-for="article in articles" :key="article.id" :article="article" />
      </template>
      <el-empty v-else description="还没有发布文章" />
    </div>
    <div class="pagination">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        background
        @current-change="loadArticles"
      />
    </div>
  </div>
</template>

<style scoped>
.profile-head {
  border-radius: 8px;
  margin-bottom: 20px;
  display: flex;
  gap: 20px;
  align-items: center;
}

.nickname {
  font-size: 20px;
}

.username {
  color: #909399;
  margin: 4px 0;
}

.stats {
  display: flex;
  gap: 20px;
  color: #606266;
  font-size: 13px;
}

.section-title {
  margin-bottom: 16px;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
