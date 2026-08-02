<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from './stores/user'

const store = useUserStore()
const router = useRouter()
const keyword = ref('')

function search() {
  router.push({ path: '/', query: keyword.value ? { keyword: keyword.value } : {} })
}

async function logout() {
  await ElMessageBox.confirm('确定退出登录吗?', '提示', { type: 'warning' })
  store.logout()
  router.push('/')
}
</script>

<template>
  <header class="app-header">
    <div class="app-header-inner">
      <router-link to="/" class="logo">blogSys</router-link>
      <nav class="nav">
        <router-link to="/">首页</router-link>
        <router-link v-if="store.isLoggedIn" to="/write">写文章</router-link>
      </nav>
      <div class="search-box">
        <el-input
          v-model="keyword"
          placeholder="搜索文章…"
          clearable
          @keyup.enter="search"
        >
          <template #append>
            <el-button @click="search">搜索</el-button>
          </template>
        </el-input>
      </div>
      <div class="user-area">
        <template v-if="store.isLoggedIn">
          <el-dropdown>
            <span class="user-info">
              <el-avatar :size="28" :src="store.user?.avatar" />
              <span class="nickname">{{ store.user?.nickname }}</span>
              <el-tag v-if="store.isAdmin" size="small" type="danger" effect="plain">管理员</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/me')">个人中心</el-dropdown-item>
                <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <el-button link type="primary" @click="router.push('/login')">登录</el-button>
          <el-button type="primary" plain @click="router.push('/register')">注册</el-button>
        </template>
      </div>
    </div>
  </header>

  <main class="app-main">
    <router-view />
  </main>
</template>
