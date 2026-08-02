<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { articleApi, authApi } from '../api'
import { useUserStore } from '../stores/user'
import ArticleCard from '../components/ArticleCard.vue'
import ImageCropUpload from '../components/ImageCropUpload.vue'
import { avatarSrc } from '../utils/avatar'

const store = useUserStore()

const profileForm = reactive({
  nickname: store.user?.nickname || '',
  avatar: store.user?.avatar || '',
})

const myArticles = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const savingProfile = ref(false)
const activeTab = ref('articles')

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

async function loadMyArticles() {
  const data = await articleApi.myArticles({ page: page.value, size: size.value })
  myArticles.value = data.records
  total.value = data.total
}

async function loadMyFavorites() {
  const data = await articleApi.myFavorites({ page: page.value, size: size.value })
  myArticles.value = data.records
  total.value = data.total
}

function switchTab(tab) {
  activeTab.value = tab
  page.value = 1
  if (tab === 'articles') {
    loadMyArticles()
  } else {
    loadMyFavorites()
  }
}

async function removeArticle(id) {
  await ElMessageBox.confirm('确定删除这篇文章吗?此操作不可恢复。', '删除文章', { type: 'warning' })
  await articleApi.remove(id)
  ElMessage.success('已删除')
  activeTab.value === 'articles' ? loadMyArticles() : loadMyFavorites()
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
      <el-empty v-else :description="activeTab === 'articles' ? '还没有发布过文章' : '还没有收藏任何文章'">
        <el-button v-if="activeTab === 'articles'" type="primary" @click="$router.push('/write')">去写一篇</el-button>
      </el-empty>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="activeTab === 'articles' ? loadMyArticles() : loadMyFavorites()"
        />
      </div>
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

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

@media (max-width: 768px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
