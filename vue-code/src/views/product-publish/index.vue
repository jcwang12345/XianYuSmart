<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { getAccountList } from '@/api/account'
import { getResources, type MerchantResource } from '@/api/merchant'
import { createListingDraft, executePublish, getListingDrafts, getListingDraftVersions, getListingFormSchema, getPublishingRequestStatus, newRequestId, preflightPublish, updateListingDraft, validateListingDraft, type ListingFormSchema } from '@/api/matrix'
import PublishAddressFields from '@/components/PublishAddressFields.vue'
import MediaUploader from '@/components/MediaUploader.vue'
import ListingSkuEditor from '@/components/product/ListingSkuEditor.vue'
import ListingPhonePreview from '@/components/product/ListingPhonePreview.vue'
import { isConfirmedPublish, publishResultLabel } from './publish-result'
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
const preflightError = ref('')
const publishResult = ref<Record<string, any> | null>(null)
const preflightRequestId = ref('')
const publishRequestId = ref('')
const publishFingerprint = ref('')
const addressEditing = ref(false)
const showAdvancedForm = ref(false)
const addressDraft = ref<PublishAddress>({ province: '', city: '', district: '', divisionId: '', gps: '', poiId: '', poiName: '' })

const emptyForm = (): Record<string, any> => ({
  xianyuAccountId: 0, publishChannel: '', productType: 'VIRTUAL', businessMode: 'NORMAL',
  name: '', description: '', amount: 0, originalPrice: undefined, stock: 1, outerId: '',
  conditionCode: 'DIGITAL', industryCode: '', leafCategoryCode: '', leafCategoryName: '', categoryAttributes: {},
  categoryMode: 'SPECIFIED_CATALOG', confirmedPlatformCategoryId: '',
  shippingMode: 'ONLINE_DELIVERY', shippingFee: undefined, freightTemplateId: '',
  province: '', city: '', district: '', divisionId: '', gps: '', poiId: '', poiName: '',
  imagesText: '', videoUrl: '', skuDimensions: [] as Dimension[], skus: [] as Sku[], serviceProtocols: [] as string[],
  fulfillmentMode: '', validityDays: undefined, supportPolicy: '', afterSalesPolicy: '',
  serviceDurationMinutes: 60, appointmentLeadHours: 2, serviceArea: '', catalogVersion: ''
})
const form = reactive<Record<string, any>>(emptyForm())

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
const basicCategoryMode = computed(() => form.categoryMode === 'BASIC_PLATFORM_RECOMMENDED')
const formFingerprint = computed(() => JSON.stringify(baseCommand()))
const canExecute = computed(() => Boolean(preflightResult.value?.previewToken && preflightRequestId.value && preflightRequestId.value === publishRequestId.value && publishFingerprint.value === formFingerprint.value && preflightResult.value?.valid === true && selectedChannel.value?.executionAvailable !== false))
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
const simpleVirtualQr = computed(() => form.productType === 'VIRTUAL'
  && form.publishChannel === 'QR_COOKIE' && basicCategoryMode.value
  && executionBlockers.value.length === 0 && !showAdvancedForm.value)
const visibleSections = computed(() => simpleVirtualQr.value
  ? sections.filter(item => ['identity', 'content', 'price', 'review'].includes(item.id)) : sections)
const hasExplicitAddress = computed(() => Boolean(form.province || form.city || form.district || form.divisionId || form.gps))
const publishAddress = computed<PublishAddress>({
  get: () => ({ province: form.province, city: form.city, district: form.district, divisionId: form.divisionId, gps: form.gps, poiId: form.poiId, poiName: form.poiName }),
  set: value => Object.assign(form, value)
})
const beginAddressEdit = () => {
  addressDraft.value = { ...publishAddress.value }
  addressEditing.value = true
}
const applyAddressEdit = () => {
  if (!addressDraft.value.divisionId || !addressDraft.value.province || !addressDraft.value.city || !addressDraft.value.district) {
    toast.error('请选择完整的省、市、区后再使用此位置')
    return
  }
  publishAddress.value = { ...addressDraft.value }
  addressEditing.value = false
}
const useAccountAddress = () => {
  publishAddress.value = { province: '', city: '', district: '', divisionId: '', gps: '', poiId: '', poiName: '' }
  addressEditing.value = false
}

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
  try {
    const [accountResult, materialResult] = await Promise.all([
      getAccountList(),
      getResources('MATERIAL', 1).catch(() => null)
    ])
    accounts.value = accountResult.data?.accounts || []
    materials.value = materialResult?.data || []
    form.xianyuAccountId ||= accounts.value[0]?.id || 0
    if (!form.xianyuAccountId) { schemaError.value = '暂无可发布的账号，请先在账号管理连接账号'; return }
    await loadSchema()
  } catch (error: any) { schemaError.value = error?.message || '发布账号加载失败，请稍后重试' }
}

const applyEmptyQrDefault = () => {
  if (form.publishChannel !== 'QR_COOKIE' || draftId.value || hydratingDraft.value
      || form.name || form.description || form.imagesText || form.leafCategoryCode
      || form.industryCode || Object.keys(form.categoryAttributes || {}).length
      || form.skus.length || form.skuDimensions.length || form.videoUrl || form.outerId
      || Number(form.amount) > 0 || form.originalPrice != null || Number(form.stock) !== 1
      || form.supportPolicy || form.afterSalesPolicy) return
  form.categoryMode = 'BASIC_PLATFORM_RECOMMENDED'
}
let schemaSerial = 0
const loadSchema = async () => {
  const serial = ++schemaSerial
  const accountId = Number(form.xianyuAccountId)
  schema.value = null
  if (!accountId) { schemaError.value = '请先选择一个发布账号'; return }
  schemaLoading.value = true
  schemaError.value = ''
  try {
    const response = await getListingFormSchema(accountId, form.productType)
    if (serial !== schemaSerial) return
    schema.value = response.data || null
    if (!schema.value) throw new Error('发布字段目录为空')
    if (draftId.value) {
      const stale = form.catalogVersion !== schema.value.catalogVersion
      const unsupported = !channels.value.some(item => item.channelCode === form.publishChannel && item.available)
      if (stale || unsupported) schemaError.value = '旧草稿已保留原目录版本和通道；目录陈旧或通道不可用，请核对后明确修改，未自动迁移。'
    } else {
      form.catalogVersion = schema.value.catalogVersion
      const available = channels.value.find(item => item.available)
      if (!channels.value.some(item => item.channelCode === form.publishChannel && item.available)) form.publishChannel = String(available?.channelCode || '')
      applyEmptyQrDefault()
      if (!schema.value.conditions.some(item => item.value === form.conditionCode)) form.conditionCode = schema.value.conditions[0]?.value || ''
      if (!schema.value.shippingModes.some(item => item.value === form.shippingMode)) form.shippingMode = schema.value.shippingModes[0]?.value || ''
    }
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
  if (hydratingDraft.value) return
  if (!leafCategories.value.some(item => item.code === form.leafCategoryCode)) { form.leafCategoryCode = ''; form.leafCategoryName = ''; form.categoryAttributes = {} }
})
watch(() => form.leafCategoryCode, value => {
  if (hydratingDraft.value) return
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
  clearTimeout(autosaveTimer)
  for (const key of Object.keys(form)) delete form[key]
  Object.assign(form, emptyForm(), draft.payload || {})
  addressEditing.value = false
  showAdvancedForm.value = false
  if (Array.isArray(draft.payload?.images)) form.imagesText = draft.payload.images.join('\n')
  draftId.value = Number(draft.id); draftRevision.value = Number(draft.revision || 1)
  draftSaveState.value = 'SAVED'; draftSavedAt.value = draft.updatedAt ? new Date(draft.updatedAt) : new Date()
  await nextTick()
  await loadSchema()
  await nextTick()
  hydratingDraft.value = false
  draftVersions.value = (await getListingDraftVersions(draftId.value)).data || []
  invalidatePreflight(); toast.success('商品草稿已载入')
}
let pendingSave: Promise<boolean> | null = null
const saveDraft = async (source: 'AUTO_SAVE' | 'MANUAL_SAVE' = 'MANUAL_SAVE'): Promise<boolean> => {
  if (!form.xianyuAccountId) return false
  if (pendingSave) {
    const completed = await pendingSave
    if (!completed || source === 'AUTO_SAVE') return completed
  }
  const task = (async () => {
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
      return true
    } catch (error: any) {
      draftSaveState.value = 'ERROR'
      if (source === 'MANUAL_SAVE') toast.error(error?.message || '草稿保存失败')
      return false
    } finally { if (source === 'MANUAL_SAVE') loading.value = false }
  })()
  pendingSave = task
  try { return await task } finally { if (pendingSave === task) pendingSave = null }
}

let autosaveTimer: ReturnType<typeof setTimeout> | undefined
const categoryInputFingerprint = computed(() => {
  const fields: Record<string, any> = baseCommand()
  delete fields.confirmedPlatformCategoryId
  return JSON.stringify(fields)
})
watch(categoryInputFingerprint, () => {
  if (!hydratingDraft.value) form.confirmedPlatformCategoryId = ''
})
watch(form, () => {
  if (!readyForAutosave.value || hydratingDraft.value) return
  invalidatePreflight()
  clearTimeout(autosaveTimer)
  if (!String(form.name || '').trim() && !String(form.description || '').trim()) return
  autosaveTimer = setTimeout(() => { void saveDraft('AUTO_SAVE') }, 1600)
}, { deep: true })

const invalidatePreflight = () => {
  preflightResult.value = null; preflightError.value = ''; preflightRequestId.value = ''; publishResult.value = null
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
const preflight = async (allowCategoryRecovery = true) => {
  clearTimeout(autosaveTimer)
  if (pendingSave && !await pendingSave) { toast.error('草稿未保存，请重试保存后再校验'); return }
  if (draftSaveState.value === 'ERROR') { toast.error('草稿未保存，请先重试保存'); return }
  loading.value = true
  preflightResult.value = null
  preflightError.value = ''
  try {
    if (!await localCheck()) {
      preflightError.value = String(localValidation.value?.errors?.[0] || '请先修正表单问题')
      return
    }
    const response = await preflightPublish(command(true))
    preflightResult.value = response.data || null; preflightRequestId.value = publishRequestId.value; activeSection.value = 'review'
    if (response.data?.requiresCategoryConfirmation) toast.info('请核对并确认平台推荐类目，再完成预检')
    else if (response.data?.valid !== true) { preflightError.value = '发布前校验未通过，请查看阻断原因'; toast.error(preflightError.value) }
    else toast.success(response.data?.platform?.executionChannel === 'QA_MOCK' ? '隔离预检通过，未调用闲鱼平台' : '已读取平台类目与地址，本地发布请求检查通过；尚未提交平台')
  } catch (error: any) {
    const message = String(error?.message || '')
    if (allowCategoryRecovery && Number(error?.code || error?.response?.data?.code) === 409
        && message.includes('平台推荐类目已变化')) {
      form.confirmedPlatformCategoryId = ''
      await nextTick()
      clearTimeout(autosaveTimer)
      if (await saveDraft('MANUAL_SAVE')) {
        toast.info('平台推荐类目已变化，正在重新获取；请核对后再次确认')
        await preflight(false)
      } else preflightError.value = '平台类目变化后草稿保存失败，请先保存草稿再重新校验'
    } else {
      preflightError.value = message || '发布前校验失败'
      toast.error(preflightError.value)
    }
  } finally { loading.value = false }
}
const selectCategoryMode = async (event: Event) => {
  const select = event.target as HTMLSelectElement
  const mode = select.value
  select.value = form.categoryMode
  if (mode === form.categoryMode) return
  if (mode === 'BASIC_PLATFORM_RECOMMENDED' && (form.leafCategoryCode || Object.keys(form.categoryAttributes || {}).length)) {
    try {
      await showConfirm('切换为平台推荐类目的基本发布后，将清除当前指定叶子类目及属性。基本发布不支持提交结构化类目属性，原草稿版本仍保留。', '确认切换发布模式')
    } catch { return }
  }
  if (mode === 'BASIC_PLATFORM_RECOMMENDED') {
    form.industryCode = ''; form.leafCategoryCode = ''; form.leafCategoryName = ''; form.categoryAttributes = {}
  }
  form.categoryMode = mode; form.confirmedPlatformCategoryId = ''
}
const confirmRecommendedCategory = async () => {
  const category = preflightResult.value?.platform?.category
  const id = String(category?.catId || category?.cid || category?.categoryId || '')
  if (!id) return toast.error('平台没有返回可确认的类目，请重新预检')
  try { await showConfirm(`平台推荐类目：${category.catName || category.categoryName || id}（${id}）。确认后会保存草稿并重新预检；类别变化将阻止发布。`, '确认平台推荐类目') } catch { return }
  form.confirmedPlatformCategoryId = id
  await nextTick()
  clearTimeout(autosaveTimer)
  if (await saveDraft('MANUAL_SAVE')) await preflight()
  else toast.error('草稿未保存，请重试后再确认类目')
}
const publish = async () => {
  if (selectedChannel.value?.executionAvailable === false) return toast.error(selectedChannel.value.executionReason || '当前通道只允许预检，不能创建真实发布任务')
  if (!canExecute.value) return toast.error('内容已变化，请重新执行发布前校验')
  if (executionBlockers.value.length) return toast.error(`当前通道尚未验证：${executionBlockers.value.join('、')}；为避免静默丢字段，已阻止真实提交`)
  try {
    await showConfirm([
      `店铺：${selectedAccount.value?.accountNote || selectedAccount.value?.unb || form.xianyuAccountId}`,
      `发布身份：${selectedChannel.value?.channelName || form.publishChannel} / ${schema.value?.businessModes.find(item => item.value === form.businessMode)?.label || form.businessMode}`,
      `商品：${form.name} · ¥${Number(form.amount).toFixed(2)} · 库存 ${form.stock}`,
      simpleVirtualQr.value ? `媒体：${images.value.length} 张图片 · 单一售价和库存` : `媒体：${images.value.length} 张图片 · SKU ${form.skus.length || 1} 个`,
      ...(basicCategoryMode.value ? [`已确认平台类目：${preflightResult.value?.platform?.category?.catName || form.confirmedPlatformCategoryId}`] : []),
      form.publishChannel === 'QA_LOCAL' ? '隔离验收：不会调用闲鱼网络。' : '确认后将向闲鱼提交真实发布请求。'
    ].join('\n'), form.publishChannel === 'QA_LOCAL' ? '确认隔离发布' : '确认发布商品')
  } catch { return }
  loading.value = true
  try {
    const response = await executePublish(command(true, true))
    publishResult.value = response.data || null
    if (isConfirmedPublish(publishResult.value)) {
      toast.success(publishResult.value?.platform?.localSynced === false
        ? '闲鱼已返回商品 ID；本地同步待修复，请按商品 ID 核对，不要重复发布'
        : publishResult.value?.verificationStatus === 'PENDING'
        ? '闲鱼已返回商品 ID；字段回读待核对，请勿重复发布'
        : '闲鱼已返回商品 ID，发布成功')
    } else if (publishResult.value?.valid === true && publishResult.value?.platform?.executionChannel === 'QA_MOCK') {
      toast.success('隔离任务执行成功，未写入闲鱼')
    } else if (String(publishResult.value?.outcomeState || '').includes('UNKNOWN')) {
      toast.warning('发布结果未知，请按请求 ID 查询；核实前不要再次提交')
    } else toast.error(publishResult.value?.error || '发布未成功，请查看结果与原因')
  } catch (error: any) {
    const code = Number(error?.code || error?.response?.data?.code || error?.response?.status)
    if (code >= 400 && code < 500) toast.error(error?.message || '发布请求被拒绝，请修正后重新预检')
    else {
      publishResult.value = { valid: false, outcomeState: 'UNKNOWN', requestId: publishRequestId.value,
        recoveryHint: '请求结果未确认。请按请求 ID 查询，核实前不要再次提交。' }
      toast.warning('发布请求结果未知，请先查询；不要重复提交')
    }
  } finally { loading.value = false }
}
const refreshStatus = async () => {
  if (!publishRequestId.value) return
  try { publishResult.value = (await getPublishingRequestStatus(publishRequestId.value)).data || null }
  catch (error: any) { toast.error(error?.message || '查询失败；请稍后用同一请求 ID 重试') }
}
const publishResultTitle = computed(() => publishResultLabel(publishResult.value))
const preflightAddressLabel = computed(() => {
  const address = preflightResult.value?.platform?.address || {}
  return [address.prov || address.province, address.city, address.area || address.district]
    .filter(Boolean).join(' ') || '未返回'
})
const statusText = (value: unknown) => ({ READY: '可用', SUPPORTED: '支持', PARTIAL: '部分能力', MOCK_ONLY: '隔离模拟', NOT_VERIFIED: '未验证', UNKNOWN: '未知', UNAVAILABLE: '不可用', ENVIRONMENT_BLOCKED: '环境禁止写入', NEEDS_VERIFICATION: '需要验证', REQUIRES_PLATFORM_PERMISSION: '需平台权限', NOT_CONNECTED: '未接入', AUTHORIZED: '已授权', NOT_APPLICABLE: '无需授权' }[String(value)] || String(value || '未同步'))
const channelAvailabilityText = (channel: Record<string, any>) => channel.available
  ? channel.executionAvailable === false ? '仅可预检' : '可发布'
  : statusText(channel.authorizationStatus === 'NOT_APPLICABLE' ? channel.connectionStatus : channel.authorizationStatus || channel.connectionStatus)
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
const channelTone = (channel: Record<string, any>) => channel.available ? channel.executionAvailable === false ? 'missing' : 'ready' : channel.authorizationStatus === 'NOT_CONNECTED' ? 'missing' : 'blocked'
const scrollTo = (id: string) => {
  const target = simpleVirtualQr.value && id === 'category' ? 'review'
    : simpleVirtualQr.value && id === 'trade' ? 'price' : id
  activeSection.value = target
  document.getElementById(`publish-${target}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
onMounted(async () => { await loadBase(); readyForAutosave.value = true })
onBeforeUnmount(() => clearTimeout(autosaveTimer))
</script>

<template>
  <main class="workbench publish-studio">
    <header class="workbench__header publish-studio__header">
      <div><span class="publish-studio__eyebrow">商品发布</span><h1>{{ simpleVirtualQr ? '发布虚拟商品' : '商品发布工作台' }}</h1><p>{{ simpleVirtualQr ? '选择账号，填写商品内容、图片、售价和库存；发布前再确认闲鱼推荐的类目。' : '完整字段保留在草稿中；通道不支持的能力不会静默提交。' }}</p></div>
      <div class="publish-studio__header-actions"><span class="draft-state" :class="`draft-state--${draftSaveState.toLowerCase()}`">{{ draftSaveState === 'SAVING' ? '自动保存中…' : draftSaveState === 'SAVED' ? `已保存 r${draftRevision}` : draftSaveState === 'ERROR' ? '自动保存失败' : '尚未保存' }}</span><select class="workbench__select" aria-label="打开商品草稿" @change="loadDraft"><option value="">打开草稿（{{ drafts.length }}）</option><option v-for="item in drafts" :key="item.id" :value="item.id">{{ item.draftName }} · r{{ item.revision }}</option></select><button class="workbench__btn" type="button" :disabled="loading" @click="saveDraft('MANUAL_SAVE')">{{ draftId ? '更新草稿' : '保存草稿' }}</button><button class="workbench__btn workbench__btn--primary" type="button" :disabled="loading" @click="preflight()">发布前校验</button></div>
    </header>
    <div class="publish-studio__mobile-switch" role="tablist" aria-label="发布编辑视图"><button type="button" :class="{ active: mobilePane === 'FORM' }" @click="mobilePane = 'FORM'">填写表单</button><button type="button" :class="{ active: mobilePane === 'PREVIEW' }" @click="mobilePane = 'PREVIEW'">买家预览</button></div>
    <div v-if="schemaError" class="publish-studio__error" role="alert"><span>{{ schemaError }}</span><button class="workbench__btn" type="button" @click="loadBase">重新读取</button></div>
    <div class="publish-studio__shell" :class="`publish-studio__shell--${mobilePane.toLowerCase()}`">
      <nav v-if="schema && !schemaLoading" class="publish-studio__nav" aria-label="发布表单章节"><button v-for="(item, index) in visibleSections" :key="item.id" :class="{ active: activeSection === item.id }" @click="scrollTo(item.id)"><span>{{ index + 1 }}</span><div><strong>{{ simpleVirtualQr && item.id === 'identity' ? '选择账号' : simpleVirtualQr && item.id === 'price' ? '售价与库存' : simpleVirtualQr && item.id === 'review' ? '核对并发布' : item.label }}</strong><small v-if="!simpleVirtualQr">{{ item.hint }}</small></div></button></nav>
      <div v-else class="publish-studio__loading" role="status">{{ schemaLoading ? '正在读取发布账号与可用通道…' : schemaError || '请选择可用的发布账号' }}</div>
      <form v-if="schema && !schemaLoading" class="publish-studio__form" @submit.prevent>
        <section id="publish-identity" class="publish-card" @focusin="activeSection = 'identity'">
          <header><div><span>01</span><h2>{{ simpleVirtualQr ? '选择发布账号' : '店铺与发布通道' }}</h2></div><p>{{ simpleVirtualQr ? '使用当前账号已连接的基础发布通道；不会要求填写授权、SKU 或类目属性。' : '授权、类目、SKU、编辑和营销能力都必须有真实证据。' }}</p></header>
          <div class="publish-grid publish-grid--two"><label class="workbench__field">发布店铺<select v-model="form.xianyuAccountId" class="workbench__select"><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }} · ID {{ account.id }}</option></select></label><label v-if="!simpleVirtualQr" class="workbench__field">发布身份<select v-model="form.businessMode" class="workbench__select"><option v-for="item in schema?.businessModes || []" :key="item.value" :value="item.value">{{ item.label }} · {{ item.description }}</option></select></label></div>
          <div v-if="simpleVirtualQr" class="basic-publish-note" role="status"><span>发布方式：当前账号的扫码通道</span><span>商品类目：由闲鱼推荐，提交前由你确认</span><button class="workbench__btn" type="button" @click="showAdvancedForm = true">查看完整字段</button></div>
          <button v-if="showAdvancedForm && form.productType === 'VIRTUAL' && form.publishChannel === 'QR_COOKIE' && basicCategoryMode && !executionBlockers.length" class="workbench__btn" type="button" @click="showAdvancedForm = false">返回简洁填写</button>
          <div v-if="schemaLoading" class="publish-studio__loading" role="status">正在读取当前店铺的连接、授权与通道能力…</div>
          <div v-else-if="!simpleVirtualQr" class="channel-grid"><button v-for="channel in channels" :key="channel.channelCode" type="button" class="channel-card" :class="[`channel-card--${channelTone(channel)}`, { selected: form.publishChannel === channel.channelCode }]" :disabled="!channel.available" @click="form.publishChannel = channel.channelCode; invalidatePreflight()"><span class="channel-card__radio"></span><div><strong>{{ channel.channelName }}</strong><small>{{ channel.channelCode }} · {{ channel.source || '来源未同步' }}</small></div><em>{{ channelAvailabilityText(channel) }}</em><p>{{ channel.reason || channel.executionReason || `最近核验：${channel.lastCheckedTime ? new Date(channel.lastCheckedTime).toLocaleString('zh-CN') : '未同步'}` }}</p></button></div>
          <div v-if="selectedChannel && !simpleVirtualQr" class="capability-matrix"><span v-for="(value, key) in selectedChannel.features" :key="key"><small>{{ key }}</small><strong>{{ statusText(value) }}</strong></span></div><p v-if="selectedChannel?.available === false || selectedChannel?.executionAvailable === false" class="publish-studio__channel-notice">{{ selectedChannel.reason || selectedChannel.executionReason || '当前账号暂不能发布，请先恢复账号连接' }}</p>
        </section>

        <section v-if="!simpleVirtualQr" id="publish-category" class="publish-card" @focusin="activeSection = 'category'">
          <header><div><span>02</span><h2>商品类型与类目属性</h2></div><p>{{ schema?.notice || '平台目录读取中' }}</p></header>
          <div class="product-type-grid"><label :class="{ selected: form.productType === 'PHYSICAL' }"><input v-model="form.productType" type="radio" value="PHYSICAL"><strong>实物商品</strong><small>快递、包邮/运费模板、成色和所在地</small></label><label :class="{ selected: form.productType === 'VIRTUAL' }"><input v-model="form.productType" type="radio" value="VIRTUAL"><strong>虚拟商品</strong><small>软件、卡密与数字内容，记录有效期和交付方式</small></label><label :class="{ selected: form.productType === 'SERVICE' }"><input v-model="form.productType" type="radio" value="SERVICE"><strong>服务商品</strong><small>远程、上门或到店，记录时长、预约和服务范围</small></label></div>
          <label v-if="form.publishChannel === 'QR_COOKIE' || basicCategoryMode" class="workbench__field">类目模式<select :value="form.categoryMode" class="workbench__select" @change="selectCategoryMode"><option value="SPECIFIED_CATALOG" :disabled="form.publishChannel === 'QR_COOKIE'">指定类目与属性（需通道支持）</option><option value="BASIC_PLATFORM_RECOMMENDED" :disabled="form.publishChannel !== 'QR_COOKIE'">平台推荐类目（基本发布）</option></select><small v-if="basicCategoryMode">基本发布不提交指定类目或结构化属性；请在预检结果中确认平台推荐类目。</small><small v-else>指定类目与属性仍需通道支持；原草稿不会自动转换为基本发布。</small></label>
          <div class="publish-grid publish-grid--three"><label class="workbench__field">行业<select v-model="form.industryCode" class="workbench__select" :disabled="basicCategoryMode || form.publishChannel === 'QR_COOKIE'"><option value="">请选择行业</option><option v-for="item in industries" :key="item.code" :value="item.code">{{ item.name }}</option></select></label><label class="workbench__field">叶子类目（最末级商品分类）<select v-model="form.leafCategoryCode" class="workbench__select" :disabled="basicCategoryMode || form.publishChannel === 'QR_COOKIE' || !selectedIndustry"><option value="">请选择最末级类目</option><option v-for="item in leafCategories" :key="item.code" :value="item.code">{{ item.name }}</option></select><small>{{ basicCategoryMode ? '预检取得平台推荐类目后确认' : '本地参考目录 · 需通道支持指定类目' }}</small></label><label class="workbench__field">成色 / 交付性质<select v-model="form.conditionCode" class="workbench__select"><option v-for="item in schema?.conditions || []" :key="item.value" :value="item.value">{{ item.label }}</option></select></label></div>
          <div v-if="!basicCategoryMode && attributes.length" class="attribute-panel"><header><strong>类目属性（该分类的附加信息）</strong><span>标记 * 的字段用于预检完整性</span></header><div class="publish-grid publish-grid--two"><label v-for="item in attributes" :key="item.code" class="workbench__field">{{ item.name }}{{ item.required ? ' *' : '' }}<select v-if="item.options.length" v-model="form.categoryAttributes[item.code]" class="workbench__select"><option value="">请选择</option><option v-for="option in item.options" :key="option" :value="option">{{ option }}</option></select><input v-else v-model="form.categoryAttributes[item.code]" class="workbench__input" maxlength="100" placeholder="请输入"></label></div></div>
        </section>

        <section id="publish-content" class="publish-card" @focusin="activeSection = 'content'">
          <header><div><span>{{ simpleVirtualQr ? '02' : '03' }}</span><h2>商品内容与图片</h2></div><p>{{ simpleVirtualQr ? '标题、详情和至少一张图片是基础发布的必填内容。' : '正文不使用网页字体冒充平台能力，字体样式应固化到有授权的商品图片中。' }}</p></header>
          <label v-if="!simpleVirtualQr" class="workbench__field">复用素材<select class="workbench__select" @change="useMaterial"><option value="">从素材库选择（可选）</option><option v-for="item in materials" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
          <label class="workbench__field">商品标题（必填）<input v-model="form.name" class="workbench__input" maxlength="120" placeholder="说明商品是什么、适合谁、核心差异"><small>{{ form.name.length }} / 120</small></label>
          <label class="workbench__field">商品详情（必填）<textarea v-model="form.description" class="workbench__textarea publish-studio__description" maxlength="3000" placeholder="建议依次说明：功能价值、适用范围、交付方式、使用教程、售后方式与有效期"></textarea><small>{{ form.description.length }} / 3000</small></label>
          <label class="workbench__field">商品图片（必填，1–{{ schema?.limits.images || 9 }} 张）<MediaUploader v-model="images" :account-id="form.xianyuAccountId" :max="schema?.limits.images || 9" label="上传商品图片" /><small>支持排序、删除、失败重试；第一张作为封面并同步到右侧预览。</small></label>
          <label v-if="!simpleVirtualQr" class="workbench__field">图片地址（可选，每行一张）<textarea v-model="form.imagesText" class="workbench__textarea" maxlength="6000" placeholder="支持 HTTPS 或本地媒体地址"></textarea></label>
          <details v-if="!simpleVirtualQr"><summary>视频（选填；扫码基础通道暂不支持）</summary>
          <label class="workbench__field">商品视频（可选）<MediaUploader v-model="videos" :account-id="form.xianyuAccountId" :max="1" accept="video" label="上传商品视频" /><small class="publish-studio__degraded">安全降级：视频可保存到草稿和素材版本，只有通道能力验证后才会提交平台。</small></label>
          </details>
        </section>

        <section id="publish-price" class="publish-card" @focusin="activeSection = 'price'">
          <header><div><span>{{ simpleVirtualQr ? '03' : '04' }}</span><h2>{{ simpleVirtualQr ? '售价与库存' : '售价、库存与规格型号' }}</h2></div><p>{{ simpleVirtualQr ? '这里是平台展示售价和可售数量；自动发货的模板或卡密请到对应页面单独配置。' : '平台库存和本地可交付库存分开核对，多 SKU 不会被降级成单品静默提交。' }}</p></header>
          <div class="publish-grid" :class="simpleVirtualQr ? 'publish-grid--two' : 'publish-grid--four'"><label class="workbench__field">售价（必填）<input v-model.number="form.amount" class="workbench__input" type="number" min="0.01" step="0.01" placeholder="0.00"></label><label v-if="!simpleVirtualQr" class="workbench__field">原价 / 划线价（选填，通道需支持）<input v-model.number="form.originalPrice" class="workbench__input" type="number" min="0.01" step="0.01" placeholder="可选"></label><label class="workbench__field">{{ form.publishChannel === 'QR_COOKIE' ? '平台库存（必填，最多 9999）' : '平台库存（必填）' }}<input v-model.number="form.stock" class="workbench__input" type="number" min="1" :max="form.publishChannel === 'QR_COOKIE' ? 9999 : undefined" step="1"></label><label v-if="!simpleVirtualQr" class="workbench__field">商家编码（选填，通道需支持）<input v-model="form.outerId" class="workbench__input" maxlength="64" placeholder="便于内部检索"></label></div>
          <details v-if="!simpleVirtualQr"><summary>多规格（选填；扫码基础通道暂不支持）</summary><p v-if="form.publishChannel === 'QR_COOKIE'" class="publish-studio__degraded">基础发布使用单一售价和库存。旧草稿规格保留；发布前需手动清理，不会自动丢弃。</p>
          <ListingSkuEditor v-model:dimensions="form.skuDimensions" v-model:skus="form.skus" :base-price="Number(form.amount || 0)" :base-stock="Number(form.stock || 1)" />
          </details>
          <div v-if="simpleVirtualQr" class="basic-publish-options"><p>发布位置：{{ hasExplicitAddress ? `${form.province} ${form.city} ${form.district}` : '使用此账号在闲鱼设置的默认位置（发布前校验时读取）' }}</p><button class="workbench__btn" type="button" @click="beginAddressEdit">修改发布位置</button><button v-if="hasExplicitAddress" class="workbench__btn" type="button" @click="useAccountAddress">改用账号默认位置</button><div v-if="addressEditing" class="publish-address"><PublishAddressFields v-model="addressDraft" /><div class="basic-publish-options__actions"><button class="workbench__btn workbench__btn--primary" type="button" @click="applyAddressEdit">使用此位置</button><button class="workbench__btn" type="button" @click="addressEditing = false">取消</button></div></div><details><summary>本地交付说明（可选，发布后另行配置自动发货）</summary><div class="publish-grid publish-grid--two"><label class="workbench__field">有效期（天）<input v-model.number="form.validityDays" class="workbench__input" type="number" min="1" step="1" placeholder="按实际服务填写"></label><label class="workbench__field">售后与安装说明<input v-model="form.supportPolicy" class="workbench__input" maxlength="500" placeholder="如：群内提供使用指导"></label></div></details></div>
        </section>

        <section v-if="!simpleVirtualQr" id="publish-trade" class="publish-card" @focusin="activeSection = 'trade'">
          <header><div><span>05</span><h2>交易、位置与服务承诺</h2></div><p>只承诺真实可履约的服务，协议、余额和类目资格仍需平台预检。</p></header>
          <div class="publish-grid publish-grid--two"><label class="workbench__field">交付 / 运费方式<select v-model="form.shippingMode" class="workbench__select"><option v-for="item in schema?.shippingModes || []" :key="item.value" :value="item.value">{{ item.label }} · {{ item.description }}</option></select></label><label v-if="form.shippingMode === 'FREIGHT_TEMPLATE'" class="workbench__field">运费模板 ID<input v-model="form.freightTemplateId" class="workbench__input" placeholder="平台运费模板 ID"></label></div>
          <div v-if="form.productType === 'VIRTUAL'" class="publish-grid publish-grid--three publish-type-fields"><label class="workbench__field">数字履约方式<select v-model="form.fulfillmentMode" class="workbench__select"><option value="AUTO_DELIVERY">自动发货</option><option value="MANUAL_DIGITAL">人工线上交付</option><option value="GROUP_SUPPORT">售后群内交付</option></select></label><label class="workbench__field">有效期（天，可选，本地记录）<input v-model.number="form.validityDays" class="workbench__input" type="number" min="1" step="1"></label><label class="workbench__field">售后与安装说明（可选，本地记录）<input v-model="form.supportPolicy" class="workbench__input" maxlength="500" placeholder="如：群内提供视频教程与人工指导"></label></div>
          <div v-else-if="form.productType === 'SERVICE'" class="publish-grid publish-grid--four publish-type-fields"><label class="workbench__field">服务时长（分钟）<input v-model.number="form.serviceDurationMinutes" class="workbench__input" type="number" min="1" step="1"></label><label class="workbench__field">至少提前预约（小时）<input v-model.number="form.appointmentLeadHours" class="workbench__input" type="number" min="0" step="1"></label><label class="workbench__field">服务范围<input v-model="form.serviceArea" class="workbench__input" maxlength="200" :placeholder="form.shippingMode === 'ON_SITE_SERVICE' ? '上门服务必填' : '可填写城市或线上范围'"></label><label class="workbench__field">改期 / 售后说明<input v-model="form.supportPolicy" class="workbench__input" maxlength="500" placeholder="说明改期、取消和售后规则"></label></div>
          <label v-else class="workbench__field publish-type-fields">实物售后说明<textarea v-model="form.afterSalesPolicy" class="workbench__textarea" maxlength="500" placeholder="说明退换、保修、瑕疵和签收注意事项"></textarea></label>
          <p class="type-requirement-note">{{ schema?.typeRequirements?.notice }}</p>
          <div class="publish-address"><p>发布位置：{{ hasExplicitAddress ? `${form.province} ${form.city} ${form.district}` : '使用账号默认位置' }}</p><button class="workbench__btn" type="button" @click="beginAddressEdit">修改位置</button><button v-if="hasExplicitAddress" class="workbench__btn" type="button" @click="useAccountAddress">使用账号默认位置</button><div v-if="addressEditing"><PublishAddressFields v-model="addressDraft" /><button class="workbench__btn" type="button" @click="applyAddressEdit">使用此位置</button><button class="workbench__btn" type="button" @click="addressEditing = false">取消</button></div></div>
          <div class="service-grid"><label v-for="service in schema?.serviceProtocols || []" :key="service.code" :class="{ selected: form.serviceProtocols.includes(service.code) }"><input v-model="form.serviceProtocols" type="checkbox" :value="service.code"><span><strong>{{ service.label }}</strong><small>{{ service.description }}</small></span><em>{{ statusText(service.status) }}</em></label></div>
        </section>

        <section id="publish-review" class="publish-card" @focusin="activeSection = 'review'">
          <header><div><span>{{ simpleVirtualQr ? '04' : '06' }}</span><h2>{{ simpleVirtualQr ? '核对并发布' : '风险校验与最终发布' }}</h2></div><p>草稿、发布前校验、平台确认是不同状态；只有闲鱼返回商品 ID 才算发布成功。</p></header>
          <div v-if="preflightError" class="publish-studio__error" role="alert"><span>{{ preflightError }}</span><button v-if="preflightError.includes('省、市、区') || preflightError.includes('发布位置')" class="workbench__btn" type="button" @click="scrollTo(simpleVirtualQr ? 'price' : 'trade'); beginAddressEdit()">手动选择位置</button></div>
          <div v-if="adapterWarnings.length" class="publish-studio__warnings"><strong>能力边界</strong><ul><li v-for="item in adapterWarnings" :key="item">{{ item }}</li></ul></div>
          <div v-if="executionBlockers.length" class="publish-studio__warnings"><strong>发布阻断项</strong><ul><li v-for="item in executionBlockers" :key="item">{{ item }}：可保存草稿；发布预检与提交须等待通道能力验证。</li></ul></div>
          <div v-if="localValidation" class="validation-result" :class="{ invalid: !localValidation.valid }"><header><strong>{{ localValidation.valid ? '本地结构校验通过' : '需要修正表单' }}</strong><span>{{ localValidation.catalogVersion }} · {{ localValidation.source }}</span></header><ul v-if="structureErrors.length"><li v-for="item in structureErrors" :key="`${item.field}-${item.code}`"><button type="button" @click="scrollTo(item.field.startsWith('category') || item.field.includes('Category') || item.field === 'industryCode' ? 'category' : item.field.startsWith('sku') || ['amount','originalPrice','stock','outerId'].includes(item.field) ? 'price' : ['shippingMode','supportPolicy','afterSalesPolicy','validityDays','serviceArea','serviceDurationMinutes','appointmentLeadHours'].includes(item.field) ? 'trade' : 'content')">{{ item.message }}</button><code>{{ item.field }}</code></li></ul><ul v-if="localValidation.warnings?.length"><li v-for="item in localValidation.warnings" :key="item">{{ item }}</li></ul></div>
          <section v-if="localValidation?.contentPolicy" class="content-risk" :class="{ 'content-risk--blocked': !localValidation.contentPolicy.valid }" aria-live="polite"><header><div><strong>{{ localValidation.contentPolicy.valid ? '内容风险检查通过' : `发现 ${localValidation.contentPolicy.blockerCount} 项内容风险` }}</strong><p>{{ localValidation.contentPolicy.summary }}</p></div><span>{{ localValidation.contentPolicy.policyVersion }} · {{ localValidation.contentPolicy.checkedFieldCount }} 个字段</span></header><ul v-if="localValidation.contentPolicy.findings?.length"><li v-for="item in localValidation.contentPolicy.findings" :key="`${item.field}-${item.term}`"><button type="button" @click="scrollTo(item.field.startsWith('category') ? 'category' : item.field.startsWith('sku') ? 'price' : ['supportPolicy','afterSalesPolicy','serviceArea'].includes(item.field) ? 'trade' : 'content')"><span>{{ item.fieldLabel }}</span><strong>{{ item.term }}</strong><small>{{ item.snippet }}</small></button><p>{{ item.suggestion }}</p></li></ul><footer><span>来源：系统内容策略 · 检查于 {{ localValidation.contentPolicy.checkedAt ? new Date(localValidation.contentPolicy.checkedAt).toLocaleString('zh-CN') : '—' }}</span><b>{{ localValidation.contentPolicy.nextAction }}</b></footer></section>
          <div v-if="preflightResult && simpleVirtualQr" class="publish-evidence">
            <header><strong>发布前核对</strong><span>{{ preflightResult.requiresCategoryConfirmation ? '待确认类目' : preflightResult.valid ? '可以提交' : '尚未通过' }}</span></header>
            <dl>
              <div><dt>闲鱼推荐类目</dt><dd>{{ preflightResult.platform?.category?.catName || preflightResult.platform?.category?.categoryName || '未返回' }}</dd></div>
              <div><dt>发布位置</dt><dd>{{ preflightAddressLabel }}</dd></div>
              <div><dt>售价 / 库存</dt><dd>¥{{ Number(form.amount).toFixed(2) }} / {{ form.stock }} 件</dd></div>
            </dl>
            <p>已读取平台类目与地址并检查本地请求；这一步尚未向闲鱼发布商品。</p>
          </div>
          <div v-else-if="preflightResult" class="publish-evidence"><header><strong>{{ preflightResult.platform?.executionChannel === 'QA_MOCK' ? '隔离平台预检' : '平台类目与地址读取 / 本地请求检查' }}</strong><span>{{ preflightResult.outcomeState }}</span></header><dl><div><dt>请求 ID</dt><dd>{{ preflightResult.requestId }}</dd></div><div><dt>发布通道</dt><dd>{{ preflightResult.platform?.publishChannel || form.publishChannel }}</dd></div><div><dt>平台类目</dt><dd>{{ preflightResult.platform?.category?.catName || preflightResult.platform?.category?.categoryName || '未返回' }}</dd></div><div><dt>目录版本</dt><dd>{{ preflightResult.catalogVersion }}</dd></div><div><dt>预检凭证</dt><dd>{{ preflightResult.previewToken }}</dd></div><div><dt>有效期至</dt><dd>{{ preflightResult.expiresAt ? new Date(preflightResult.expiresAt).toLocaleString('zh-CN') : '未返回' }}</dd></div><div><dt>载荷指纹</dt><dd>{{ preflightResult.payloadFingerprint }}</dd></div><div><dt>平台差异</dt><dd>{{ preflightResult.platformDifferences?.status || '未比对' }}</dd></div></dl><ul v-if="preflightResult.platformDifferences?.items?.length" class="publish-evidence__diff"><li v-for="item in preflightResult.platformDifferences.items" :key="item.field"><strong>{{ item.field }}</strong><span>{{ item.message }}</span></li></ul><p>{{ preflightResult.platform?.previewUsesProductionBuilder ? '预览与提交共用生产请求构造器。' : '隔离预检未调用生产构造器，也不会访问闲鱼网络。' }}</p></div>
          <div v-if="preflightResult?.requiresCategoryConfirmation" class="publish-studio__warnings" role="status"><p>平台推荐类目尚未确认，预检未通过，不能提交发布。</p><button class="workbench__btn" type="button" :disabled="loading" @click="confirmRecommendedCategory">确认推荐类目并重新预检</button></div>
          <div v-if="publishResult" class="publish-evidence" :class="isConfirmedPublish(publishResult) ? 'publish-evidence--result' : 'publish-evidence--failed'">
            <header><strong>{{ publishResultTitle }}</strong><span v-if="!simpleVirtualQr">{{ publishResult.outcomeState || publishResult.status }}</span></header>
            <dl>
              <div><dt>请求 ID</dt><dd>{{ publishResult.requestId || publishRequestId || '未生成' }}</dd></div>
              <div><dt>任务 ID</dt><dd>{{ publishResult.taskId || '未生成' }}</dd></div>
              <div><dt>商品 ID</dt><dd>{{ isConfirmedPublish(publishResult) ? publishResult.platform?.itemId : '未确认' }}</dd></div>
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
          <footer class="publish-studio__review-actions"><button class="workbench__btn" type="button" :disabled="loading" @click="saveDraft('MANUAL_SAVE')">保存草稿</button><button class="workbench__btn" type="button" :disabled="loading" @click="preflight()">{{ preflightResult ? '重新预检' : '发布前校验' }}</button><button class="workbench__btn workbench__btn--primary" type="button" :disabled="loading || !canExecute || !!publishResult" @click="publish">{{ publishResult ? '已提交，请查询结果' : '确认发布' }}</button></footer>
        </section>
      </form>
      <ListingPhonePreview :form="{ ...form, images }" :account-name="selectedAccount?.accountNote || selectedAccount?.unb" :channel-name="selectedChannel?.channelName" :category-name="form.leafCategoryName" />
    </div>
  </main>
</template>

<style scoped>
.publish-studio{--studio-border:#e4e0d7;--studio-muted:#77736b;max-width:1680px;margin:0 auto;padding:24px 28px 64px}.publish-studio__header{align-items:flex-end}.publish-studio__eyebrow{color:#a36a00;font-size:10px;font-weight:800;letter-spacing:.16em}.publish-studio__header-actions{display:flex;align-items:center;gap:9px}.publish-studio__header-actions .workbench__select{min-width:190px}.publish-studio__shell{display:grid;grid-template-columns:176px minmax(0,1fr) 360px;gap:18px;align-items:start;margin-top:20px}.publish-studio__nav{position:sticky;top:18px;display:grid;gap:4px}.publish-studio__nav button{display:grid;grid-template-columns:26px 1fr;align-items:center;gap:9px;padding:10px;border:0;border-radius:10px;color:#69655e;background:transparent;text-align:left;cursor:pointer}.publish-studio__nav button>span{display:grid;width:25px;height:25px;place-items:center;border-radius:50%;background:#ece9e2;font-size:10px;font-weight:800}.publish-studio__nav button div{display:grid}.publish-studio__nav strong{font-size:12px}.publish-studio__nav small{margin-top:2px;font-size:9px}.publish-studio__nav button.active{color:#3f3100;background:#fff5c9}.publish-studio__nav button.active>span{background:#ffd733}.publish-studio__form{display:grid;gap:14px;min-width:0}.publish-card{scroll-margin-top:18px;padding:20px;border:1px solid var(--studio-border);border-radius:16px;background:rgba(255,255,255,.92);box-shadow:0 8px 25px rgba(50,44,32,.045)}.publish-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:18px;padding-bottom:14px;border-bottom:1px solid #eeebe5}.publish-card>header>div{display:flex;align-items:center;gap:9px}.publish-card>header span{color:#ad7200;font-size:10px;font-weight:800}.publish-card h2{margin:0;font-size:18px}.publish-card>header p{max-width:56%;margin:0;color:var(--studio-muted);font-size:11px;line-height:1.5;text-align:right}.publish-grid{display:grid;gap:12px}.publish-grid--two{grid-template-columns:repeat(2,minmax(0,1fr))}.publish-grid--three{grid-template-columns:repeat(3,minmax(0,1fr))}.publish-grid--four{grid-template-columns:repeat(4,minmax(0,1fr))}.channel-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px}.channel-card{position:relative;display:grid;grid-template-columns:18px 1fr auto;gap:4px 8px;padding:13px;border:1px solid var(--studio-border);border-radius:12px;color:#403d38;background:#fff;text-align:left;cursor:pointer}.channel-card:disabled{cursor:not-allowed;opacity:.72}.channel-card.selected{border-color:#e2b500;box-shadow:0 0 0 2px #fff1a8}.channel-card__radio{width:14px;height:14px;margin-top:2px;border:1px solid #a8a49b;border-radius:50%}.channel-card.selected .channel-card__radio{border:4px solid #d6a900}.channel-card div{display:grid}.channel-card small{color:#8a867f;font-size:9px}.channel-card em{padding:3px 6px;border-radius:999px;font-size:9px;font-style:normal}.channel-card--ready em{color:#11633b;background:#e7f8ed}.channel-card--missing em{color:#835600;background:#fff1c2}.channel-card--blocked em{color:#8f3d34;background:#fff0ed}.channel-card p{grid-column:2/4;margin:5px 0 0;color:#77736b;font-size:10px;line-height:1.4}.capability-matrix{display:grid;grid-template-columns:repeat(auto-fit,minmax(90px,1fr));gap:7px;margin-top:10px;padding:10px;border-radius:11px;background:#f7f6f2}.capability-matrix span{display:grid;gap:2px}.capability-matrix small{color:#8b877f;font-size:9px;text-transform:uppercase}.capability-matrix strong{font-size:11px}.product-type-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-bottom:14px}.product-type-grid label{display:grid;grid-template-columns:18px 1fr;gap:3px 8px;padding:14px;border:1px solid var(--studio-border);border-radius:12px;cursor:pointer}.product-type-grid label.selected{border-color:#e1b300;background:#fffaf0}.product-type-grid input{grid-row:1/3;margin:3px 0}.product-type-grid small{color:#77736b;font-size:10px}.attribute-panel{margin-top:14px;padding:14px;border:1px solid #e5e1d9;border-radius:12px;background:#faf9f6}.attribute-panel>header{display:flex;justify-content:space-between;margin-bottom:12px}.attribute-panel>header span{color:#817d75;font-size:10px}.publish-studio__description{min-height:160px}.publish-studio__degraded{color:#9c6500!important}.publish-address{margin-top:12px}.service-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;margin-top:14px}.service-grid label{display:grid;grid-template-columns:18px 1fr auto;align-items:start;gap:8px;padding:12px;border:1px solid var(--studio-border);border-radius:11px;cursor:pointer}.service-grid label.selected{border-color:#dfb100;background:#fffaf0}.service-grid span{display:grid}.service-grid small{margin-top:3px;color:#77736b;font-size:10px}.service-grid em{color:#8d6700;font-size:9px;font-style:normal}.publish-studio__loading,.publish-studio__error{padding:14px;border-radius:11px;background:#f7f6f2;color:#65615a;font-size:12px}.publish-studio__error{display:flex;align-items:center;justify-content:space-between;margin-top:14px;border:1px solid #efb5ae;color:#912f28;background:#fff3f1}.publish-studio__warnings,.validation-result,.publish-evidence{margin-bottom:12px;padding:13px 14px;border:1px solid #ead188;border-radius:11px;color:#704d00;background:#fff9e5;font-size:11px}.publish-studio__warnings ul,.validation-result ul{margin:7px 0 0;padding-left:18px}.validation-result{border-color:#b8dfc5;color:#165d39;background:#f0faf3}.validation-result.invalid{border-color:#efb5ae;color:#912f28;background:#fff3f1}.validation-result header,.publish-evidence header{display:flex;justify-content:space-between}.publish-evidence{border-color:#d9d5cc;color:#494640;background:#f8f7f4}.publish-evidence dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:10px 0}.publish-evidence dt{color:#827e76;font-size:9px}.publish-evidence dd{overflow-wrap:anywhere;margin:2px 0 0;font-weight:700}.publish-evidence--result{border-color:#b8dfc5;background:#f1faf4}.publish-studio__review-actions{position:sticky;bottom:0;display:flex;justify-content:flex-end;gap:9px;margin:16px -20px -20px;padding:13px 20px;border-top:1px solid var(--studio-border);border-radius:0 0 16px 16px;background:rgba(255,255,255,.96);backdrop-filter:blur(8px)}
.basic-publish-note,.basic-publish-options{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin-top:14px;padding:14px;border:1px solid #e8db9f;border-radius:12px;background:#fffdf3;color:#5f4b13;font-size:13px;line-height:1.5}.basic-publish-note span{padding:5px 8px;border-radius:7px;background:#fff7da}.basic-publish-note .workbench__btn{margin-left:auto}.basic-publish-options>p{flex:1 1 100%;margin:0}.basic-publish-options>.publish-address,.basic-publish-options>details{flex:1 1 100%}.basic-publish-options__actions{display:flex;gap:8px;margin-top:10px}.basic-publish-options .publish-grid{margin-top:10px}.publish-evidence--failed{border-color:#efb5ae;color:#912f28;background:#fff3f1}
@media(max-width:1380px){.publish-studio__shell{grid-template-columns:150px minmax(0,1fr) 320px}.publish-studio{padding-inline:20px}.publish-grid--four{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:1100px){.publish-studio__shell{grid-template-columns:130px minmax(0,1fr)}.publish-studio__shell>.listing-preview{grid-column:2}.publish-studio__header{align-items:flex-start;flex-direction:column}.publish-studio__header-actions{width:100%;flex-wrap:wrap}}
@media(max-width:767px){.publish-studio{padding:14px 12px 48px}.publish-studio__shell{grid-template-columns:1fr}.publish-studio__nav{position:static;display:flex;overflow-x:auto;padding-bottom:4px}.publish-studio__nav button{min-width:132px}.publish-studio__form,.publish-studio__shell>.listing-preview{grid-column:1}.publish-card{padding:15px;border-radius:13px}.publish-card>header{align-items:flex-start;flex-direction:column;gap:6px}.publish-card>header p{max-width:none;text-align:left}.publish-grid--two,.publish-grid--three,.publish-grid--four,.product-type-grid,.service-grid{grid-template-columns:1fr}.publish-studio__header-actions .workbench__select{width:100%}.publish-studio__header-actions .workbench__btn{flex:1}.publish-studio__review-actions{margin:14px -15px -15px;padding:11px 15px max(11px,env(safe-area-inset-bottom))}.publish-studio__review-actions .workbench__btn{flex:1;padding-inline:8px}.publish-evidence dl{grid-template-columns:1fr}}
.publish-card>header>div{flex:1 1 auto;min-width:0}.publish-card h2{word-break:keep-all}.publish-card>header p{flex:0 1 46%;max-width:46%}
.draft-state{padding:5px 8px;border-radius:999px;color:#6c675f;background:#f0eee8;font-size:10px;white-space:nowrap}.draft-state--saving{color:#7a5600;background:#fff3c4}.draft-state--saved{color:#17603d;background:#e7f7ed}.draft-state--error{color:#922f28;background:#ffefed}.publish-studio__mobile-switch{display:none}.product-type-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.publish-type-fields{margin-top:14px}.type-requirement-note{margin:10px 0 0;color:#7b7469;font-size:11px}.validation-result li button{padding:0;border:0;color:inherit;background:transparent;text-align:left;text-decoration:underline;cursor:pointer}.validation-result li code{margin-left:7px;color:#8c8379;font-size:9px}.draft-versions{margin-top:12px;padding:12px;border:1px solid #e1ddd4;border-radius:11px;background:#faf9f6}.draft-versions summary{cursor:pointer;font-weight:700}.draft-versions ol{display:grid;gap:6px;margin:10px 0 0;padding:0;list-style:none}.draft-versions li{display:grid;grid-template-columns:42px 72px 1fr auto;gap:8px;align-items:center;font-size:10px}.draft-versions time{color:#77736b}.draft-versions code{color:#8a6500}
.content-risk{display:grid;gap:12px;margin-bottom:12px;padding:14px;border:1px solid #b8dfc5;border-radius:12px;color:#165d39;background:#f0faf3}.content-risk--blocked{border-color:#ec9d91;color:#842b24;background:#fff3f1}.content-risk>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.content-risk>header div{display:grid;gap:3px}.content-risk>header p,.content-risk li p{margin:0;font-size:11px;line-height:1.5}.content-risk>header>span,.content-risk>footer span{color:#77736b;font-size:10px}.content-risk ul{display:grid;gap:8px;margin:0;padding:0;list-style:none}.content-risk li{display:grid;gap:6px;padding:10px;border:1px solid currentColor;border-radius:9px;background:rgba(255,255,255,.68)}.content-risk li button{display:grid;grid-template-columns:auto auto minmax(0,1fr);align-items:center;gap:8px;padding:0;border:0;color:inherit;background:transparent;text-align:left;cursor:pointer}.content-risk li button span{font-size:10px;font-weight:800}.content-risk li button strong{padding:3px 6px;border-radius:999px;background:rgba(155,44,44,.1);font-size:10px}.content-risk li button small{min-width:0;overflow:hidden;color:#6f6860;text-overflow:ellipsis;white-space:nowrap}.content-risk>footer{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;padding-top:10px;border-top:1px solid currentColor}.content-risk>footer b{font-size:11px;text-align:right}
@media(max-width:767px){.publish-studio__mobile-switch{display:grid;grid-template-columns:1fr 1fr;margin-top:14px;padding:3px;border-radius:10px;background:#ece9e2}.publish-studio__mobile-switch button{padding:9px;border:0;border-radius:8px;background:transparent;font-weight:700}.publish-studio__mobile-switch button.active{background:#fff;box-shadow:0 2px 8px rgba(50,44,32,.1)}.publish-studio__shell{margin-top:10px}.publish-studio__shell--preview .publish-studio__nav,.publish-studio__shell--preview .publish-studio__form{display:none}.publish-studio__shell--form>.listing-preview{display:none}.draft-state{width:100%;text-align:center}.draft-versions li{grid-template-columns:36px 64px 1fr}.draft-versions code{display:none}}
@media(max-width:767px){.content-risk>header,.content-risk>footer{display:grid}.content-risk>footer b{text-align:left}.content-risk li button{grid-template-columns:auto 1fr}.content-risk li button small{grid-column:1/-1;white-space:normal}}
.publish-evidence__diff--verified{display:grid;gap:6px;margin:10px 0;padding:0;list-style:none}.publish-evidence__diff--verified li{display:grid;grid-template-columns:90px minmax(0,1fr);gap:3px 10px;padding:8px 10px;border-radius:8px;background:rgba(255,255,255,.72)}.publish-evidence__diff--verified li strong{font-size:11px}.publish-evidence__diff--verified li span{color:#5f5b54}.publish-evidence__diff--verified li small{grid-column:2;color:#8a5b00;overflow-wrap:anywhere}.publish-evidence__diff--verified .difference--different{border-left:3px solid #d89b00}.publish-evidence__diff--verified .difference--unavailable{border-left:3px solid #cf5d50}
.publish-studio__channel-notice{margin:9px 0 0;padding:9px 11px;border-radius:9px;color:#775300;background:#fff6d8;font-size:10px}
@media(max-width:767px){.publish-evidence__diff--verified li{grid-template-columns:1fr}.publish-evidence__diff--verified li small{grid-column:1}}
</style>
