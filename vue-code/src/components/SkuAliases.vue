<script setup lang="ts">
import { ref, watch } from 'vue'
import { request } from '@/utils/request'
import { showSuccess } from '@/utils'
const props = defineProps<{ accountId: number; goodsId: string }>()
type Sku = { skuId: string; valueText?: string; displayName?: string }
const skus = ref<Sku[]>([])
const busy = ref(false)
const error = ref('')
const emit = defineEmits<{ updated: [] }>()
let sequence = 0
async function load(sync = false) {
  const current = ++sequence; busy.value = true; error.value = ''
  try {
    const result = await request<Sku[]>({ url: sync ? '/automation-assist/skus/sync' : '/automation-assist/skus', method: sync ? 'POST' : 'GET', ...(sync ? { data: props } : { params: props }) })
    if (current === sequence) { skus.value = result.data || []; if (sync) emit('updated') }
  } catch { if (current === sequence) error.value = '同步失败，请检查账号凭证或平台验证状态后重试' }
  finally { if (current === sequence) busy.value = false }
}
watch(() => [props.accountId, props.goodsId], () => load(), { immediate: true })
async function save(sku: Sku) {
  busy.value = true
  try { await request({ url: '/automation-assist/skus/name', method: 'POST', data: { ...props, skuId: sku.skuId, name: sku.displayName || '' } }); showSuccess('规格别名已保存'); emit('updated') }
  catch { error.value = '别名保存失败' }
  finally { busy.value = false }
}
</script>
<template>
  <details class="sku-aliases">
    <summary>规格同步与后台别名（{{ skus.length }} 个规格）</summary>
    <p>别名仅用于后台识别，发货仍按平台 SKU 匹配。</p>
    <button class="btn" :disabled="busy" @click="load(true)">{{ busy ? '处理中…' : '重新同步规格' }}</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-for="sku in skus" :key="sku.skuId" class="sku-aliases__row">
      <span>{{ sku.valueText || sku.skuId }}</span><input v-model="sku.displayName" maxlength="100" :aria-label="`${sku.valueText || sku.skuId} 的后台别名`" placeholder="后台别名"><button :disabled="busy" @click="save(sku)">保存</button>
    </div>
  </details>
</template>
<style scoped>
.sku-aliases{margin:12px 0;padding:12px;border:1px solid #e2e8f0;border-radius:8px;font-size:13px}.sku-aliases summary{cursor:pointer}.sku-aliases p{color:#64748b}.sku-aliases__row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0}.sku-aliases__row input{padding:6px;border:1px solid #cbd5e1;border-radius:4px}
</style>
