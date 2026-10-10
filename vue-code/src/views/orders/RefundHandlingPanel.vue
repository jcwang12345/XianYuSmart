<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { newRequestId, recordRefundHandling } from '@/api/matrix'
import { hasPermission } from '@/utils/permission'
import { showError, showSuccess } from '@/utils'

const props = defineProps<{ detail: Record<string, any> }>()
const emit = defineEmits<{ saved: []; conversation: [] }>()
const caseId = ref<number>()
const saving = ref(false)
const form = reactive({ status: 'IN_PROGRESS' as 'IN_PROGRESS' | 'RESOLVED', note: '', platformChecked: false, requestId: '' })
const selectedCase = computed(() => props.detail.refunds?.find((item: Record<string, any>) => item.refundCaseId === caseId.value))
const canWrite = computed(() => hasPermission('action:order-write'))
watch(() => props.detail.refunds, cases => {
  if (!cases?.some((item: Record<string, any>) => item.refundCaseId === caseId.value)) caseId.value = cases?.[0]?.refundCaseId
}, { immediate: true })
watch(selectedCase, item => {
  form.status = item?.localHandlingStatus === 'RESOLVED' ? 'RESOLVED' : 'IN_PROGRESS'
  form.note = item?.localHandlingNote || ''
  form.platformChecked = !!item?.localPlatformChecked
  form.requestId = newRequestId('refund-handling')
}, { immediate: true })
const localLabel = (status?: string) => ({ UNHANDLED: '待核对', IN_PROGRESS: '本地跟进中', RESOLVED: '本地已处理' }[status || ''] || '待核对')
const copyOrder = async () => {
  try { await navigator.clipboard.writeText(String(props.detail.order.orderId)); showSuccess('订单号已复制') }
  catch { showError('复制不可用，请手动复制下方订单号') }
}
const save = async () => {
  if (!caseId.value || saving.value) return
  saving.value = true
  try {
    await recordRefundHandling(caseId.value, { ...form, note: form.note.trim() })
    showSuccess('本地处理记录已保存；未向闲鱼提交退款决定')
    form.requestId = newRequestId('refund-handling')
    emit('saved')
  } catch (error: any) { showError(error.message || '保存失败，请保留说明并重试') }
  finally { saving.value = false }
}
</script>

<template>
  <section class="refund-handling" aria-labelledby="refund-handling-title">
    <header><div><span class="refund-handling__eyebrow">售后待办</span><h3 id="refund-handling-title">核对闲鱼退款，记录处理进展</h3></div><span class="refund-handling__badge">不自动退款</span></header>
    <p class="refund-handling__order">订单 <code>{{ detail.order.orderId }}</code></p>
    <ol>
      <li>使用当前店铺对应的闲鱼账号，在官方 App 的卖出订单中找到这笔订单，打开退款 / 售后详情。</li>
      <li>核对退款原因、申请金额、处理期限、是否已交付和聊天证据，再由你在闲鱼决定同意或拒绝。</li>
      <li>回到此处记录跟进结果。这里的“已处理”仅关闭本地待办，不代表平台已退款。</li>
    </ol>
    <div class="refund-handling__actions"><button class="workbench__btn" @click="copyOrder">复制订单号</button><button class="workbench__btn" :disabled="!hasPermission('menu:messages')" :title="hasPermission('menu:messages') ? undefined : '当前角色没有消息查看权限'" @click="emit('conversation')">查看买家会话</button></div>
    <p class="refund-handling__notice">退款待办不会暂停该店铺其他咨询的 AI 回复。原因、金额或截止时间未同步时，请以闲鱼官方页面为准。</p>
    <div v-if="selectedCase" class="refund-handling__form">
      <p v-if="selectedCase.evidence?.source === 'LEGACY_PLATFORM_STATUS_SNAPSHOT'" class="refund-handling__notice">此案例由已有平台状态快照补齐，尚未重新获取平台退款详情。请核对闲鱼官方最新状态。</p>
      <label v-if="detail.refunds.length > 1">售后记录<select v-model="caseId" class="workbench__select"><option v-for="item in detail.refunds" :key="item.refundCaseId" :value="item.refundCaseId">售后记录 {{ item.refundCaseId }} · {{ localLabel(item.localHandlingStatus) }}</option></select></label>
      <p>本地待办：<strong>{{ localLabel(selectedCase.localHandlingStatus) }}</strong></p>
      <p v-if="!canWrite" role="note">当前角色只有查看权限，请联系有订单处理权限的成员跟进。</p>
      <template v-else>
        <label>本地处理状态<select v-model="form.status" class="workbench__select"><option value="IN_PROGRESS">跟进中</option><option value="RESOLVED">已在平台核对，本地已处理</option></select></label>
        <label>处理说明<textarea v-model="form.note" class="workbench__textarea" rows="3" maxlength="1000" placeholder="例如：已在闲鱼核对，等待买家补充退款原因"></textarea></label>
        <label v-if="form.status === 'RESOLVED'" class="refund-handling__check"><input v-model="form.platformChecked" type="checkbox">我已在闲鱼核对真实处理结果；本次只保存本地协作记录。</label>
        <button class="workbench__btn workbench__btn--primary" :disabled="saving || !form.note.trim() || (form.status === 'RESOLVED' && !form.platformChecked)" @click="save">{{ saving ? '保存中…' : '保存本地处理记录' }}</button>
      </template>
    </div>
    <p v-else class="refund-handling__notice">尚无可核验退款案例：可以先按上方步骤在闲鱼处理，但暂不能保存退款处理状态。请刷新订单事实或联系管理员核对事件同步。</p>
  </section>
</template>

<style scoped>
.refund-handling{grid-column:1/-1;padding:22px;border:1px solid #eadbb2;border-radius:16px;background:#fffdf5;min-width:0}
.refund-handling header{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}
.refund-handling h3{margin:6px 0;font-size:18px;color:#282d35}.refund-handling__eyebrow{font-size:12px;color:#826617;font-weight:700}
.refund-handling__badge{white-space:nowrap;padding:5px 9px;background:#fff0cc;border-radius:8px;font-size:12px;color:#76550b}
.refund-handling__order{overflow-wrap:anywhere;color:#545c66}.refund-handling ol{padding-left:22px;line-height:1.8;color:#434b55}
.refund-handling__actions{display:flex;gap:10px;flex-wrap:wrap}.refund-handling__notice{font-size:13px;line-height:1.7;color:#706547}
.refund-handling__form{display:grid;gap:14px;border-top:1px solid #eadfbf;padding-top:16px;margin-top:16px}.refund-handling__form p{margin:0}
.refund-handling__form label{display:grid;gap:8px;font-size:14px;color:#454d57}.refund-handling__form button{justify-self:start}
.refund-handling__form .refund-handling__check{display:flex;align-items:flex-start;line-height:1.6;gap:10px}.refund-handling__check input{margin-top:5px;flex-shrink:0}
@media(max-width:600px){.refund-handling{padding:16px}.refund-handling header{flex-wrap:wrap}.refund-handling__form button{width:100%}}
</style>
