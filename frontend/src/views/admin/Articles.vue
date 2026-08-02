<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'
import { formatCount } from '../../utils/format'

const router = useRouter()
const articles = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const statusFilter = ref('')
const loading = ref(false)

async function loadArticles() {
  loading.value = true
  try {
    const data = await adminApi.articles({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
    })
    articles.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  loadArticles()
}

async function removeArticle(row) {
  await ElMessageBox.confirm(`确定删除文章「${row.title}」吗?此操作不可恢复。`, '删除文章', { type: 'warning' })
  await adminApi.removeArticle(row.id)
  ElMessage.success('已删除')
  loadArticles()
}

onMounted(loadArticles)
</script>

<template>
  <el-card shadow="never" class="page-card">
    <template #header>
      <div class="page-header">
        <span>文章管理</span>
        <div class="filters">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="search">
            <el-option label="已发布" :value="1" />
            <el-option label="草稿" :value="0" />
          </el-select>
          <el-input
            v-model="keyword"
            placeholder="搜索标题/内容"
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
      </div>
    </template>

    <el-table v-loading="loading" :data="articles" size="small">
      <el-table-column label="标题" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <a class="title-link" @click="router.push(`/article/${row.id}`)">{{ row.title }}</a>
        </template>
      </el-table-column>
      <el-table-column label="作者" width="120">
        <template #default="{ row }">{{ row.author?.nickname || row.author?.username }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'warning' : 'success'" size="small">
            {{ row.status === 0 ? '草稿' : '已发布' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="浏览" width="70">
        <template #default="{ row }">{{ formatCount(row.viewCount) }}</template>
      </el-table-column>
      <el-table-column label="赞" width="60">
        <template #default="{ row }">{{ formatCount(row.likeCount) }}</template>
      </el-table-column>
      <el-table-column label="评论" width="70">
        <template #default="{ row }">{{ formatCount(row.commentCount) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="发布时间" width="110">
        <template #default="{ row }">{{ row.createdAt?.slice(0, 10) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button type="danger" link @click="removeArticle(row)">删除</el-button>
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
        @current-change="loadArticles"
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
  flex-wrap: wrap;
}

.filters {
  display: flex;
  gap: 10px;
}

.search-input {
  width: 240px;
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
