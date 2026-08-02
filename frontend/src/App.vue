<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from './stores/user'
import { avatarSrc } from './utils/avatar'
import { applyTheme, getTheme } from './utils/theme'
import { onModuleLoadError } from './router'

const store = useUserStore()
const router = useRouter()
const keyword = ref('')
const progress = ref(0)
const theme = ref(getTheme())
const moduleError = ref(false)

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  applyTheme(theme.value)
}

function search() {
  router.push({ path: '/', query: keyword.value ? { keyword: keyword.value } : {} })
}

async function logout() {
  await ElMessageBox.confirm('确定退出登录吗?', '提示', { type: 'warning' })
  store.logout()
  router.push('/')
}

function onScroll() {
  const total = document.documentElement.scrollHeight - window.innerHeight
  progress.value = total > 0 ? Math.min(100, (window.scrollY / total) * 100) : 0
}

function reload() {
  window.location.reload()
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  onModuleLoadError(() => {
    moduleError.value = true
  })
})

onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>

<template>
  <div class="reading-progress" :style="{ width: progress + '%' }" />

  <header class="app-header">
    <div class="app-header-inner">
      <router-link to="/" class="logo">blogSys<em>分享与记录</em></router-link>
      <nav class="nav">
        <router-link to="/">首页</router-link>
        <router-link v-if="store.isLoggedIn" to="/write">写文章</router-link>
        <router-link v-if="store.isAdmin" to="/admin">管理后台</router-link>
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
        <el-tooltip :content="theme === 'dark' ? '切换到浅色' : '切换到深色'">
          <el-button class="theme-btn" circle text @click="toggleTheme">
            {{ theme === 'dark' ? '☀' : '☾' }}
          </el-button>
        </el-tooltip>
        <template v-if="store.isLoggedIn">
          <el-dropdown>
            <span class="user-info">
              <el-avatar :size="28" :src="avatarSrc(store.user?.avatar, store.user?.nickname || store.user?.username)" />
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
    <div v-if="moduleError" class="load-error">
      <el-result icon="warning" title="页面加载失败" sub-title="与开发服务器的连接已断开,请刷新页面重试">
        <template #extra>
          <el-button type="primary" @click="reload">刷新页面</el-button>
        </template>
      </el-result>
    </div>
    <router-view v-else v-slot="{ Component }">
      <transition name="fade-slide" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </main>

  <footer class="app-footer">
    © 2026 blogSys · Spring Boot + Vue 3 ·
    <router-link to="/" class="footer-link">首页</router-link>
  </footer>

  <el-backtop :right="28" :bottom="28" />
</template>
