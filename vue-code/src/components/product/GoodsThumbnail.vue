<script setup lang="ts">
import { ref, watch } from 'vue'
import IconImage from '@/components/icons/IconImage.vue'

const props = defineProps<{
  src?: string | null
  alt: string
}>()

const failed = ref(false)

watch(() => props.src, () => {
  failed.value = false
})
</script>

<template>
  <div
    v-if="!src || failed"
    class="goods-thumbnail goods-thumbnail--fallback"
    role="img"
    :aria-label="`${alt} 图片不可用`"
  >
    <IconImage aria-hidden="true" />
  </div>
  <img
    v-else
    class="goods-thumbnail"
    :src="src"
    :alt="alt"
    @error="failed = true"
  />
</template>

<style scoped>
.goods-thumbnail--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(60, 60, 67, 0.38);
  background: rgba(120, 120, 128, 0.10);
}

.goods-thumbnail--fallback :deep(svg) {
  width: 45%;
  height: 45%;
}
</style>
