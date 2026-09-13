<script setup>
/**
 * 分页块。此前这块模板在 6 个 view 里逐字相同 —— 连同它那两套几乎一样的样式
 * (公开页居中、后台表格右对齐)。
 *
 * <p>它刻意<b>不接受 `v-model:current-page`</b>:页码的唯一写入者是列表模块的 `goTo`,
 * 这里只把用户点的页码报上去。少一条写入路径,就少一处「页码与数据不同步」的可能。
 *
 * <p>`Profile.vue` 原来那一块还少了 `total`(layout 是 `prev, pager, next`),
 * 于是它的分页不显示总数 —— 共享之后这一处不一致顺手消失了。
 */
defineProps({
  page: { type: Number, required: true },
  size: { type: Number, required: true },
  total: { type: Number, required: true },
  /** 对齐方式:公开页居中,后台表格右对齐。 */
  align: { type: String, default: 'center' },
})

defineEmits(['change'])
</script>

<template>
  <div class="pagination" :class="`pagination--${align}`">
    <el-pagination
      :current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next, total"
      background
      @current-change="$emit('change', $event)"
    />
  </div>
</template>

<style scoped>
.pagination {
  display: flex;
  margin-top: 24px;
}

.pagination--center {
  justify-content: center;
}

.pagination--end {
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
