<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { userApi } from '../api'
import ArticleCard from '../components/ArticleCard.vue'
import ListPager from '../components/ListPager.vue'
import { useList } from '../useList'
import { displayAvatar } from '../utils/person'

const route = useRoute()
const userId = () => Number(route.params.id)
const invalidId = () => !/^\d+$/.test(route.params.id)

const user = ref(null)
/** 资料请求失败与「这个人不存在」是两件事,此前都渲染成同一句「用户不存在或已注销」。 */
const profileFailure = ref(null)

const {
  records: articles,
  total,
  page,
  size,
  loading,
  failure,
  phase,
  load: loadArticles,
  goTo,
} = useList(({ page: current, size: pageSize }) =>
  userApi.articles(userId(), { page: current, size: pageSize }))

async function loadProfile() {
  profileFailure.value = null
  try {
    user.value = await userApi.profile(userId())
  } catch (error) {
    user.value = null
    profileFailure.value = error?.response?.data?.message || error?.message || '资料加载失败'
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
      <el-avatar :size="80" :src="displayAvatar(user)" />
      <div class="info">
        <h2 class="nickname">{{ user.nickname }}</h2>
        <div class="username">@{{ user.username }}</div>
        <div class="stats">
          <span>文章 {{ user.articleCount }}</span>
          <span>注册于 {{ user.createdAt?.slice(0, 10) }}</span>
        </div>
      </div>
    </el-card>
    <el-empty v-else-if="profileFailure" :description="profileFailure">
      <el-button type="primary" @click="loadProfile">重试</el-button>
    </el-empty>
    <el-empty v-else description="用户不存在或已注销" />

    <h3 class="section-title">TA 的文章</h3>
    <div v-loading="loading">
      <template v-if="articles.length">
        <ArticleCard v-for="article in articles" :key="article.id" :article="article" />
      </template>
      <el-empty v-else-if="phase === 'failed'" :description="failure">
        <el-button type="primary" @click="loadArticles">重试</el-button>
      </el-empty>
      <el-empty v-else description="还没有发布文章" />
    </div>
    <ListPager :page="page" :size="size" :total="total" @change="goTo" />
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
  color: var(--text-muted);
  margin: 4px 0;
}

.stats {
  display: flex;
  gap: 20px;
  color: var(--text-secondary);
  font-size: 13px;
}

.section-title {
  margin-bottom: 16px;
}

/* 分页样式归 ListPager */
</style>
