<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { articleApi, uploadApi } from '../api'
import { useUserStore } from '../stores/user'
import { renderMarkdown } from '../utils/markdown'
import ImageCropUpload from '../components/ImageCropUpload.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const isEdit = computed(() => !!route.params.id)
/** articles.status 的取值,与后端 ArticleStatus 对应。 */
const PUBLISHED = 1
const originalStatus = ref(PUBLISHED)
const formRef = ref()
const saving = ref(false)
const uploading = ref(false)
const contentInputRef = ref()
const fileInputRef = ref()
const dirty = ref(false)
const loaded = ref(false)
let autosaveTimer = null

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
  if (!isEdit.value) {
    loaded.value = true
    return
  }
  const data = await articleApi.editDetail(route.params.id)
  originalStatus.value = data.status
  form.title = data.title
  form.summary = data.summary
  form.cover = data.cover
  form.tagNames = data.tags
  form.content = data.content
  // 先让上面这些赋值流过下面那个深度 watcher,再打开 loaded。
  // 否则 watcher 会在这个同步块结束后才 flush,那时 loaded 已经是 true ——
  // 于是「打开编辑页、什么都不碰」也会被判定为「有改动」,20 秒后触发一次自动保存。
  await nextTick()
  loaded.value = true
  dirty.value = false
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

/** 用户主动保存:校验、提示、必要时跳转。自动保存不走这里(见 autosave)。 */
async function save(draft) {
  if (!draft) {
    await formRef.value.validate()
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await articleApi.update(route.params.id, payload(draft))
      dirty.value = false
      ElMessage.success(draft ? '草稿已保存' : '已发布')
      if (!draft) {
        router.push(`/article/${route.params.id}`)
      }
    } else {
      const id = await articleApi.create(payload(draft))
      dirty.value = false
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

/**
 * 自动保存:把当前内容落库,**不改发布状态、也不跳转**。
 *
 * <p>它此前是 `save(true, true)` —— 固定以 `draft: true` 保存。于是打开一篇**已发布**
 * 文章的编辑页停留 20 秒,那篇文章就被静默改成了草稿;`originalStatus` 在载入时读了出来
 * 却从未被使用过。现在按文章原本的状态保存。
 *
 * <p>新文章仍以草稿落库:用户没点发布,不该替他发布。
 *
 * <p>后端的 `draft` 是 `Boolean`,且 `null/false` 都表示发布(见 `ArticleService.resolveStatus`),
 * 所以这里必须显式传出想要的状态,不能靠省略字段。
 */
async function autosave() {
  if (!dirty.value || saving.value) return
  saving.value = true
  const draft = isEdit.value ? originalStatus.value !== PUBLISHED : true
  try {
    if (isEdit.value) {
      await articleApi.update(route.params.id, payload(draft))
    } else {
      const id = await articleApi.create(payload(draft))
      dirty.value = false
      router.replace(`/write/${id}`)
      return
    }
    dirty.value = false
  } catch {
    /* 自动保存失败静默,下次定时重试 */
  } finally {
    saving.value = false
  }
}

function onBeforeUnload(event) {
  if (dirty.value && store.isLoggedIn) {
    event.preventDefault()
    event.returnValue = ''
  }
}

watch(
  () => [form.title, form.content, form.summary, form.cover, form.tagNames],
  () => {
    if (loaded.value) {
      dirty.value = true
    }
  },
  { deep: true }
)

onMounted(() => {
  loadArticle()
  autosaveTimer = setInterval(autosave, 20000)
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  clearInterval(autosaveTimer)
  window.removeEventListener('beforeunload', onBeforeUnload)
})

function triggerImagePick() {
  fileInputRef.value?.click()
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

async function onCoverUploaded(data) {
  form.cover = data.url
  ElMessage.success('封面已上传')
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
          <div class="cover-input-row">
            <el-input v-model="form.cover" placeholder="封面图片 URL,或上传后自动填充">
              <template #append>
                <ImageCropUpload
                  button-text="上传裁剪"
                  :aspect-ratio="16 / 9"
                  :output-width="1280"
                  :output-height="720"
                  upload-type="cover"
                  @uploaded="onCoverUploaded"
                />
              </template>
            </el-input>
          </div>
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

.cover-input-row {
  width: 100%;
}

.cover-preview {
  width: 100%;
  max-width: 480px;
  aspect-ratio: 16 / 9;
  object-fit: cover;
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
  border: 1px solid var(--border-color);
  border-radius: 4px;
  padding: 12px;
  min-height: 420px;
  overflow-y: auto;
  line-height: 1.7;
  background: var(--card-bg);
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
