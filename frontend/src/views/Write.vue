<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { articleApi, uploadApi } from '../api'
import { renderMarkdown } from '../utils/markdown'

const route = useRoute()
const router = useRouter()

const isEdit = computed(() => !!route.params.id)
const originalStatus = ref(1)
const formRef = ref()
const saving = ref(false)
const uploading = ref(false)
const contentInputRef = ref()
const fileInputRef = ref()
const coverInputRef = ref()

const form = reactive({
  title: '',
  summary: '',
  cover: '',
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
  const data = await articleApi.editDetail(route.params.id)
  originalStatus.value = data.status
  form.title = data.title
  form.summary = data.summary
  form.cover = data.cover
  form.tagNames = data.tags
  form.content = data.content
}

function payload(draft) {
  return {
    title: form.title,
    summary: form.summary,
    cover: form.cover,
    tagNames: form.tagNames,
    content: form.content,
    draft,
  }
}

async function save(draft) {
  await formRef.value.validate()
  saving.value = true
  try {
    if (isEdit.value) {
      await articleApi.update(route.params.id, payload(draft))
      ElMessage.success(draft ? '草稿已保存' : '已发布')
      if (!draft) {
        router.push(`/article/${route.params.id}`)
      }
    } else {
      const id = await articleApi.create(payload(draft))
      ElMessage.success(draft ? '草稿已保存' : '发布成功')
      if (!draft) {
        router.push(`/article/${id}`)
      } else {
        router.replace(`/write/${id}`)
      }
    }
  } finally {
    saving.value = false
  }
}

function triggerImagePick() {
  fileInputRef.value?.click()
}

function triggerCoverPick() {
  coverInputRef.value?.click()
}

async function onImagePicked(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  uploading.value = true
  try {
    const data = await uploadApi.image(file)
    insertMarkdown(`![${file.name}](${data.url})`)
  } finally {
    uploading.value = false
  }
}

async function onCoverPicked(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  uploading.value = true
  try {
    const data = await uploadApi.image(file, 'cover')
    form.cover = data.url
    ElMessage.success('封面已上传')
  } finally {
    uploading.value = false
  }
}

function insertMarkdown(text) {
  const textarea = contentInputRef.value?.textarea
  if (textarea && typeof textarea.selectionStart === 'number') {
    const start = textarea.selectionStart
    const end = textarea.selectionEnd
    form.content = form.content.slice(0, start) + text + form.content.slice(end)
    requestAnimationFrame(() => {
      const pos = start + text.length
      textarea.setSelectionRange(pos, pos)
      textarea.focus()
    })
  } else {
    form.content += text
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
      <el-form-item label="封面">
        <div class="cover-row">
          <el-input v-model="form.cover" placeholder="封面图片 URL,或上传一张">
            <template #append>
              <el-button :loading="uploading" @click="triggerCoverPick">上传封面</el-button>
            </template>
          </el-input>
          <input
            ref="coverInputRef"
            type="file"
            accept="image/*"
            class="hidden-input"
            @change="onCoverPicked"
          />
          <img v-if="form.cover" :src="form.cover" class="cover-preview" alt="cover" />
        </div>
      </el-form-item>
      <el-form-item label="摘要">
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="文章摘要(可选)"
        />
      </el-form-item>
      <el-form-item label="内容 (Markdown)" prop="content">
        <div class="editor-toolbar">
          <el-button size="small" :loading="uploading" @click="triggerImagePick">插入图片</el-button>
          <input
            ref="fileInputRef"
            type="file"
            accept="image/*"
            class="hidden-input"
            @change="onImagePicked"
          />
        </div>
        <div class="editor">
          <el-input
            ref="contentInputRef"
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
        <el-button v-if="isEdit && originalStatus === 0" @click="save(true)">保存草稿</el-button>
        <el-button v-else-if="!isEdit" type="info" plain :loading="saving" @click="save(true)">保存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="save(false)">{{ isEdit ? '发布' : '发布文章' }}</el-button>
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

.cover-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  width: 100%;
  flex-direction: column;
}

.cover-row .el-input {
  width: 100%;
}

.cover-preview {
  max-height: 180px;
  border-radius: 6px;
}

.hidden-input {
  display: none;
}

.editor-toolbar {
  margin-bottom: 8px;
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
