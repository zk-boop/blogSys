<script setup>
import { nextTick, ref, shallowRef } from 'vue'
import Cropper from 'cropperjs'
import { ElMessage } from 'element-plus'
import { uploadApi } from '../api'

const props = defineProps({
  buttonText: { type: String, default: '上传图片' },
  aspectRatio: { type: Number, required: true },
  outputWidth: { type: Number, required: true },
  outputHeight: { type: Number, required: true },
  uploadType: { type: String, required: true },
})
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
    const img = cropImageRef.value
    img.onload = () => {
      cropper.value?.destroy()
      cropper.value = new Cropper(img, {
        aspectRatio: props.aspectRatio,
        viewMode: 1,
        autoCropArea: 0.9,
        background: false,
      })
    }
    img.src = url
  })
}

async function confirmCrop() {
  if (!cropper.value) return
  const canvas = cropper.value.getCroppedCanvas({
    width: props.outputWidth,
    height: props.outputHeight,
    imageSmoothingQuality: 'high',
  })
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) {
    ElMessage.error('图片处理失败')
    return
  }
  uploading.value = true
  try {
    const data = await uploadApi.image(blob, props.uploadType)
    emit('uploaded', data)
    dialogVisible.value = false
    ElMessage.success('图片已上传')
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
  <div class="crop-upload">
    <el-button size="small" :loading="uploading" @click="pickFile">{{ buttonText }}</el-button>
    <input
      ref="fileInputRef"
      type="file"
      accept="image/*"
      class="hidden-input"
      @change="onFilePicked"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="`裁剪图片 (${aspectRatio === 1 ? '1:1' : '16:9'})`"
      width="860px"
      top="6vh"
      :close-on-click-modal="false"
      @closed="cancel"
    >
      <div class="cropper-wrap">
        <img ref="cropImageRef" alt="crop" />
      </div>
      <div class="crop-tip">拖动选框调整位置,拖动角落手柄调整大小,比例已锁定</div>
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
  height: 70vh;
  overflow: auto;
}

.cropper-wrap img {
  display: block;
  max-width: none;
}

.crop-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
