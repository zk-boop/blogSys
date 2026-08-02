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
      <h3 class="section-title">我的文章</h3>
      <template v-if="myArticles.length">
        <ArticleCard v-for="article in myArticles" :key="article.id" :article="article">
          <template #extra>
            <el-button type="danger" link @click="removeArticle(article.id)">删除</el-button>
          </template>
        </ArticleCard>
      </template>
      <el-empty v-else description="还没有发布过文章">
        <el-button type="primary" @click="$router.push('/write')">去写一篇</el-button>
      </el-empty>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="loadMyArticles"
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
