<script setup lang="ts">
import { ref, watch } from 'vue'
import { request } from '@/utils/request'
const props = defineProps<{ accountId: number; orderId: string }>()
type Timeline = { steps: { name: string; time?: string; status: string }[]; lastError?: string; confirmation?: { status: string; lastError?: string } }
const data = ref<Timeline | null>(null)
const error = ref('')
const busy = ref(false)
let sequence = 0
async function load() {
  const current = ++sequence; busy.value = true; error.value = ''; data.value = null
  try { const result = await request<Timeline>({ url: '/automation-assist/timeline', method: 'GET', params: props }); if (current === sequence) data.value = result.data ?? null }
  catch { if (current === sequence) error.value = '订单进度加载失败' }
  finally { if (current === sequence) busy.value = false }
}
watch(() => [props.accountId, props.orderId], load, { immediate: true })
async function retry() {
  busy.value = true
  try { await request({ url: '/automation-assist/confirm-retry', method: 'POST', data: props }); await load() }
  catch { error.value = '确认任务提交失败'; busy.value = false }
}
</script>
<template>
  <section class="order-timeline">
    <h4>订单处理进度</h4>
    <p v-if="busy">正在读取…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <ol v-if="data">
      <li v-for="step in data.steps" :key="step.name"><strong>{{ step.name }}</strong><span>{{ step.status }}</span><small v-if="step.time">{{ step.time }}</small></li>
    </ol>
    <p v-if="data?.lastError">{{ data.lastError }}</p>
    <p v-if="data?.confirmation?.lastError">{{ data.confirmation.lastError }}</p>
    <button v-if="data?.confirmation && ['FAILED','RETRY_WAIT','REVIEW_REQUIRED'].includes(data.confirmation.status)" class="btn" :disabled="busy" @click="retry">仅重试平台确认</button>
    <button class="btn" :disabled="busy" @click="load">刷新进度</button>
  </section>
</template>
<style scoped>
.order-timeline{padding:16px;border-bottom:1px solid #e2e8f0}.order-timeline ol{padding-left:20px}.order-timeline li{padding:7px 0;font-size:13px}.order-timeline strong{display:inline-block;min-width:95px}.order-timeline small{display:block;color:#64748b}.order-timeline p{color:#b45309;font-size:13px}.order-timeline .btn{margin-right:8px}
</style>
