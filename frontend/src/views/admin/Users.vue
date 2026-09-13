<script setup>
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'
import { avatarSrc } from '../../utils/avatar'
import ListPager from '../../components/ListPager.vue'
import { useList } from '../../useList'

const {
  records: users,
  total,
  page,
  size,
  keyword,
  loading,
  failure,
  phase,
  load: loadUsers,
  search,
  goTo,
} = useList(({ page: current, size: pageSize, keyword: kw }) =>
  adminApi.users({ page: current, size: pageSize, keyword: kw || undefined }))

async function toggleBan(user) {
  const action = user.status === 1 ? '解封' : '封禁'
  await ElMessageBox.confirm(`确定${action}用户「${user.nickname || user.username}」吗?`, '提示', { type: 'warning' })
  await adminApi.updateUserStatus(user.id, user.status === 1 ? 0 : 1)
  ElMessage.success(`已${action}`)
  loadUsers()
}

async function toggleRole(user) {
  const nextRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN'
  const action = nextRole === 'ADMIN' ? '设为管理员' : '取消管理员'
  await ElMessageBox.confirm(`确定将「${user.nickname || user.username}」${action}吗?`, '提示', { type: 'warning' })
  await adminApi.updateUserRole(user.id, nextRole)
  ElMessage.success(`已${action}`)
  loadUsers()
}

onMounted(loadUsers)
</script>

<template>
  <el-card shadow="never" class="page-card">
    <template #header>
      <div class="page-header">
        <span>用户管理</span>
        <el-input
          v-model="keyword"
          placeholder="搜索用户名/昵称"
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
      <el-button link type="primary" @click="loadUsers">重试</el-button>
    </el-alert>
    <el-table v-loading="loading" :data="users" size="small">
      <el-table-column label="用户" min-width="180">
        <template #default="{ row }">
          <div class="user-cell">
            <el-avatar :size="28" :src="avatarSrc(row.avatar, row.nickname || row.username)" />
            <div>
              <div class="user-name">{{ row.nickname || row.username }}</div>
              <div class="user-sub">@{{ row.username }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="110">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small" effect="plain">
            {{ row.role === 'ADMIN' ? '管理员' : '用户' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'danger' : 'success'" size="small">
            {{ row.status === 1 ? '已封禁' : '正常' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="articleCount" label="文章数" width="80" />
      <el-table-column prop="createdAt" label="注册时间" width="110">
        <template #default="{ row }">{{ row.createdAt?.slice(0, 10) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button
            v-if="row.role !== 'ADMIN'"
            link
            :type="row.status === 1 ? 'success' : 'danger'"
            @click="toggleBan(row)"
          >{{ row.status === 1 ? '解封' : '封禁' }}</el-button>
          <el-button
            link
            type="primary"
            @click="toggleRole(row)"
          >{{ row.role === 'ADMIN' ? '取消管理员' : '设为管理员' }}</el-button>
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

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-name {
  font-weight: 600;
}

.user-sub {
  font-size: 12px;
  color: var(--text-muted);
}

/* 分页样式归 ListPager —— 它按 align 保留各页原有的对齐方式 */
.load-failure {
  margin-bottom: 12px;
}
</style>
