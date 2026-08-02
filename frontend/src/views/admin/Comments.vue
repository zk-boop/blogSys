<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'

const router = useRouter()
const comments = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)

async function loadComments() {
  loading.value = true
  try {
    const data = await adminApi.comments({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
    })
    comments.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  loadComments()
}

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

    <el-table v-loading="loading" :data="comments" size="small">
      <el-table-column label="评论内容" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ row.content }}</template>
      </el-table-column>
      <el-table-column label="作者" width="120">
        <template #default="{ row }">{{ row.user?.nickname || row.user?.username }}</template>
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

    <div class="pagination">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        background
        @current-change="loadComments"
      />
    </div>
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

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
