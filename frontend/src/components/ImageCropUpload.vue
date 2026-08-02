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
const cropWrapRef = ref()
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
      cropper.value = new Cropper(img, { container: cropWrapRef.value })
      const canvas = cropper.value.getCropperCanvas()
      if (canvas) {
        canvas.style.width = '100%'
        canvas.style.height = '100%'
      }
      const selection = cropper.value.getCropperSelection()
      if (selection) {
        selection.aspectRatio = props.aspectRatio
        selection.initialCoverage = 0.85
        selection.movable = true
        selection.resizable = true
        selection.zoomable = true
        selection.keyboard = true
        selection.$reset()
      }
    }
    img.src = url
  })
}

function zoom(delta) {
  cropper.value?.getCropperImage()?.$zoom(delta)
}

function resetView() {
  const cropperInstance = cropper.value
  cropperInstance?.getCropperImage()?.$resetTransform()
  cropperInstance?.getCropperSelection()?.$reset()
}

function onWheel(event) {
  event.preventDefault()
  zoom(event.deltaY < 0 ? 0.05 : -0.05)
}

async function confirmCrop() {
  const selection = cropper.value?.getCropperSelection()
  if (!selection) return
  const canvas = await selection.$toCanvas({
    width: props.outputWidth,
    height: props.outputHeight,
  })
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) {
    ElMessage.error('图片处理失败')
    return
  }
  uploading.value = true
  try {
    const file = new File([blob], 'cropped.jpg', { type: 'image/jpeg' })
    const data = await uploadApi.image(file, props.uploadType)
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
      <div
        ref="cropWrapRef"
        class="cropper-wrap"
        @wheel="onWheel"
      >
        <img ref="cropImageRef" alt="crop" />
      </div>
      <div class="crop-toolbar">
        <el-button-group>
          <el-button size="small" @click="zoom(-0.1)">缩小</el-button>
          <el-button size="small" @click="zoom(0.1)">放大</el-button>
          <el-button size="small" @click="resetView">适应</el-button>
        </el-button-group>
      </div>
      <div class="crop-tip">拖动图片调整位置,拖动选框角落手柄调整大小,比例已锁定;也可滚轮缩放</div>
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
  height: 60vh;
  min-height: 320px;
  overflow: hidden;
  position: relative;
}

.cropper-wrap :deep(cropper-canvas),
.cropper-wrap :deep(cropper-image) {
  display: block;
  width: 100%;
  height: 100%;
}

.crop-toolbar {
  display: flex;
  justify-content: center;
  margin-top: 10px;
}

.crop-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}
</style>
