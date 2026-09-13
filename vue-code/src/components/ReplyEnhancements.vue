<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { request } from '@/utils/request'
import { showSuccess } from '@/utils'
const props = defineProps<{ accountId: number; goodsId: string }>()
const form = reactive({ welcomeEnabled: 0, welcomeText: '', welcomeImageUrl: '', bargainFloor: null as number | null })
const busy = ref(false)
const error = ref('')
let sequence = 0
watch(() => [props.accountId, props.goodsId], async () => {
  const current = ++sequence
  error.value = ''; busy.value = true
  Object.assign(form, { welcomeEnabled: 0, welcomeText: '', welcomeImageUrl: '', bargainFloor: null })
  try {
    const result = await request<Partial<typeof form>>({ url: '/automation-assist/reply-preference', method: 'GET', params: props })
    if (current === sequence && result.data) Object.assign(form, result.data)
  } catch { if (current === sequence) error.value = '配置加载失败，请重新选择商品后重试' }
  finally { if (current === sequence) busy.value = false }
}, { immediate: true })
async function save() {
  busy.value = true; error.value = ''
  try {
    await request({ url: '/automation-assist/reply-preference', method: 'POST', data: {
      ...form, xianyuAccountId: props.accountId, xyGoodsId: props.goodsId,
      bargainFloor: form.bargainFloor === null || String(form.bargainFloor) === '' ? null : Number(form.bargainFloor),
    } })
    showSuccess('首次回复与议价设置已保存')
  } catch { error.value = '保存失败，请检查输入和连接状态' }
  finally { busy.value = false }
}
</script>
<template>
  <section class="reply-enhancements">
    <h4>首次回复与议价设置</h4>
    <p>同一账号、商品和买家的首次回复只发送一次；后续咨询按关键词或 AI 规则处理。</p>
    <label><input v-model="form.welcomeEnabled" type="checkbox" :true-value="1" :false-value="0" :disabled="busy"> 开启首次回复</label>
    <label>首次回复文字<textarea v-model="form.welcomeText" maxlength="600" rows="3" :disabled="busy" placeholder="例如：你好，使用方式和售后说明如下……" /></label>
    <label>首次回复图片地址<input v-model="form.welcomeImageUrl" type="url" :disabled="busy" placeholder="可选，填写已上传图片的 HTTPS 地址"></label>
    <label>议价底价（元）<input v-model="form.bargainFloor" type="number" min="0" step="0.01" :disabled="busy" placeholder="不填写则不授权 AI 降价"></label>
    <p>底价用于检查 AI 回复中的金额，不会自动改价，也不代表系统会向买家公开底价。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button class="btn btn--primary" :disabled="busy" @click="save">{{ busy ? '处理中…' : '保存设置' }}</button>
  </section>
</template>
<style scoped>
.reply-enhancements{padding:18px;border:1px solid var(--border-color,#e2e8f0);border-radius:12px;margin:12px 0}.reply-enhancements h4{margin:0 0 8px}.reply-enhancements p{font-size:12px;color:#64748b;line-height:1.6}.reply-enhancements label{display:block;margin:12px 0;font-size:13px}.reply-enhancements input:not([type=checkbox]),textarea{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:9px;border:1px solid #cbd5e1;border-radius:6px;background:var(--bg-primary,white);color:inherit}
</style>
