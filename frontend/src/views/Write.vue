<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { articleApi } from '../api'
import { renderMarkdown } from '../utils/markdown'

const route = useRoute()
const router = useRouter()

const isEdit = computed(() => !!route.params.id)
const formRef = ref()
const saving = ref(false)
const form = reactive({
  title: '',
  summary: '',
  tagNames: [],
  content: '',
})

const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入内容', trigger: 'blur' }],
}

const preview = computed(() => renderMarkdown(form.content))

async function loadArticle() {
  if (!isEdit.value) return
  const data = await articleApi.detail(route.params.id)
  form.title = data.title
  form.summary = data.summary
  form.tagNames = data.tags
  form.content = data.content
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    const payload = {
      title: form.title,
      summary: form.summary,
      tagNames: form.tagNames,
      content: form.content,
    }
    if (isEdit.value) {
      await articleApi.update(route.params.id, payload)
      ElMessage.success('已保存')
      router.push(`/article/${route.params.id}`)
    } else {
      const id = await articleApi.create(payload)
      ElMessage.success('发布成功')
      router.push(`/article/${id}`)
    }
  } finally {
    saving.value = false
  }
}

onMounted(loadArticle)
</script>

<template>
  <el-card shadow="never" class="write-card">
    <h2 class="write-title">{{ isEdit ? '编辑文章' : '写文章' }}</h2>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="输入文章标题" />
      </el-form-item>
      <el-form-item label="标签">
        <el-select
          v-model="form.tagNames"
          multiple
          filterable
          allow-create
          default-first-option
          placeholder="输入标签后回车,可多选"
          style="width: 100%"
        >
          <el-option v-for="tag in form.tagNames" :key="tag" :label="tag" :value="tag" />
        </el-select>
      </el-form-item>
      <el-form-item label="摘要">
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="文章摘要(可选),留空则列表页显示暂无摘要"
        />
      </el-form-item>
      <el-form-item label="内容 (Markdown)" prop="content">
        <div class="editor">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="18"
            resize="none"
            placeholder="支持 Markdown 语法,写正文…"
          />
          <div class="preview" v-html="preview" />
        </div>
      </el-form-item>
      <div class="actions">
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ isEdit ? '保存' : '发布' }}</el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.write-card {
  border-radius: 8px;
}

.write-title {
  margin-bottom: 16px;
  font-size: 20px;
}

.editor {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  width: 100%;
}

.editor :deep(.el-textarea__inner) {
  font-family: Consolas, Monaco, monospace;
  font-size: 14px;
}

.preview {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 12px;
  min-height: 420px;
  overflow-y: auto;
  line-height: 1.7;
  background: #fafafa;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

@media (max-width: 768px) {
  .editor {
    grid-template-columns: 1fr;
  }
}
</style>
