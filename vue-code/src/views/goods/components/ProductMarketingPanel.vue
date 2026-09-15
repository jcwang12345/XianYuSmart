<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  applyProductMarketing,
  getProductMarketing,
  newRequestId,
  previewProductMarketing,
  saveProductMarketingDraft,
  type ProductMarketingCommand,
  type ProductMarketingConfiguration
} from '@/api/matrix'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap'
import { showSuccess } from '@/utils'

const props = defineProps<{
  accountId: number
  goodsId: string
  polishAvailable: boolean
  polishReason?: string
}>()
const emit = defineEmits<{ polish: []; changed: [] }>()

type Detail = Record<string, any>
const state = ref<Detail | null>(null)
const loading = ref(true)
const error = ref('')
const dialog = ref(false)
const preview = ref<Detail | null>(null)
const busy = ref(false)
const actionError = ref('')
const confirmation = ref('')
const form = reactive({
  fanAllPrice: '', fanOldPrice: '', fanBuyerPrice: '',
  bargainEnabled: false, bargainPrice: '', bargainQuantity: '',
  coinEnabled: false, coinDiscountPercent: ''
})

useModalFocusTrap(dialog, () => document.querySelector<HTMLElement>('.marketing-dialog'), closeEditor)

const display = (value: unknown) => value === null || value === undefined || value === '' ? '—' : String(value)
const money = (value: unknown) => value === null || value === undefined || value === ''
  ? '—'
  : `¥ ${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const boolText = (value: unknown) => value === null || value === undefined ? '未同步' : value ? '已开启' : '已关闭'
const capability = (code: string) => state.value?.capabilities?.[code] || { status: 'UNKNOWN', source: 'NONE', detail: '尚未探测' }
const capabilityText = (code: string) => {
  const status = String(capability(code).status || 'UNKNOWN')
  return ({ READY: '可用', QA_MOCK: '隔离验证', UNKNOWN: '未探测', UNAVAILABLE: '不可用', REQUIRES_PLATFORM_PERMISSION: '待平台授权' } as Record<string, string>)[status] || status
}
const estimatedCoinCost = computed(() => {
  const price = Number(state.value?.productPrice)
  const percent = Number(form.coinDiscountPercent)
  return form.coinEnabled && Number.isFinite(price) && Number.isFinite(percent) && percent > 0
    ? money(price * percent / 100)
    : '—'
})

function readableError(reason: any, fallback: string) {
  return reason?.response?.data?.message || reason?.response?.data?.msg || reason?.message || fallback
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    state.value = (await getProductMarketing(props.accountId, props.goodsId)).data || null
  } catch (reason: any) {
    error.value = readableError(reason, '营销状态读取失败')
  } finally {
    loading.value = false
  }
}

function hydrate() {
  const desired = state.value?.desired || {}
  Object.assign(form, {
    fanAllPrice: desired.fanAllPrice ?? '',
    fanOldPrice: desired.fanOldPrice ?? '',
    fanBuyerPrice: desired.fanBuyerPrice ?? '',
    bargainEnabled: desired.bargainEnabled === true,
    bargainPrice: desired.bargainPrice ?? '',
    bargainQuantity: desired.bargainQuantity ?? '',
    coinEnabled: desired.coinEnabled === true,
    coinDiscountPercent: desired.coinDiscountPercent ?? ''
  })
}

function openEditor() {
  if (!state.value) return
  hydrate()
  preview.value = null
  actionError.value = ''
  confirmation.value = ''
  dialog.value = true
}

function closeEditor() {
  if (busy.value) return
  dialog.value = false
  preview.value = null
  actionError.value = ''
  confirmation.value = ''
}

function invalidatePreview() {
  preview.value = null
  confirmation.value = ''
  actionError.value = ''
}

function normalizedInput(value: unknown) { return value === null || value === undefined ? '' : String(value).trim() }
function decimal(value: unknown) { const normalized = normalizedInput(value); return normalized === '' ? null : normalized }
function integer(value: unknown) { const normalized = normalizedInput(value); return normalized === '' ? null : Number(normalized) }
function configuration(): ProductMarketingConfiguration {
  return {
    fanAllPrice: decimal(form.fanAllPrice),
    fanOldPrice: decimal(form.fanOldPrice),
    fanBuyerPrice: decimal(form.fanBuyerPrice),
    bargainEnabled: form.bargainEnabled,
    bargainPrice: form.bargainEnabled ? decimal(form.bargainPrice) : null,
    bargainQuantity: form.bargainEnabled ? integer(form.bargainQuantity) : null,
    coinEnabled: form.coinEnabled,
    coinDiscountPercent: form.coinEnabled ? integer(form.coinDiscountPercent) : null
  }
}

function command(prefix: string): ProductMarketingCommand {
  return {
    requestId: newRequestId(prefix),
    expectedVersion: Number(state.value?.rowVersion || 0),
    configuration: configuration()
  }
}

async function runPreview() {
  busy.value = true
  actionError.value = ''
  preview.value = null
  confirmation.value = ''
  try {
    preview.value = (await previewProductMarketing(props.accountId, props.goodsId, command('marketing-preview'))).data || null
  } catch (reason: any) {
    actionError.value = readableError(reason, '营销预检失败')
  } finally {
    busy.value = false
  }
}

async function saveDraft() {
  busy.value = true
  actionError.value = ''
  try {
    state.value = (await saveProductMarketingDraft(props.accountId, props.goodsId, command('marketing-draft'))).data || state.value
    hydrate()
    preview.value = null
    confirmation.value = ''
    showSuccess('营销方案已保存到本地；未向平台宣称生效')
    emit('changed')
  } catch (reason: any) {
    actionError.value = readableError(reason, '营销方案保存失败')
  } finally {
    busy.value = false
  }
}

async function applyConfiguration() {
  if (!preview.value || confirmation.value !== preview.value.confirmationSummary) return
  busy.value = true
  actionError.value = ''
  try {
    const payload = command('marketing-apply')
    payload.previewToken = preview.value.previewToken
    payload.confirmationText = confirmation.value
    state.value = (await applyProductMarketing(props.accountId, props.goodsId, payload)).data || state.value
    hydrate()
    preview.value = null
    confirmation.value = ''
    showSuccess('隔离 QA 营销状态机已完成；未触达闲鱼平台')
    emit('changed')
  } catch (reason: any) {
    actionError.value = readableError(reason, '营销配置应用失败')
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="marketing-workspace" aria-live="polite">
    <div v-if="loading" class="marketing-state"><span class="spinner"></span><div><strong>正在读取营销证据</strong><small>本地方案与平台观察值分开加载。</small></div></div>
    <div v-else-if="error" class="marketing-state error" role="alert"><div><strong>营销状态暂时无法读取</strong><small>{{ error }}</small></div><button class="workbench__btn" @click="load">重试</button></div>
    <template v-else-if="state">
      <article class="marketing-evidence">
        <div><span>平台覆盖</span><strong>{{ display(state.evidence?.coverageStatus) }}</strong></div>
        <div><span>方案来源</span><strong>{{ display(state.evidence?.desiredDataSource) }}</strong></div>
        <div><span>平台来源</span><strong>{{ display(state.evidence?.platformDataSource) }}</strong></div>
        <div><span>平台同步</span><strong>{{ state.evidence?.platformSyncedAt || '未同步' }}</strong></div>
      </article>
      <p class="marketing-truth">平台观察值为空表示“未同步”，不代表关闭或 0；本地方案不会覆盖平台事实。</p>

      <div class="marketing-grid">
        <article>
          <header><div><strong>粉丝价三档</strong><small>整组覆盖，空档位代表删除</small></div><span>{{ capabilityText('FAN_PRICE') }}</span></header>
          <dl>
            <div><dt>全部粉丝</dt><dd>{{ money(state.desired?.fanAllPrice) }} <small>平台 {{ money(state.platformObserved?.fanAllPrice) }}</small></dd></div>
            <div><dt>30 天老粉</dt><dd>{{ money(state.desired?.fanOldPrice) }} <small>平台 {{ money(state.platformObserved?.fanOldPrice) }}</small></dd></div>
            <div><dt>已购粉丝</dt><dd>{{ money(state.desired?.fanBuyerPrice) }} <small>平台 {{ money(state.platformObserved?.fanBuyerPrice) }}</small></dd></div>
          </dl>
          <p>{{ capability('FAN_PRICE').detail }}</p>
        </article>
        <article>
          <header><div><strong>小刀活动</strong><small>活动价与可用份数</small></div><span>{{ capabilityText('BARGAIN_ACTIVITY') }}</span></header>
          <dl><div><dt>本地方案</dt><dd>{{ boolText(state.desired?.bargainEnabled) }} · {{ money(state.desired?.bargainPrice) }} · {{ display(state.desired?.bargainQuantity) }} 份</dd></div><div><dt>平台观察</dt><dd>{{ boolText(state.platformObserved?.bargainEnabled) }} · {{ money(state.platformObserved?.bargainPrice) }}</dd></div></dl>
          <p>{{ capability('BARGAIN_ACTIVITY').detail }}</p>
        </article>
        <article>
          <header><div><strong>闲鱼币抵扣</strong><small>由卖家余额承担让利</small></div><span>{{ capabilityText('COIN_DEDUCTION') }}</span></header>
          <dl><div><dt>协议</dt><dd>{{ display(state.evidence?.coinAgreementStatus) }}</dd></div><div><dt>余额</dt><dd>{{ money(state.evidence?.coinBalance) }}</dd></div><div><dt>本地方案</dt><dd>{{ boolText(state.desired?.coinEnabled) }} · {{ display(state.desired?.coinDiscountPercent) }}%</dd></div></dl>
          <p>{{ capability('COIN_DEDUCTION').detail }}</p>
        </article>
        <article class="degraded">
          <header><div><strong>商城推广、曝光与佣金</strong><small>必须有可靠能力与归因证据</small></div><span>安全降级</span></header>
          <p>{{ capability('PAID_PROMOTION').detail }}。当前不展示可点击后才失败的假入口，也不把未知佣金显示为 0。</p>
          <button class="workbench__btn" disabled>推广与佣金未接入</button>
        </article>
      </div>

      <div class="marketing-actions">
        <button class="workbench__btn" @click="load">重新读取营销证据</button>
        <button class="workbench__btn" disabled title="平台营销同步适配器尚未验证；不能从单品上下文静默扩大到全店">同步本店全部商品营销状态（未接入）</button>
        <button class="workbench__btn" :disabled="!polishAvailable" :title="polishReason" @click="emit('polish')">立即擦亮</button>
        <button class="workbench__btn workbench__btn--primary" @click="openEditor">配置营销方案</button>
      </div>
      <p class="marketing-boundary">{{ state.platformWriteReason }}</p>
    </template>
  </section>

  <div v-if="dialog" class="marketing-backdrop" @click.self="closeEditor">
    <section class="marketing-dialog" role="dialog" aria-modal="true" aria-labelledby="marketing-dialog-title">
      <header><div><span>商品营销方案</span><h2 id="marketing-dialog-title">配置粉丝价与活动</h2><p>{{ goodsId }} · 商品售价 {{ money(state?.productPrice) }}</p></div><button aria-label="关闭营销配置" @click="closeEditor">×</button></header>
      <div class="marketing-dialog__body">
        <section class="marketing-form-section">
          <div class="section-heading"><div><strong>粉丝价三档</strong><small>每档必须大于 0 且严格低于商品售价；留空表示整组覆盖时删除该档。</small></div></div>
          <div class="marketing-form-grid">
            <label>全部粉丝价<input v-model="form.fanAllPrice" data-autofocus class="workbench__input" inputmode="decimal" placeholder="留空不设置" @input="invalidatePreview"></label>
            <label>30 天老粉价<input v-model="form.fanOldPrice" class="workbench__input" inputmode="decimal" placeholder="留空不设置" @input="invalidatePreview"></label>
            <label>已购粉丝价<input v-model="form.fanBuyerPrice" class="workbench__input" inputmode="decimal" placeholder="留空不设置" @input="invalidatePreview"></label>
          </div>
        </section>
        <section class="marketing-form-section">
          <label class="marketing-toggle"><input v-model="form.bargainEnabled" type="checkbox" @change="invalidatePreview"><span><strong>小刀活动</strong><small>开启后设置活动价与可用份数。</small></span></label>
          <div v-if="form.bargainEnabled" class="marketing-form-grid two">
            <label>活动价<input v-model="form.bargainPrice" class="workbench__input" inputmode="decimal" @input="invalidatePreview"></label>
            <label>活动份数<input v-model="form.bargainQuantity" class="workbench__input" inputmode="numeric" @input="invalidatePreview"></label>
          </div>
        </section>
        <section class="marketing-form-section">
          <label class="marketing-toggle"><input v-model="form.coinEnabled" type="checkbox" @change="invalidatePreview"><span><strong>闲鱼币抵扣</strong><small>真实执行前必须确认协议、余额与平台通道。</small></span></label>
          <div v-if="form.coinEnabled" class="marketing-form-grid two">
            <label>抵扣比例（1～99%）<input v-model="form.coinDiscountPercent" class="workbench__input" inputmode="numeric" @input="invalidatePreview"></label>
            <div class="cost-card"><span>预计单件让利</span><strong>{{ estimatedCoinCost }}</strong><small>仅用于预检，不代表平台最终结算。</small></div>
          </div>
        </section>

        <div v-if="actionError" class="marketing-error" role="alert"><strong>操作未完成</strong><span>{{ actionError }}</span></div>
        <section v-if="preview" :class="['marketing-preview', { blocked: !preview.executable }]">
          <header><strong>{{ preview.executable ? '预检可执行' : '预检存在阻塞' }}</strong><span>{{ preview.executionChannel === 'QA_MOCK' ? '隔离 QA Mock' : '平台不可用' }}</span></header>
          <div class="preview-counts"><span>SKU 已验证 / 主档<strong>{{ preview.declaredSkuCount == null ? `${preview.verifiedSkuCount} / 未知` : `${preview.verifiedSkuCount} / ${preview.declaredSkuCount}` }}</strong></span><span>删除档位<strong>{{ preview.deletedFanTiers?.length || 0 }}</strong></span><span>字段变化<strong>{{ Object.keys(preview.fieldDiff || {}).length }}</strong></span></div>
          <p>{{ preview.confirmationSummary }}</p>
          <ul v-if="preview.deletedFanTiers?.length"><li>将删除：{{ preview.deletedFanTiers.join('、') }}</li></ul>
          <ul v-if="preview.warnings?.length"><li v-for="item in preview.warnings" :key="item">{{ item }}</li></ul>
          <ul v-if="preview.conflicts?.length" class="conflicts"><li v-for="item in preview.conflicts" :key="item">{{ item }}</li></ul>
          <label v-if="preview.executable">输入完整确认文案<input v-model="confirmation" class="workbench__input" :placeholder="preview.confirmationSummary"></label>
        </section>
      </div>
      <footer>
        <button class="workbench__btn" :disabled="busy" @click="closeEditor">取消</button>
        <button class="workbench__btn" :disabled="busy" @click="saveDraft">{{ busy ? '处理中…' : '仅保存本地方案' }}</button>
        <button class="workbench__btn" :disabled="busy" @click="runPreview">{{ busy ? '正在预检…' : '运行预检' }}</button>
        <button class="workbench__btn workbench__btn--primary" :disabled="busy || !preview?.executable || confirmation !== preview?.confirmationSummary" @click="applyConfiguration">应用配置</button>
      </footer>
    </section>
  </div>
</template>

<style scoped src="./ProductMarketingPanel.css"></style>
