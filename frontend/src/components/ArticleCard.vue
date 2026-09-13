<script setup>
import { avatarSrc } from '../utils/avatar'
import { formatCount } from '../utils/format'

defineProps({
  article: { type: Object, required: true },
})
</script>

<template>
  <el-card class="article-card" shadow="hover">
    <div class="card-body">
      <div class="card-main">
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
        <p class="summary">{{ article.summary || '暂无摘要' }}</p>
        <div class="meta">
          <router-link :to="`/user/${article.author?.id}`" class="author">
            <el-avatar :size="22" :src="avatarSrc(article.author?.avatar, article.author?.nickname || article.author?.username)" />
            {{ article.author?.nickname || article.author?.username }}
          </router-link>
          <span class="date">{{ article.createdAt?.slice(0, 10) }}</span>
          <span class="stat">浏览 {{ formatCount(article.viewCount) }}</span>
          <span class="stat">赞 {{ formatCount(article.likeCount) }}</span>
          <span class="stat">评论 {{ formatCount(article.commentCount) }}</span>
        </div>
      </div>
      <router-link v-if="article.cover" :to="`/article/${article.id}`" class="cover-link">
        <!--
          直接用它,不再 `coverThumb || cover`:那张回退规则属于投影模块
          (找不到缩略图时它会返回原图 —— webp 与非 /uploads 路径就是这种情况),
          前端再实现一遍等于给同一个决定留了两个主人。
          这个 `|| cover` 当初还顺手掩盖了推荐接口漏掉 coverThumb 这件事。
        -->
        <img :src="article.coverThumb" class="cover" alt="cover" />
      </router-link>
    </div>
  </el-card>
</template>

<style scoped>
.article-card {
  margin-bottom: 16px;
  border-radius: 0;
}

.card-body {
  display: flex;
  gap: 20px;
  align-items: stretch;
}

.card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.title:hover {
  color: var(--brand-color);
}

.tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-left: auto;
}

.summary {
  color: var(--text-secondary);
  line-height: 1.6;
  margin-top: 10px;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--text-muted);
}

.author {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.author:hover {
  color: var(--brand-color);
}

.cover-link {
  flex-shrink: 0;
  width: 260px;
  align-self: stretch;
  display: block;
}

.cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

@media (max-width: 640px) {
  .cover-link {
    width: 140px;
  }

  .title {
    white-space: normal;
  }
}
</style>
