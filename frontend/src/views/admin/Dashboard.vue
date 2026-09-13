<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../../api'
import { formatCount } from '../../utils/format'
import { displayName } from '../../utils/person'

const stats = ref(null)
const loading = ref(true)

onMounted(async () => {
  try {
    stats.value = await adminApi.stats()
  } finally {
    loading.value = false
  }
})

const cards = [
  { key: 'userCount', label: '用户总数', color: '#8a5a2b' },
  { key: 'articleCount', label: '文章总数', color: '#a04d2c' },
  { key: 'commentCount', label: '评论总数', color: '#7a6b3f' },
  { key: 'tagCount', label: '标签总数', color: '#6b4423' },
]
</script>

<template>
  <div v-loading="loading">
    <div class="stat-cards">
      <el-card v-for="card in cards" :key="card.key" class="stat-card" shadow="never">
        <div class="stat-value" :style="{ color: card.color }">
          {{ stats ? formatCount(stats[card.key]) : '—' }}
        </div>
        <div class="stat-label">{{ card.label }}</div>
      </el-card>
    </div>

    <div class="today-row">
      <el-card class="today-card" shadow="never">
        <div class="today-label">今日新增用户</div>
        <div class="today-value">{{ stats?.todayUsers ?? '—' }}</div>
      </el-card>
      <el-card class="today-card" shadow="never">
        <div class="today-label">今日新增文章</div>
        <div class="today-value">{{ stats?.todayArticles ?? '—' }}</div>
      </el-card>
    </div>

    <el-card class="hot-card" shadow="never">
      <template #header>热门文章 Top 5</template>
      <el-table :data="stats?.hotArticles || []" size="small">
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
        <el-table-column label="作者" width="120">
          <template #default="{ row }">{{ displayName(row.author) }}</template>
        </el-table-column>
        <el-table-column prop="viewCount" label="浏览" width="90" />
        <el-table-column prop="likeCount" label="赞" width="80" />
        <el-table-column prop="commentCount" label="评论" width="80" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  border-radius: 8px;
  text-align: center;
}

.stat-value {
  font-size: 30px;
  font-weight: 700;
  font-family: Georgia, serif;
}

.stat-label {
  color: var(--text-muted);
  font-size: 13px;
  margin-top: 4px;
}

.today-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin: 16px 0;
}

.today-card {
  border-radius: 8px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 4px 8px;
}

.today-label {
  color: var(--text-secondary);
  font-size: 14px;
}

.today-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--brand-color);
  font-family: Georgia, serif;
}

.hot-card {
  border-radius: 8px;
}

@media (max-width: 768px) {
  .stat-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
