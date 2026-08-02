<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'

const tags = ref([])
const loading = ref(false)
const renameDialog = ref(false)
const editingTag = ref(null)
const newName = ref('')

async function loadTags() {
  loading.value = true
  try {
    tags.value = await adminApi.tags()
  } finally {
    loading.value = false
  }
}

function openRename(tag) {
  editingTag.value = tag
  newName.value = tag.name
  renameDialog.value = true
}

async function confirmRename() {
  if (!newName.value.trim()) {
    ElMessage.warning('标签名不能为空')
    return
  }
  await adminApi.renameTag(editingTag.value.id, newName.value.trim())
  renameDialog.value = false
  ElMessage.success('已重命名')
  loadTags()
}

async function removeTag(tag) {
  await ElMessageBox.confirm(
    `确定删除标签「${tag.name}」吗?该标签将从 ${tag.count} 篇文章中移除。`,
    '删除标签',
    { type: 'warning' }
  )
  await adminApi.removeTag(tag.id)
  ElMessage.success('已删除')
  loadTags()
}

onMounted(loadTags)
</script>

<template>
  <el-card shadow="never" class="page-card">
    <template #header>标签管理</template>

    <el-table v-loading="loading" :data="tags" size="small">
      <el-table-column prop="name" label="标签名" min-width="200" />
      <el-table-column prop="count" label="文章数" width="120" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRename(row)">重命名</el-button>
          <el-button link type="danger" @click="removeTag(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !tags.length" description="暂无标签" />

    <el-dialog v-model="renameDialog" title="重命名标签" width="360px">
      <el-input v-model="newName" maxlength="50" placeholder="输入新标签名" />
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmRename">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.page-card {
  border-radius: 8px;
}
</style>
