<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div class="admin-layout">
    <el-card class="admin-nav" shadow="never">
      <div class="admin-title">管理后台</div>
      <el-menu
        :default-active="route.path"
        router
        class="admin-menu"
      >
        <el-menu-item index="/admin">
          <span>数据仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/admin/users">
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/articles">
          <span>文章管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/comments">
          <span>评论管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/tags">
          <span>标签管理</span>
        </el-menu-item>
      </el-menu>
    </el-card>
    <div class="admin-content">
      <router-view />
    </div>
  </div>
</template>

<style scoped>
.admin-layout {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.admin-nav {
  border-radius: 8px;
}

.admin-title {
  font-size: 16px;
  font-weight: 700;
  padding: 4px 8px 12px;
  color: var(--text-primary);
}

.admin-menu {
  border-right: none;
}

.admin-menu :deep(.el-menu-item) {
  height: 42px;
}

.admin-content {
  min-width: 0;
}

@media (max-width: 768px) {
  .admin-layout {
    grid-template-columns: 1fr;
  }
}
</style>
