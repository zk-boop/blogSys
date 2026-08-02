<script setup>
import { nextTick, ref, shallowRef } from 'vue'
import Cropper from 'cropperjs'
import { ElMessage } from 'element-plus'
import { uploadApi } from '../api'

const emit = defineEmits(['uploaded'])

const fileInputRef = ref()
const dialogVisible = ref(false)
const cropImageRef = ref()
const uploading = ref(false)
const cropper = shallowRef(null)

function pickFile() {
  fileInputRef.value?.click()
}

function onFilePicked(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  openCropper(file)
}

function openCropper(file) {
  const url = URL.createObjectURL(file)
  dialogVisible.value = true
  nextTick(() => {
    cropper.value?.destroy()
    cropImageRef.value.src = url
    cropper.value = new Cropper(cropImageRef.value, {
      aspectRatio: 1,
      viewMode: 1,
      autoCropArea: 0.8,
      background: false,
    })
  })
}

async function confirmCrop() {
  if (!cropper.value) return
  const canvas = cropper.value.getCroppedCanvas({
    width: 256,
    height: 256,
    imageSmoothingQuality: 'high',
  })
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) {
    ElMessage.error('图片处理失败')
    return
  }
  uploading.value = true
  try {
    const data = await uploadApi.image(blob, 'avatar')
    emit('uploaded', data.url)
    dialogVisible.value = false
    ElMessage.success('头像已上传,记得保存资料')
  } finally {
    uploading.value = false
    cropper.value?.destroy()
    cropper.value = null
  }
}

function cancel() {
  dialogVisible.value = false
  cropper.value?.destroy()
  cropper.value = null
}
</script>

<template>
  <div>
    <el-button size="small" :loading="uploading" @click="pickFile">上传头像</el-button>
    <input
      ref="fileInputRef"
      type="file"
      accept="image/*"
      class="hidden-input"
      @change="onFilePicked"
    />

    <el-dialog
      v-model="dialogVisible"
      title="裁剪头像"
      width="420px"
      :close-on-click-modal="false"
      @closed="cancel"
    >
      <div class="cropper-wrap">
        <img ref="cropImageRef" alt="crop" />
      </div>
      <template #footer>
        <el-button @click="cancel">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="confirmCrop">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hidden-input {
  display: none;
}

.cropper-wrap {
  max-height: 360px;
}

.cropper-wrap img {
  display: block;
  max-width: 100%;
}
</style>
