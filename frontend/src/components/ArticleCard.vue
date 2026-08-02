<script setup>
defineProps({
  article: { type: Object, required: true },
})
</script>

<template>
  <el-card class="article-card" shadow="hover">
    <template #header>
      <div class="card-header">
        <router-link :to="`/article/${article.id}`" class="title">
          {{ article.title }}
          <el-tag v-if="article.status === 0" size="small" type="warning">草稿</el-tag>
        </router-link>
        <div class="tags">
          <el-tag v-for="tag in article.tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
        </div>
        <slot name="extra" />
      </div>
    </template>
    <div class="card-body">
      <p class="summary">{{ article.summary || '暂无摘要' }}</p>
      <img v-if="article.cover" :src="article.coverThumb || article.cover" class="cover" alt="cover" />
    </div>
    <div class="meta">
      <router-link :to="`/user/${article.author?.id}`" class="author">
        <el-avatar :size="22" :src="article.author?.avatar" />
        {{ article.author?.nickname || article.author?.username }}
      </router-link>
      <span class="date">{{ article.createdAt?.slice(0, 10) }}</span>
      <span class="stat">浏览 {{ article.viewCount }}</span>
      <span class="stat">赞 {{ article.likeCount }}</span>
      <span class="stat">评论 {{ article.commentCount }}</span>
    </div>
  </el-card>
</template>

<style scoped>
.article-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.title {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}

.title:hover {
  color: #409eff;
}

.tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.card-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.summary {
  color: #606266;
  line-height: 1.6;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.cover {
  width: 160px;
  height: 90px;
  object-fit: cover;
  border-radius: 6px;
  flex-shrink: 0;
}

.meta {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
  font-size: 13px;
  color: #909399;
}

.author {
  display: flex;
  align-items: center;
  gap: 6px;
}

.author:hover {
  color: #409eff;
}
</style>
