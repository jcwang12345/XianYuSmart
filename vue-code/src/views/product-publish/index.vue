<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { getAccountList } from '@/api/account'
import { getResources, type MerchantResource } from '@/api/merchant'
import { createListingDraft, executePublish, getListingDrafts, getListingDraftVersions, getListingFormSchema, getPublishingRequestStatus, newRequestId, preflightPublish, updateListingDraft, validateListingDraft, type ListingFormSchema } from '@/api/matrix'
import PublishAddressFields from '@/components/PublishAddressFields.vue'
import MediaUploader from '@/components/MediaUploader.vue'
import ListingSkuEditor from '@/components/product/ListingSkuEditor.vue'
import ListingPhonePreview from '@/components/product/ListingPhonePreview.vue'
import type { PublishAddress } from '@/data/publish-address'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import '@/styles/merchant-workbench.css'

type Dimension = { name: string; values: string[] }
type Sku = { key: string; values: Record<string, string>; price: number; originalPrice?: number; stock: number; merchantCode?: string; image?: string }

const loading = ref(false)
const schemaLoading = ref(false)
const schemaError = ref('')
const schema = ref<ListingFormSchema | null>(null)
const accounts = ref<Account[]>([])
const materials = ref<MerchantResource[]>([])
const drafts = ref<Array<Record<string, any>>>([])
const draftVersions = ref<Array<Record<string, any>>>([])
const activeSection = ref('identity')
const mobilePane = ref<'FORM' | 'PREVIEW'>('FORM')
const draftId = ref<number | null>(null)
const draftRevision = ref(0)
const draftSaveState = ref<'IDLE' | 'SAVING' | 'SAVED' | 'ERROR'>('IDLE')
const draftSavedAt = ref<Date | null>(null)
const hydratingDraft = ref(false)
const readyForAutosave = ref(false)
const localValidation = ref<Record<string, any> | null>(null)
const preflightResult = ref<Record<string, any> | null>(null)
const publishResult = ref<Record<string, any> | null>(null)
const preflightRequestId = ref('')
const publishRequestId = ref('')
const publishFingerprint = ref('')

const form = reactive<Record<string, any>>({
  xianyuAccountId: 0, publishChannel: '', productType: 'VIRTUAL', businessMode: 'NORMAL',
  name: '', description: '', amount: 0, originalPrice: undefined, stock: 1, outerId: '',
  conditionCode: 'DIGITAL', industryCode: '', leafCategoryCode: '', leafCategoryName: '', categoryAttributes: {},
  shippingMode: 'ONLINE_DELIVERY', shippingFee: undefined, freightTemplateId: '',
  province: '北京市', city: '北京市', district: '', divisionId: '', gps: '', poiId: '', poiName: '',
  imagesText: '', videoUrl: '', skuDimensions: [] as Dimension[], skus: [] as Sku[], serviceProtocols: [] as string[],
  fulfillmentMode: 'AUTO_DELIVERY', validityDays: 7, supportPolicy: '', afterSalesPolicy: '',
  serviceDurationMinutes: 60, appointmentLeadHours: 2, serviceArea: '', catalogVersion: ''
})

const sections = [
  { id: 'identity', label: '店铺与通道', hint: '选择发布身份' },
  { id: 'category', label: '类型与类目', hint: '行业、叶子类目、属性' },
  { id: 'content', label: '商品内容', hint: '标题、描述、媒体' },
  { id: 'price', label: '价格与规格', hint: '售价、库存、SKU' },
  { id: 'trade', label: '交易与服务', hint: '物流、位置、承诺' },
  { id: 'review', label: '预检与发布', hint: '确认最终请求' }
]

const images = computed<string[]>({
  get: () => String(form.imagesText || '').split('\n').map(value => value.trim()).filter(Boolean),
  set: value => { form.imagesText = value.join('\n') }
})
const videos = computed<string[]>({
  get: () => form.videoUrl ? [String(form.videoUrl)] : [],
  set: value => { form.videoUrl = value[0] || '' }
})
const channels = computed<Array<Record<string, any>>>(() => schema.value?.channelCapabilities?.channels || [])
const selectedChannel = computed(() => channels.value.find(item => item.channelCode === form.publishChannel) || null)
const selectedAccount = computed(() => accounts.value.find(item => item.id === form.xianyuAccountId) || null)
const industries = computed(() => schema.value?.industries || [])
const selectedIndustry = computed(() => industries.value.find(item => item.code === form.industryCode) || null)
const leafCategories = computed(() => selectedIndustry.value?.leafCategories || [])
const selectedLeaf = computed(() => leafCategories.value.find(item => item.code === form.leafCategoryCode) || null)
const attributes = computed(() => selectedLeaf.value?.attributes || [])
const formFingerprint = computed(() => JSON.stringify(baseCommand()))
const canExecute = computed(() => Boolean(preflightRequestId.value && preflightRequestId.value === publishRequestId.value && publishFingerprint.value === formFingerprint.value && preflightResult.value?.valid !== false))
const structureErrors = computed<Array<Record<string, any>>>(() =>
  (localValidation.value?.fieldErrors || []).filter((item: Record<string, any>) => item.code !== 'PROHIBITED_TERM')
)
const adapterWarnings = computed(() => {
  const warnings: string[] = []
  const features = selectedChannel.value?.features || {}
  if (form.skus.length && !['READY', 'SUPPORTED'].includes(String(features.sku))) warnings.push('当前通道的多规格尚未验证，真实提交会被阻止')
  if (form.videoUrl) warnings.push('视频发布尚未取得真实通道适配证据')
  if (form.leafCategoryCode && schema.value?.source === 'LOCAL_REFERENCE') warnings.push('叶子类目来自本地参考目录，必须以平台预检回读为准')
  return warnings
})
const executionBlockers = computed(() => {
  if (form.publishChannel === 'QA_LOCAL') return []
  const blockers: string[] = []
  const features = selectedChannel.value?.features || {}
  if (form.skus.length && !['READY', 'SUPPORTED'].includes(String(features.sku))) blockers.push('多规格 SKU')
  if (form.videoUrl) blockers.push('商品视频')
  if (form.originalPrice !== undefined && form.originalPrice !== null && String(form.originalPrice) !== '') blockers.push('原价/划线价')
  if (form.outerId) blockers.push('商家编码')
  if (form.leafCategoryCode) blockers.push('指定叶子类目')
  if (Object.values(form.categoryAttributes || {}).some(value => String(value || '').trim())) blockers.push('类目属性')
  if (form.serviceProtocols.length) blockers.push('服务协议')
  if (form.businessMode !== 'NORMAL') blockers.push('鱼小铺/官方授权身份')
  if (form.productType === 'PHYSICAL' && form.conditionCode) blockers.push('实物成色')
  return blockers
})
const publishAddress = computed<PublishAddress>({
  get: () => ({ province: form.province, city: form.city, district: form.district, divisionId: form.divisionId, gps: form.gps, poiId: form.poiId, poiName: form.poiName }),
  set: value => Object.assign(form, value)
})

const deliveryMethod = () => ({ ONLINE_DELIVERY: '线上交付', FACE_TO_FACE: '当面交易', FREE_SHIPPING: '快递发货', FREIGHT_TEMPLATE: '快递发货', SELF_PICKUP: '当面交易', REMOTE_SERVICE: '远程服务', ON_SITE_SERVICE: '上门服务', STORE_SERVICE: '到店服务' }[String(form.shippingMode)] || '待选择')
const baseCommand = () => {
  const { imagesText: _imagesText, ...fields } = form
  return { ...fields, images: images.value, deliveryMethod: deliveryMethod(), freeShipping: form.shippingMode === 'FREE_SHIPPING' }
}
const command = (withRequest = true, withPreviewToken = false) => ({
  ...baseCommand(),
  requestId: withRequest ? currentRequestId() : undefined,
  draftId: withRequest ? (draftId.value || undefined) : undefined,
  draftRevision: withRequest && draftId.value ? draftRevision.value : undefined,
  previewToken: withPreviewToken ? preflightResult.value?.previewToken : undefined
})

const loadBase = async () => {
  const [accountResult, materialResult] = await Promise.all([getAccountList(), getResources('MATERIAL', 1)])
  accounts.value = accountResult.data?.accounts || []
  materials.value = materialResult.data || []
  form.xianyuAccountId ||= accounts.value[0]?.id || 0
  await loadSchema()
}

let schemaSerial = 0
const loadSchema = async () => {
  const serial = ++schemaSerial
  const accountId = Number(form.xianyuAccountId)
  if (!accountId) return
  schemaLoading.value = true
  schemaError.value = ''
  try {
    const response = await getListingFormSchema(accountId, form.productType)
    if (serial !== schemaSerial) return
    schema.value = response.data || null
    if (!schema.value) throw new Error('发布字段目录为空')
    form.catalogVersion = schema.value.catalogVersion
    const available = channels.value.find(item => item.available)
    if (!channels.value.some(item => item.channelCode === form.publishChannel && item.available)) form.publishChannel = String(available?.channelCode || '')
    if (!schema.value.conditions.some(item => item.value === form.conditionCode)) form.conditionCode = schema.value.conditions[0]?.value || ''
    if (!schema.value.shippingModes.some(item => item.value === form.shippingMode)) form.shippingMode = schema.value.shippingModes[0]?.value || ''
    await refreshDrafts()
  } catch (error: any) {
    if (serial === schemaSerial) schemaError.value = error?.message || '发布目录加载失败'
  } finally { if (serial === schemaSerial) schemaLoading.value = false }
}
const refreshDrafts = async () => { if (form.xianyuAccountId) drafts.value = (await getListingDrafts(form.xianyuAccountId)).data || [] }

watch(() => form.xianyuAccountId, () => { invalidatePreflight(); void loadSchema() })
watch(() => form.productType, () => {
  if (hydratingDraft.value) return
  form.skuDimensions = []; form.skus = []; form.industryCode = ''; form.leafCategoryCode = ''; form.categoryAttributes = {}
  invalidatePreflight(); void loadSchema()
})
watch(() => form.industryCode, () => {
  if (!leafCategories.value.some(item => item.code === form.leafCategoryCode)) { form.leafCategoryCode = ''; form.leafCategoryName = ''; form.categoryAttributes = {} }
})
watch(() => form.leafCategoryCode, value => {
  const leaf = leafCategories.value.find(item => item.code === value)
  form.leafCategoryName = leaf?.name || ''
  const next: Record<string, string> = {}
  leaf?.attributes.forEach(item => { next[item.code] = form.categoryAttributes[item.code] || '' })
  form.categoryAttributes = next
})

const useMaterial = (event: Event) => {
  const material = materials.value.find(item => item.id === Number((event.target as HTMLSelectElement).value))
  if (!material) return
  form.name = String(material.data?.title || material.name || '')
  form.description = String(material.data?.description || '')
  form.amount = Number(material.amount || 0); form.stock = Number(material.stock || 1)
  form.imagesText = Array.isArray(material.data?.images) ? material.data.images.join('\n') : ''
  invalidatePreflight()
}
const loadDraft = async (event: Event) => {
  const draft = drafts.value.find(item => Number(item.id) === Number((event.target as HTMLSelectElement).value))
  if (!draft) return
  hydratingDraft.value = true
  Object.assign(form, draft.payload || {})
  if (Array.isArray(draft.payload?.images)) form.imagesText = draft.payload.images.join('\n')
  draftId.value = Number(draft.id); draftRevision.value = Number(draft.revision || 1)
  draftSaveState.value = 'SAVED'; draftSavedAt.value = draft.updatedAt ? new Date(draft.updatedAt) : new Date()
  await nextTick()
  hydratingDraft.value = false
  await loadSchema()
  draftVersions.value = (await getListingDraftVersions(draftId.value)).data || []
  invalidatePreflight(); toast.success('商品草稿已载入')
}
const saveDraft = async (source: 'AUTO_SAVE' | 'MANUAL_SAVE' = 'MANUAL_SAVE') => {
  if (draftSaveState.value === 'SAVING' || !form.xianyuAccountId) return
  draftSaveState.value = 'SAVING'
  if (source === 'MANUAL_SAVE') loading.value = true
  try {
    const payload = command(false)
    const requestId = newRequestId(source === 'AUTO_SAVE' ? 'listing-auto' : 'listing-save')
    const result = draftId.value
      ? await updateListingDraft(draftId.value, draftRevision.value, payload, requestId, source)
      : await createListingDraft(payload, requestId, source)
    draftId.value = Number(result.data?.id || draftId.value); draftRevision.value = Number(result.data?.revision || draftRevision.value || 1)
    if (preflightRequestId.value) invalidatePreflight()
    draftSavedAt.value = new Date(); draftSaveState.value = 'SAVED'
    await refreshDrafts()
    if (draftId.value) draftVersions.value = (await getListingDraftVersions(draftId.value)).data || []
    if (source === 'MANUAL_SAVE') toast.success('草稿已保存；尚未向闲鱼提交')
  } catch (error: any) {
    draftSaveState.value = 'ERROR'
    if (source === 'MANUAL_SAVE') toast.error(error?.message || '草稿保存失败')
  } finally { if (source === 'MANUAL_SAVE') loading.value = false }
}

let autosaveTimer: ReturnType<typeof setTimeout> | undefined
watch(form, () => {
  if (!readyForAutosave.value || hydratingDraft.value) return
  invalidatePreflight()
  clearTimeout(autosaveTimer)
  if (!String(form.name || '').trim() && !String(form.description || '').trim()) return
  autosaveTimer = setTimeout(() => { void saveDraft('AUTO_SAVE') }, 1600)
}, { deep: true })

const invalidatePreflight = () => {
  preflightResult.value = null; preflightRequestId.value = ''; publishResult.value = null
  publishRequestId.value = ''; publishFingerprint.value = ''
}
const currentRequestId = () => {
  const fingerprint = formFingerprint.value
  if (!publishRequestId.value || publishFingerprint.value !== fingerprint) {
    publishRequestId.value = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`
    publishFingerprint.value = fingerprint
  }
  return publishRequestId.value
}
const localCheck = async () => {
  localValidation.value = (await validateListingDraft(command(false))).data || null
  if (!localValidation.value?.valid) { activeSection.value = 'review'; toast.error(localValidation.value?.errors?.[0] || '请先修正表单问题'); return false }
  return true
}
const preflight = async () => {
  loading.value = true
  try {
    if (!await localCheck()) return
    const response = await preflightPublish(command(true))
    preflightResult.value = response.data || null; preflightRequestId.value = publishRequestId.value; activeSection.value = 'review'
    toast.success(response.data?.platform?.executionChannel === 'QA_MOCK' ? '隔离预检通过，未调用闲鱼平台' : '平台预检通过，请核对最终请求')
  } catch (error: any) { toast.error(error?.message || '发布前校验失败') } finally { loading.value = false }
}
const publish = async () => {
  if (!canExecute.value) return toast.error('内容已变化，请重新执行发布前校验')
  if (executionBlockers.value.length) return toast.error(`当前通道尚未验证：${executionBlockers.value.join('、')}；为避免静默丢字段，已阻止真实提交`)
  try {
    await showConfirm([`店铺：${selectedAccount.value?.accountNote || selectedAccount.value?.unb || form.xianyuAccountId}`, `发布身份：${selectedChannel.value?.channelName || form.publishChannel} / ${schema.value?.businessModes.find(item => item.value === form.businessMode)?.label || form.businessMode}`, `商品：${form.name} · ¥${Number(form.amount).toFixed(2)} · 库存 ${form.stock}`, `媒体：${images.value.length} 张图片 · SKU ${form.skus.length || 1} 个`, form.publishChannel === 'QA_LOCAL' ? '隔离验收：不会调用闲鱼网络。' : '确认后将向闲鱼提交真实发布请求。'].join('\n'), form.publishChannel === 'QA_LOCAL' ? '确认隔离发布' : '确认发布商品')
  } catch { return }
  loading.value = true
  try {
    const response = await executePublish(command(true, true)); publishResult.value = response.data || null
    toast.success(response.data?.platform?.executionChannel === 'QA_MOCK' ? '隔离任务执行成功，未写入闲鱼' : '平台已确认发布结果')
  } catch (error: any) { toast.error(error?.message || '发布请求失败') } finally { loading.value = false }
}
const refreshStatus = async () => { if (publishRequestId.value) publishResult.value = (await getPublishingRequestStatus(publishRequestId.value)).data || null }
const statusText = (value: unknown) => ({ READY: '可用', SUPPORTED: '支持', PARTIAL: '部分能力', MOCK_ONLY: '隔离模拟', NOT_VERIFIED: '未验证', UNKNOWN: '未知', UNAVAILABLE: '不可用', REQUIRES_PLATFORM_PERMISSION: '需平台权限', NOT_CONNECTED: '未接入', AUTHORIZED: '已授权', NOT_APPLICABLE: '无需授权' }[String(value)] || String(value || '未同步'))
const verificationText = (value: unknown, qa = false) => ({
  VERIFIED: qa ? '隔离夹具已核对' : '平台字段已回读',
  PENDING: '字段待人工核对',
  LOCAL_PENDING: '本地同步待修复',
  FAILED: '字段核对失败',
  UNKNOWN: '结果未知'
}[String(value)] || '尚未核对')
const differenceStatusText = (value: unknown) => ({ SAME: '一致', DIFFERENT: '平台已调整', UNAVAILABLE: '未返回' }[String(value)] || String(value || '未知'))
const evidenceValue = (value: unknown) => {
  if (value === null || value === undefined || value === '') return '—'
  if (Array.isArray(value)) return value.length ? `${value.length} 项` : '—'
  const text = String(value)
  return text.length > 90 ? `${text.slice(0, 90)}…` : text
}
const channelTone = (channel: Record<string, any>) => channel.available ? 'ready' : channel.authorizationStatus === 'NOT_CONNECTED' ? 'missing' : 'blocked'
const scrollTo = (id: string) => { activeSection.value = id; document.getElementById(`publish-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
onMounted(async () => { await loadBase(); readyForAutosave.value = true })
onBeforeUnmount(() => clearTimeout(autosaveTimer))
</script>

<template>
  <main class="workbench publish-studio">
    <header class="workbench__header publish-studio__header">
      <div><span class="publish-studio__eyebrow">LISTING STUDIO</span><h1>商品发布工作台</h1><p>店铺身份、类目属性、内容媒体、规格库存与交付承诺共用一份结构化草稿，右侧实时还原买家视角。</p></div>
      <div class="publish-studio__header-actions"><span class="draft-state" :class="`draft-state--${draftSaveState.toLowerCase()}`">{{ draftSaveState === 'SAVING' ? '自动保存中…' : draftSaveState === 'SAVED' ? `已保存 r${draftRevision}` : draftSaveState === 'ERROR' ? '自动保存失败' : '尚未保存' }}</span><select class="workbench__select" aria-label="打开商品草稿" @change="loadDraft"><option value="">打开草稿（{{ drafts.length }}）</option><option v-for="item in drafts" :key="item.id" :value="item.id">{{ item.draftName }} · r{{ item.revision }}</option></select><button class="workbench__btn" type="button" :disabled="loading" @click="saveDraft('MANUAL_SAVE')">{{ draftId ? '更新草稿' : '保存草稿' }}</button><button class="workbench__btn workbench__btn--primary" type="button" :disabled="loading" @click="preflight">发布前校验</button></div>
    </header>
    <div class="publish-studio__mobile-switch" role="tablist" aria-label="发布编辑视图"><button type="button" :class="{ active: mobilePane === 'FORM' }" @click="mobilePane = 'FORM'">填写表单</button><button type="button" :class="{ active: mobilePane === 'PREVIEW' }" @click="mobilePane = 'PREVIEW'">买家预览</button></div>
    <div v-if="schemaError" class="publish-studio__error" role="alert"><span>{{ schemaError }}</span><button class="workbench__btn" @click="loadSchema">重试当前账号</button></div>
    <div class="publish-studio__shell" :class="`publish-studio__shell--${mobilePane.toLowerCase()}`">
      <nav class="publish-studio__nav" aria-label="发布表单章节"><button v-for="(item, index) in sections" :key="item.id" :class="{ active: activeSection === item.id }" @click="scrollTo(item.id)"><span>{{ index + 1 }}</span><div><strong>{{ item.label }}</strong><small>{{ item.hint }}</small></div></button></nav>
      <form class="publish-studio__form" @submit.prevent>
        <section id="publish-identity" class="publish-card" @focusin="activeSection = 'identity'">
          <header><div><span>01</span><h2>店铺与发布通道</h2></div><p>授权、类目、SKU、编辑和营销能力都必须有真实证据。</p></header>
          <div class="publish-grid publish-grid--two"><label class="workbench__field">发布店铺<select v-model="form.xianyuAccountId" class="workbench__select"><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }} · ID {{ account.id }}</option></select></label><label class="workbench__field">发布身份<select v-model="form.businessMode" class="workbench__select"><option v-for="item in schema?.businessModes || []" :key="item.value" :value="item.value">{{ item.label }} · {{ item.description }}</option></select></label></div>
          <div v-if="schemaLoading" class="publish-studio__loading" role="status">正在读取当前店铺的连接、授权与通道能力…</div>
          <div v-else class="channel-grid"><button v-for="channel in channels" :key="channel.channelCode" type="button" class="channel-card" :class="[`channel-card--${channelTone(channel)}`, { selected: form.publishChannel === channel.channelCode }]" :disabled="!channel.available" @click="form.publishChannel = channel.channelCode; invalidatePreflight()"><span class="channel-card__radio"></span><div><strong>{{ channel.channelName }}</strong><small>{{ channel.channelCode }} · {{ channel.source || '来源未同步' }}</small></div><em>{{ channel.available ? '可选择' : statusText(channel.authorizationStatus || channel.connectionStatus) }}</em><p>{{ channel.reason || `最近核验：${channel.lastCheckedTime ? new Date(channel.lastCheckedTime).toLocaleString('zh-CN') : '未同步'}` }}</p></button></div>
          <div v-if="selectedChannel" class="capability-matrix"><span v-for="(value, key) in selectedChannel.features" :key="key"><small>{{ key }}</small><strong>{{ statusText(value) }}</strong></span></div>
        </section>

        <section id="publish-category" class="publish-card" @focusin="activeSection = 'category'">
          <header><div><span>02</span><h2>商品类型与类目属性</h2></div><p>{{ schema?.notice || '平台目录读取中' }}</p></header>
          <div class="product-type-grid"><label :class="{ selected: form.productType === 'PHYSICAL' }"><input v-model="form.productType" type="radio" value="PHYSICAL"><strong>实物商品</strong><small>快递、包邮/运费模板、成色和所在地</small></label><label :class="{ selected: form.productType === 'VIRTUAL' }"><input v-model="form.productType" type="radio" value="VIRTUAL"><strong>虚拟商品</strong><small>软件、卡密与数字内容，记录有效期和交付方式</small></label><label :class="{ selected: form.productType === 'SERVICE' }"><input v-model="form.productType" type="radio" value="SERVICE"><strong>服务商品</strong><small>远程、上门或到店，记录时长、预约和服务范围</small></label></div>
          <div class="publish-grid publish-grid--three"><label class="workbench__field">行业<select v-model="form.industryCode" class="workbench__select"><option value="">请选择行业</option><option v-for="item in industries" :key="item.code" :value="item.code">{{ item.name }}</option></select></label><label class="workbench__field">叶子类目<select v-model="form.leafCategoryCode" class="workbench__select" :disabled="!selectedIndustry"><option value="">请选择最末级类目</option><option v-for="item in leafCategories" :key="item.code" :value="item.code">{{ item.name }}</option></select><small>本地参考目录 · 平台预检回读确认</small></label><label class="workbench__field">成色 / 交付性质<select v-model="form.conditionCode" class="workbench__select"><option v-for="item in schema?.conditions || []" :key="item.value" :value="item.value">{{ item.label }}</option></select></label></div>
          <div v-if="attributes.length" class="attribute-panel"><header><strong>类目属性</strong><span>标记 * 的字段用于预检完整性</span></header><div class="publish-grid publish-grid--two"><label v-for="item in attributes" :key="item.code" class="workbench__field">{{ item.name }}{{ item.required ? ' *' : '' }}<select v-if="item.options.length" v-model="form.categoryAttributes[item.code]" class="workbench__select"><option value="">请选择</option><option v-for="option in item.options" :key="option" :value="option">{{ option }}</option></select><input v-else v-model="form.categoryAttributes[item.code]" class="workbench__input" maxlength="100" placeholder="请输入"></label></div></div>
        </section>

        <section id="publish-content" class="publish-card" @focusin="activeSection = 'content'">
          <header><div><span>03</span><h2>商品内容与媒体</h2></div><p>正文不使用网页字体冒充平台能力，字体样式应固化到有授权的商品图片中。</p></header>
          <label class="workbench__field">复用素材<select class="workbench__select" @change="useMaterial"><option value="">从素材库选择（可选）</option><option v-for="item in materials" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
          <label class="workbench__field">商品标题<input v-model="form.name" class="workbench__input" maxlength="120" placeholder="说明商品是什么、适合谁、核心差异"><small>{{ form.name.length }} / 120</small></label>
          <label class="workbench__field">商品详情<textarea v-model="form.description" class="workbench__textarea publish-studio__description" maxlength="3000" placeholder="建议依次说明：功能价值、适用范围、交付方式、使用教程、售后方式与有效期"></textarea><small>{{ form.description.length }} / 3000</small></label>
          <label class="workbench__field">商品图片（1–{{ schema?.limits.images || 9 }} 张）<MediaUploader v-model="images" :account-id="form.xianyuAccountId" :max="schema?.limits.images || 9" label="上传商品图片" /><small>支持排序、删除、失败重试；第一张作为封面并同步到右侧预览。</small></label>
          <label class="workbench__field">图片地址（每行一张）<textarea v-model="form.imagesText" class="workbench__textarea" maxlength="6000" placeholder="支持 HTTPS 或本地媒体地址"></textarea></label>
          <label class="workbench__field">商品视频（可选）<MediaUploader v-model="videos" :account-id="form.xianyuAccountId" :max="1" accept="video" label="上传商品视频" /><small class="publish-studio__degraded">安全降级：视频可保存到草稿和素材版本，只有通道能力验证后才会提交平台。</small></label>
        </section>

        <section id="publish-price" class="publish-card" @focusin="activeSection = 'price'">
          <header><div><span>04</span><h2>售价、库存与规格型号</h2></div><p>平台库存和本地可交付库存分开核对，多 SKU 不会被降级成单品静默提交。</p></header>
          <div class="publish-grid publish-grid--four"><label class="workbench__field">售价<input v-model.number="form.amount" class="workbench__input" type="number" min="0.01" step="0.01" placeholder="0.00"></label><label class="workbench__field">原价 / 划线价<input v-model.number="form.originalPrice" class="workbench__input" type="number" min="0.01" step="0.01" placeholder="可选"></label><label class="workbench__field">平台库存<input v-model.number="form.stock" class="workbench__input" type="number" min="1" step="1"></label><label class="workbench__field">商家编码 outerId<input v-model="form.outerId" class="workbench__input" maxlength="64" placeholder="便于内部检索"></label></div>
          <ListingSkuEditor v-model:dimensions="form.skuDimensions" v-model:skus="form.skus" :base-price="Number(form.amount || 0)" :base-stock="Number(form.stock || 1)" />
        </section>

        <section id="publish-trade" class="publish-card" @focusin="activeSection = 'trade'">
          <header><div><span>05</span><h2>交易、位置与服务承诺</h2></div><p>只承诺真实可履约的服务，协议、余额和类目资格仍需平台预检。</p></header>
          <div class="publish-grid publish-grid--two"><label class="workbench__field">交付 / 运费方式<select v-model="form.shippingMode" class="workbench__select"><option v-for="item in schema?.shippingModes || []" :key="item.value" :value="item.value">{{ item.label }} · {{ item.description }}</option></select></label><label v-if="form.shippingMode === 'FREIGHT_TEMPLATE'" class="workbench__field">运费模板 ID<input v-model="form.freightTemplateId" class="workbench__input" placeholder="平台运费模板 ID"></label></div>
          <div v-if="form.productType === 'VIRTUAL'" class="publish-grid publish-grid--three publish-type-fields"><label class="workbench__field">数字履约方式<select v-model="form.fulfillmentMode" class="workbench__select"><option value="AUTO_DELIVERY">自动发货</option><option value="MANUAL_DIGITAL">人工线上交付</option><option value="GROUP_SUPPORT">售后群内交付</option></select></label><label class="workbench__field">有效期（天）<input v-model.number="form.validityDays" class="workbench__input" type="number" min="1" step="1"></label><label class="workbench__field">售后与安装说明<input v-model="form.supportPolicy" class="workbench__input" maxlength="500" placeholder="如：群内提供视频教程与人工指导"></label></div>
          <div v-else-if="form.productType === 'SERVICE'" class="publish-grid publish-grid--four publish-type-fields"><label class="workbench__field">服务时长（分钟）<input v-model.number="form.serviceDurationMinutes" class="workbench__input" type="number" min="1" step="1"></label><label class="workbench__field">至少提前预约（小时）<input v-model.number="form.appointmentLeadHours" class="workbench__input" type="number" min="0" step="1"></label><label class="workbench__field">服务范围<input v-model="form.serviceArea" class="workbench__input" maxlength="200" :placeholder="form.shippingMode === 'ON_SITE_SERVICE' ? '上门服务必填' : '可填写城市或线上范围'"></label><label class="workbench__field">改期 / 售后说明<input v-model="form.supportPolicy" class="workbench__input" maxlength="500" placeholder="说明改期、取消和售后规则"></label></div>
          <label v-else class="workbench__field publish-type-fields">实物售后说明<textarea v-model="form.afterSalesPolicy" class="workbench__textarea" maxlength="500" placeholder="说明退换、保修、瑕疵和签收注意事项"></textarea></label>
          <p class="type-requirement-note">{{ schema?.typeRequirements?.notice }}</p>
          <div class="publish-address"><PublishAddressFields v-model="publishAddress" /></div>
          <div class="service-grid"><label v-for="service in schema?.serviceProtocols || []" :key="service.code" :class="{ selected: form.serviceProtocols.includes(service.code) }"><input v-model="form.serviceProtocols" type="checkbox" :value="service.code"><span><strong>{{ service.label }}</strong><small>{{ service.description }}</small></span><em>{{ statusText(service.status) }}</em></label></div>
        </section>

        <section id="publish-review" class="publish-card" @focusin="activeSection = 'review'">
          <header><div><span>06</span><h2>风险校验与最终发布</h2></div><p>草稿预览、平台预检和发布结果是三个不同状态。</p></header>
          <div v-if="adapterWarnings.length" class="publish-studio__warnings"><strong>能力边界</strong><ul><li v-for="item in adapterWarnings" :key="item">{{ item }}</li></ul></div>
          <div v-if="executionBlockers.length" class="publish-studio__warnings"><strong>真实提交阻断项</strong><ul><li v-for="item in executionBlockers" :key="item">{{ item }}：可保存草稿与执行平台预检，真实提交需等待通道能力验证。</li></ul></div>
          <div v-if="localValidation" class="validation-result" :class="{ invalid: !localValidation.valid }"><header><strong>{{ localValidation.valid ? '本地结构校验通过' : '需要修正表单' }}</strong><span>{{ localValidation.catalogVersion }} · {{ localValidation.source }}</span></header><ul v-if="structureErrors.length"><li v-for="item in structureErrors" :key="`${item.field}-${item.code}`"><button type="button" @click="scrollTo(item.field.startsWith('category') || item.field.includes('Category') || item.field === 'industryCode' ? 'category' : item.field.startsWith('sku') || ['amount','originalPrice','stock','outerId'].includes(item.field) ? 'price' : ['shippingMode','supportPolicy','afterSalesPolicy','validityDays','serviceArea','serviceDurationMinutes','appointmentLeadHours'].includes(item.field) ? 'trade' : 'content')">{{ item.message }}</button><code>{{ item.field }}</code></li></ul><ul v-if="localValidation.warnings?.length"><li v-for="item in localValidation.warnings" :key="item">{{ item }}</li></ul></div>
          <section v-if="localValidation?.contentPolicy" class="content-risk" :class="{ 'content-risk--blocked': !localValidation.contentPolicy.valid }" aria-live="polite"><header><div><strong>{{ localValidation.contentPolicy.valid ? '内容风险检查通过' : `发现 ${localValidation.contentPolicy.blockerCount} 项内容风险` }}</strong><p>{{ localValidation.contentPolicy.summary }}</p></div><span>{{ localValidation.contentPolicy.policyVersion }} · {{ localValidation.contentPolicy.checkedFieldCount }} 个字段</span></header><ul v-if="localValidation.contentPolicy.findings?.length"><li v-for="item in localValidation.contentPolicy.findings" :key="`${item.field}-${item.term}`"><button type="button" @click="scrollTo(item.field.startsWith('category') ? 'category' : item.field.startsWith('sku') ? 'price' : ['supportPolicy','afterSalesPolicy','serviceArea'].includes(item.field) ? 'trade' : 'content')"><span>{{ item.fieldLabel }}</span><strong>{{ item.term }}</strong><small>{{ item.snippet }}</small></button><p>{{ item.suggestion }}</p></li></ul><footer><span>来源：系统内容策略 · 检查于 {{ localValidation.contentPolicy.checkedAt ? new Date(localValidation.contentPolicy.checkedAt).toLocaleString('zh-CN') : '—' }}</span><b>{{ localValidation.contentPolicy.nextAction }}</b></footer></section>
          <div v-if="preflightResult" class="publish-evidence"><header><strong>{{ preflightResult.platform?.executionChannel === 'QA_MOCK' ? '隔离平台预检' : '真实平台预检' }}</strong><span>{{ preflightResult.outcomeState }}</span></header><dl><div><dt>请求 ID</dt><dd>{{ preflightResult.requestId }}</dd></div><div><dt>发布通道</dt><dd>{{ preflightResult.platform?.publishChannel || form.publishChannel }}</dd></div><div><dt>平台类目</dt><dd>{{ preflightResult.platform?.category?.catName || preflightResult.platform?.category?.categoryName || '未返回' }}</dd></div><div><dt>目录版本</dt><dd>{{ preflightResult.catalogVersion }}</dd></div><div><dt>预检凭证</dt><dd>{{ preflightResult.previewToken }}</dd></div><div><dt>有效期至</dt><dd>{{ preflightResult.expiresAt ? new Date(preflightResult.expiresAt).toLocaleString('zh-CN') : '未返回' }}</dd></div><div><dt>载荷指纹</dt><dd>{{ preflightResult.payloadFingerprint }}</dd></div><div><dt>平台差异</dt><dd>{{ preflightResult.platformDifferences?.status || '未比对' }}</dd></div></dl><ul v-if="preflightResult.platformDifferences?.items?.length" class="publish-evidence__diff"><li v-for="item in preflightResult.platformDifferences.items" :key="item.field"><strong>{{ item.field }}</strong><span>{{ item.message }}</span></li></ul><p>{{ preflightResult.platform?.previewUsesProductionBuilder ? '预览与提交共用生产请求构造器。' : '隔离预检未调用生产构造器，也不会访问闲鱼网络。' }}</p></div>
          <div v-if="publishResult" class="publish-evidence publish-evidence--result">
            <header><strong>发布结果证据</strong><span>{{ publishResult.outcomeState || publishResult.status }}</span></header>
            <dl>
              <div><dt>任务 ID</dt><dd>{{ publishResult.taskId || '未生成' }}</dd></div>
              <div><dt>商品 ID</dt><dd>{{ publishResult.platform?.itemId || publishResult.material?.xyGoodsId || '未确认' }}</dd></div>
              <div><dt>数据来源</dt><dd>{{ publishResult.dataSource || publishResult.platform?.dataSource || publishResult.task?.dataSource || (form.publishChannel === 'QA_LOCAL' ? 'QA_FIXTURE' : '未同步') }}</dd></div>
              <div><dt>平台写入</dt><dd>{{ publishResult.platform?.platformWrite || (form.publishChannel === 'QA_LOCAL' ? 'NOT_PERFORMED' : '待回读') }}</dd></div>
              <div><dt>字段核对</dt><dd>{{ verificationText(publishResult.verificationStatus || publishResult.platform?.verificationStatus, publishResult.platform?.executionChannel === 'QA_MOCK') }}</dd></div>
              <div><dt>本地记录</dt><dd>{{ publishResult.platform?.localSynced === false ? '待修复' : publishResult.platform?.localSynced === true ? '已同步' : '—' }}</dd></div>
              <template v-if="publishResult.platform?.platformReadBack">
                <div><dt>最终标题</dt><dd>{{ evidenceValue(publishResult.platform.platformReadBack.title) }}</dd></div>
                <div><dt>最终售价 / 库存</dt><dd>{{ publishResult.platform.platformReadBack.price != null ? `¥${publishResult.platform.platformReadBack.price}` : '—' }} / {{ evidenceValue(publishResult.platform.platformReadBack.stock) }}</dd></div>
                <div><dt>最终类目</dt><dd>{{ publishResult.platform.platformReadBack.categoryName || publishResult.platform.platformReadBack.categoryId || '—' }}</dd></div>
                <div><dt>最终图片</dt><dd>{{ publishResult.platform.platformReadBack.imageCount == null ? '—' : `${publishResult.platform.platformReadBack.imageCount} 张` }}</dd></div>
              </template>
            </dl>
            <ul v-if="publishResult.platform?.fieldDifferences?.items?.length" class="publish-evidence__diff publish-evidence__diff--verified">
              <li v-for="item in publishResult.platform.fieldDifferences.items" :key="item.field" :class="`difference--${String(item.status).toLowerCase()}`">
                <strong>{{ item.label || item.field }}</strong><span>{{ differenceStatusText(item.status) }} · {{ item.message }}</span>
                <small v-if="item.status !== 'SAME'">提交 {{ evidenceValue(item.requested) }} → 平台 {{ evidenceValue(item.actual) }}</small>
              </li>
            </ul>
            <p>{{ publishResult.idempotentReplay ? '同一请求已执行过，本次返回持久化结果，没有重复创建任务。' : (publishResult.recoveryHint || publishResult.platform?.recoveryHint || publishResult.error || '结果已持久化，可按请求 ID 查询。') }}</p>
            <button class="workbench__btn" type="button" @click="refreshStatus">按请求 ID 查询</button>
          </div>
          <details v-if="draftVersions.length" class="draft-versions"><summary>草稿版本历史（{{ draftVersions.length }}）</summary><ol><li v-for="version in draftVersions" :key="version.revision"><strong>r{{ version.revision }}</strong><span>{{ version.changeSource === 'AUTO_SAVE' ? '自动保存' : '手动保存' }}</span><time>{{ new Date(version.createdTime).toLocaleString('zh-CN') }}</time><code>{{ version.payloadFingerprint?.slice(0, 12) }}</code></li></ol></details>
          <footer class="publish-studio__review-actions"><button class="workbench__btn" type="button" :disabled="loading" @click="saveDraft('MANUAL_SAVE')">保存草稿</button><button class="workbench__btn" type="button" :disabled="loading" @click="preflight">{{ preflightResult ? '重新预检' : '发布前校验' }}</button><button class="workbench__btn workbench__btn--primary" type="button" :disabled="loading || !canExecute" @click="publish">确认发布</button></footer>
        </section>
      </form>
      <ListingPhonePreview :form="{ ...form, images }" :account-name="selectedAccount?.accountNote || selectedAccount?.unb" :channel-name="selectedChannel?.channelName" :category-name="form.leafCategoryName" />
    </div>
  </main>
</template>

<style scoped>
.publish-studio{--studio-border:#e4e0d7;--studio-muted:#77736b;max-width:1680px;margin:0 auto;padding:24px 28px 64px}.publish-studio__header{align-items:flex-end}.publish-studio__eyebrow{color:#a36a00;font-size:10px;font-weight:800;letter-spacing:.16em}.publish-studio__header-actions{display:flex;align-items:center;gap:9px}.publish-studio__header-actions .workbench__select{min-width:190px}.publish-studio__shell{display:grid;grid-template-columns:176px minmax(0,1fr) 360px;gap:18px;align-items:start;margin-top:20px}.publish-studio__nav{position:sticky;top:18px;display:grid;gap:4px}.publish-studio__nav button{display:grid;grid-template-columns:26px 1fr;align-items:center;gap:9px;padding:10px;border:0;border-radius:10px;color:#69655e;background:transparent;text-align:left;cursor:pointer}.publish-studio__nav button>span{display:grid;width:25px;height:25px;place-items:center;border-radius:50%;background:#ece9e2;font-size:10px;font-weight:800}.publish-studio__nav button div{display:grid}.publish-studio__nav strong{font-size:12px}.publish-studio__nav small{margin-top:2px;font-size:9px}.publish-studio__nav button.active{color:#3f3100;background:#fff5c9}.publish-studio__nav button.active>span{background:#ffd733}.publish-studio__form{display:grid;gap:14px;min-width:0}.publish-card{scroll-margin-top:18px;padding:20px;border:1px solid var(--studio-border);border-radius:16px;background:rgba(255,255,255,.92);box-shadow:0 8px 25px rgba(50,44,32,.045)}.publish-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:18px;padding-bottom:14px;border-bottom:1px solid #eeebe5}.publish-card>header>div{display:flex;align-items:center;gap:9px}.publish-card>header span{color:#ad7200;font-size:10px;font-weight:800}.publish-card h2{margin:0;font-size:18px}.publish-card>header p{max-width:56%;margin:0;color:var(--studio-muted);font-size:11px;line-height:1.5;text-align:right}.publish-grid{display:grid;gap:12px}.publish-grid--two{grid-template-columns:repeat(2,minmax(0,1fr))}.publish-grid--three{grid-template-columns:repeat(3,minmax(0,1fr))}.publish-grid--four{grid-template-columns:repeat(4,minmax(0,1fr))}.channel-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px}.channel-card{position:relative;display:grid;grid-template-columns:18px 1fr auto;gap:4px 8px;padding:13px;border:1px solid var(--studio-border);border-radius:12px;color:#403d38;background:#fff;text-align:left;cursor:pointer}.channel-card:disabled{cursor:not-allowed;opacity:.72}.channel-card.selected{border-color:#e2b500;box-shadow:0 0 0 2px #fff1a8}.channel-card__radio{width:14px;height:14px;margin-top:2px;border:1px solid #a8a49b;border-radius:50%}.channel-card.selected .channel-card__radio{border:4px solid #d6a900}.channel-card div{display:grid}.channel-card small{color:#8a867f;font-size:9px}.channel-card em{padding:3px 6px;border-radius:999px;font-size:9px;font-style:normal}.channel-card--ready em{color:#11633b;background:#e7f8ed}.channel-card--missing em{color:#835600;background:#fff1c2}.channel-card--blocked em{color:#8f3d34;background:#fff0ed}.channel-card p{grid-column:2/4;margin:5px 0 0;color:#77736b;font-size:10px;line-height:1.4}.capability-matrix{display:grid;grid-template-columns:repeat(auto-fit,minmax(90px,1fr));gap:7px;margin-top:10px;padding:10px;border-radius:11px;background:#f7f6f2}.capability-matrix span{display:grid;gap:2px}.capability-matrix small{color:#8b877f;font-size:9px;text-transform:uppercase}.capability-matrix strong{font-size:11px}.product-type-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-bottom:14px}.product-type-grid label{display:grid;grid-template-columns:18px 1fr;gap:3px 8px;padding:14px;border:1px solid var(--studio-border);border-radius:12px;cursor:pointer}.product-type-grid label.selected{border-color:#e1b300;background:#fffaf0}.product-type-grid input{grid-row:1/3;margin:3px 0}.product-type-grid small{color:#77736b;font-size:10px}.attribute-panel{margin-top:14px;padding:14px;border:1px solid #e5e1d9;border-radius:12px;background:#faf9f6}.attribute-panel>header{display:flex;justify-content:space-between;margin-bottom:12px}.attribute-panel>header span{color:#817d75;font-size:10px}.publish-studio__description{min-height:160px}.publish-studio__degraded{color:#9c6500!important}.publish-address{margin-top:12px}.service-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;margin-top:14px}.service-grid label{display:grid;grid-template-columns:18px 1fr auto;align-items:start;gap:8px;padding:12px;border:1px solid var(--studio-border);border-radius:11px;cursor:pointer}.service-grid label.selected{border-color:#dfb100;background:#fffaf0}.service-grid span{display:grid}.service-grid small{margin-top:3px;color:#77736b;font-size:10px}.service-grid em{color:#8d6700;font-size:9px;font-style:normal}.publish-studio__loading,.publish-studio__error{padding:14px;border-radius:11px;background:#f7f6f2;color:#65615a;font-size:12px}.publish-studio__error{display:flex;align-items:center;justify-content:space-between;margin-top:14px;border:1px solid #efb5ae;color:#912f28;background:#fff3f1}.publish-studio__warnings,.validation-result,.publish-evidence{margin-bottom:12px;padding:13px 14px;border:1px solid #ead188;border-radius:11px;color:#704d00;background:#fff9e5;font-size:11px}.publish-studio__warnings ul,.validation-result ul{margin:7px 0 0;padding-left:18px}.validation-result{border-color:#b8dfc5;color:#165d39;background:#f0faf3}.validation-result.invalid{border-color:#efb5ae;color:#912f28;background:#fff3f1}.validation-result header,.publish-evidence header{display:flex;justify-content:space-between}.publish-evidence{border-color:#d9d5cc;color:#494640;background:#f8f7f4}.publish-evidence dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:10px 0}.publish-evidence dt{color:#827e76;font-size:9px}.publish-evidence dd{overflow-wrap:anywhere;margin:2px 0 0;font-weight:700}.publish-evidence--result{border-color:#b8dfc5;background:#f1faf4}.publish-studio__review-actions{position:sticky;bottom:0;display:flex;justify-content:flex-end;gap:9px;margin:16px -20px -20px;padding:13px 20px;border-top:1px solid var(--studio-border);border-radius:0 0 16px 16px;background:rgba(255,255,255,.96);backdrop-filter:blur(8px)}
@media(max-width:1380px){.publish-studio__shell{grid-template-columns:150px minmax(0,1fr) 320px}.publish-studio{padding-inline:20px}.publish-grid--four{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:1100px){.publish-studio__shell{grid-template-columns:130px minmax(0,1fr)}.publish-studio__shell>.listing-preview{grid-column:2}.publish-studio__header{align-items:flex-start;flex-direction:column}.publish-studio__header-actions{width:100%;flex-wrap:wrap}}
@media(max-width:767px){.publish-studio{padding:14px 12px 48px}.publish-studio__shell{grid-template-columns:1fr}.publish-studio__nav{position:static;display:flex;overflow-x:auto;padding-bottom:4px}.publish-studio__nav button{min-width:132px}.publish-studio__form,.publish-studio__shell>.listing-preview{grid-column:1}.publish-card{padding:15px;border-radius:13px}.publish-card>header{align-items:flex-start;flex-direction:column;gap:6px}.publish-card>header p{max-width:none;text-align:left}.publish-grid--two,.publish-grid--three,.publish-grid--four,.product-type-grid,.service-grid{grid-template-columns:1fr}.publish-studio__header-actions .workbench__select{width:100%}.publish-studio__header-actions .workbench__btn{flex:1}.publish-studio__review-actions{margin:14px -15px -15px;padding:11px 15px max(11px,env(safe-area-inset-bottom))}.publish-studio__review-actions .workbench__btn{flex:1;padding-inline:8px}.publish-evidence dl{grid-template-columns:1fr}}
.publish-card>header>div{flex:1 1 auto;min-width:0}.publish-card h2{word-break:keep-all}.publish-card>header p{flex:0 1 46%;max-width:46%}
.draft-state{padding:5px 8px;border-radius:999px;color:#6c675f;background:#f0eee8;font-size:10px;white-space:nowrap}.draft-state--saving{color:#7a5600;background:#fff3c4}.draft-state--saved{color:#17603d;background:#e7f7ed}.draft-state--error{color:#922f28;background:#ffefed}.publish-studio__mobile-switch{display:none}.product-type-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.publish-type-fields{margin-top:14px}.type-requirement-note{margin:10px 0 0;color:#7b7469;font-size:11px}.validation-result li button{padding:0;border:0;color:inherit;background:transparent;text-align:left;text-decoration:underline;cursor:pointer}.validation-result li code{margin-left:7px;color:#8c8379;font-size:9px}.draft-versions{margin-top:12px;padding:12px;border:1px solid #e1ddd4;border-radius:11px;background:#faf9f6}.draft-versions summary{cursor:pointer;font-weight:700}.draft-versions ol{display:grid;gap:6px;margin:10px 0 0;padding:0;list-style:none}.draft-versions li{display:grid;grid-template-columns:42px 72px 1fr auto;gap:8px;align-items:center;font-size:10px}.draft-versions time{color:#77736b}.draft-versions code{color:#8a6500}
.content-risk{display:grid;gap:12px;margin-bottom:12px;padding:14px;border:1px solid #b8dfc5;border-radius:12px;color:#165d39;background:#f0faf3}.content-risk--blocked{border-color:#ec9d91;color:#842b24;background:#fff3f1}.content-risk>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.content-risk>header div{display:grid;gap:3px}.content-risk>header p,.content-risk li p{margin:0;font-size:11px;line-height:1.5}.content-risk>header>span,.content-risk>footer span{color:#77736b;font-size:10px}.content-risk ul{display:grid;gap:8px;margin:0;padding:0;list-style:none}.content-risk li{display:grid;gap:6px;padding:10px;border:1px solid currentColor;border-radius:9px;background:rgba(255,255,255,.68)}.content-risk li button{display:grid;grid-template-columns:auto auto minmax(0,1fr);align-items:center;gap:8px;padding:0;border:0;color:inherit;background:transparent;text-align:left;cursor:pointer}.content-risk li button span{font-size:10px;font-weight:800}.content-risk li button strong{padding:3px 6px;border-radius:999px;background:rgba(155,44,44,.1);font-size:10px}.content-risk li button small{min-width:0;overflow:hidden;color:#6f6860;text-overflow:ellipsis;white-space:nowrap}.content-risk>footer{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;padding-top:10px;border-top:1px solid currentColor}.content-risk>footer b{font-size:11px;text-align:right}
@media(max-width:767px){.publish-studio__mobile-switch{display:grid;grid-template-columns:1fr 1fr;margin-top:14px;padding:3px;border-radius:10px;background:#ece9e2}.publish-studio__mobile-switch button{padding:9px;border:0;border-radius:8px;background:transparent;font-weight:700}.publish-studio__mobile-switch button.active{background:#fff;box-shadow:0 2px 8px rgba(50,44,32,.1)}.publish-studio__shell{margin-top:10px}.publish-studio__shell--preview .publish-studio__nav,.publish-studio__shell--preview .publish-studio__form{display:none}.publish-studio__shell--form>.listing-preview{display:none}.draft-state{width:100%;text-align:center}.draft-versions li{grid-template-columns:36px 64px 1fr}.draft-versions code{display:none}}
@media(max-width:767px){.content-risk>header,.content-risk>footer{display:grid}.content-risk>footer b{text-align:left}.content-risk li button{grid-template-columns:auto 1fr}.content-risk li button small{grid-column:1/-1;white-space:normal}}
.publish-evidence__diff--verified{display:grid;gap:6px;margin:10px 0;padding:0;list-style:none}.publish-evidence__diff--verified li{display:grid;grid-template-columns:90px minmax(0,1fr);gap:3px 10px;padding:8px 10px;border-radius:8px;background:rgba(255,255,255,.72)}.publish-evidence__diff--verified li strong{font-size:11px}.publish-evidence__diff--verified li span{color:#5f5b54}.publish-evidence__diff--verified li small{grid-column:2;color:#8a5b00;overflow-wrap:anywhere}.publish-evidence__diff--verified .difference--different{border-left:3px solid #d89b00}.publish-evidence__diff--verified .difference--unavailable{border-left:3px solid #cf5d50}
@media(max-width:767px){.publish-evidence__diff--verified li{grid-template-columns:1fr}.publish-evidence__diff--verified li small{grid-column:1}}
</style>
