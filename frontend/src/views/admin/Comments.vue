<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'
import ListPager from '../../components/ListPager.vue'
import { useList } from '../../useList'
import { displayName } from '../../utils/person'

const router = useRouter()

const {
  records: comments,
  total,
  page,
  size,
  keyword,
  loading,
  failure,
  phase,
  load: loadComments,
  search,
  goTo,
} = useList(({ page: current, size: pageSize, keyword: kw }) =>
  adminApi.comments({ page: current, size: pageSize, keyword: kw || undefined }))

async function removeComment(row) {
  await ElMessageBox.confirm('确定删除这条评论吗?', '删除评论', { type: 'warning' })
  await adminApi.removeComment(row.id)
  ElMessage.success('已删除')
  loadComments()
}

onMounted(loadComments)
</script>

<template>
  <el-card shadow="never" class="page-card">
    <template #header>
      <div class="page-header">
        <span>评论管理</span>
        <el-input
          v-model="keyword"
          placeholder="搜索评论内容"
          clearable
          class="search-input"
          @keyup.enter="search"
          @clear="search"
        >
          <template #append>
            <el-button @click="search">搜索</el-button>
          </template>
        </el-input>
      </div>
    </template>

    <el-alert
      v-if="phase === 'failed'"
      type="error"
      :title="failure"
      :closable="false"
      show-icon
      class="load-failure"
    >
      <el-button link type="primary" @click="loadComments">重试</el-button>
    </el-alert>
    <el-table v-loading="loading" :data="comments" size="small">
      <el-table-column label="评论内容" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ row.content }}</template>
      </el-table-column>
      <el-table-column label="作者" width="120">
        <template #default="{ row }">{{ displayName(row.user) }}</template>
      </el-table-column>
      <el-table-column label="所属文章" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <a class="title-link" @click="router.push(`/article/${row.articleId}`)">{{ row.articleTitle }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="评论时间" width="110">
        <template #default="{ row }">{{ row.createdAt?.slice(0, 10) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button type="danger" link @click="removeComment(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPager :page="page" :size="size" :total="total" align="end" @change="goTo" />
  </el-card>
</template>

<style scoped>
.page-card {
  border-radius: 8px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.search-input {
  width: 260px;
}

.title-link {
  color: var(--text-primary);
}

.title-link:hover {
  color: var(--brand-color);
}

/* 分页样式归 ListPager —— 它按 align 保留各页原有的对齐方式 */
.load-failure {
  margin-bottom: 12px;
}
</style>
