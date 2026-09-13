<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { articleApi, authApi } from '../api'
import { useUserStore } from '../stores/user'
import ArticleCard from '../components/ArticleCard.vue'
import ImageCropUpload from '../components/ImageCropUpload.vue'
import ListPager from '../components/ListPager.vue'
import { useList } from '../useList'
import { avatarSrc } from '../utils/avatar'

const store = useUserStore()

const profileForm = reactive({
  nickname: store.user?.nickname || '',
  avatar: store.user?.avatar || '',
})

const savingProfile = ref(false)
const activeTab = ref('articles')

// 两个 tab 共用同一块分页:取数函数按当前 tab 分派,模块只管状态。
// 此前是两个 load 函数各自复制一遍「取数 → 赋值 records/total」,外加一处三元表达式分派。
const {
  records: myArticles,
  total,
  page,
  size,
  loading,
  failure,
  phase,
  load: loadMyArticles,
  search,
  goTo,
} = useList(({ page: current, size: pageSize }) =>
  activeTab.value === 'articles'
    ? articleApi.myArticles({ page: current, size: pageSize })
    : articleApi.myFavorites({ page: current, size: pageSize }))

async function saveProfile() {
  savingProfile.value = true
  try {
    const user = await authApi.updateProfile(profileForm)
    store.setUser(user)
    ElMessage.success('资料已更新')
  } finally {
    savingProfile.value = false
  }
}

function switchTab(tab) {
  activeTab.value = tab
  // 换 tab 回到第 1 页再取 —— 与原先「page.value = 1 之后调对应 load」等价,
  // 而取数函数会因为 activeTab 已经变了而取到另一份列表
  search()
}

async function removeArticle(id) {
  await ElMessageBox.confirm('确定删除这篇文章吗?此操作不可恢复。', '删除文章', { type: 'warning' })
  await articleApi.remove(id)
  ElMessage.success('已删除')
  loadMyArticles()
}

onMounted(loadMyArticles)
</script>

<template>
  <div class="profile-grid">
    <el-card class="profile-card" shadow="never">
      <template #header>个人资料</template>
      <div class="avatar-box">
        <el-avatar :size="72" :src="avatarSrc(profileForm.avatar, store.user?.nickname || store.user?.username)" />
        <span class="username">@{{ store.user?.username }}</span>
        <ImageCropUpload
          button-text="上传头像"
          :aspect-ratio="1"
          :output-width="256"
          :output-height="256"
          upload-type="avatar"
          @uploaded="(data) => (profileForm.avatar = data.url)"
        />
      </div>
      <el-form label-position="top">
        <el-form-item label="昵称">
          <el-input v-model="profileForm.nickname" maxlength="20" />
        </el-form-item>
        <el-form-item label="头像 URL">
          <el-input v-model="profileForm.avatar" placeholder="https://…" />
        </el-form-item>
        <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
      </el-form>
    </el-card>

    <div class="articles-area">
      <el-tabs v-model="activeTab" @tab-change="switchTab">
        <el-tab-pane label="我的文章" name="articles" />
        <el-tab-pane label="我的收藏" name="favorites" />
      </el-tabs>
      <template v-if="myArticles.length">
        <ArticleCard v-for="article in myArticles" :key="article.id" :article="article">
          <template #extra>
            <el-button
              v-if="activeTab === 'articles'"
              type="danger"
              link
              @click="removeArticle(article.id)"
            >删除</el-button>
          </template>
        </ArticleCard>
      </template>
      <el-empty v-else-if="phase === 'failed'" :description="failure">
        <el-button type="primary" @click="loadMyArticles">重试</el-button>
      </el-empty>
      <el-empty v-else :description="activeTab === 'articles' ? '还没有发布过文章' : '还没有收藏任何文章'">
        <el-button v-if="activeTab === 'articles'" type="primary" @click="$router.push('/write')">去写一篇</el-button>
      </el-empty>
      <ListPager :page="page" :size="size" :total="total" @change="goTo" />
    </div>
  </div>
</template>

<style scoped>
.profile-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 20px;
  align-items: start;
}

.profile-card {
  border-radius: 8px;
}

.avatar-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.username {
  color: var(--text-muted);
  font-size: 13px;
}

.section-title {
  margin-bottom: 16px;
}

/* 分页样式归 ListPager */

@media (max-width: 768px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
