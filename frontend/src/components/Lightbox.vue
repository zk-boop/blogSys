<script setup>
import { onBeforeUnmount, onMounted } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
})
const emit = defineEmits(['close'])

function onKeydown(event) {
  if (event.key === 'Escape') {
    emit('close')
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <div class="lightbox" @click.self="emit('close')">
      <button class="lightbox-close" @click="emit('close')">×</button>
      <img :src="src" class="lightbox-img" alt="preview" @click="emit('close')" />
      <div class="lightbox-hint">点击任意位置或按 Esc 关闭</div>
    </div>
  </Teleport>
</template>

<style scoped>
.lightbox {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(20, 14, 6, 0.9);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: zoom-out;
  animation: fade-in 0.15s ease;
}

@keyframes fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.lightbox-img {
  max-width: 92vw;
  max-height: 88vh;
  object-fit: contain;
  border-radius: 4px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.5);
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 24px;
  background: none;
  border: none;
  color: #fff;
  font-size: 40px;
  line-height: 1;
  cursor: pointer;
  opacity: 0.8;
}

.lightbox-close:hover {
  opacity: 1;
}

.lightbox-hint {
  position: absolute;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  color: rgba(255, 255, 255, 0.6);
  font-size: 13px;
}
</style>
