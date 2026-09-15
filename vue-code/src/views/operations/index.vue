<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAccountList } from '@/api/account'
import {
  batchPublish, cancelTask, compensateResource, deleteResource, executeResource,
  getMerchantOverview, getResources, getTasks, requeueTask, saveResource,
  type MerchantOverview, type MerchantResource, type MerchantTask, type ResourceType
} from '@/api/merchant'
import { getKamiConfigsByAccountId, type KamiConfig } from '@/api/kami-config'
import {
  activateGrowthResourceVersion, getGrowthResource, getGrowthResources, saveGrowthResourceVersion,
  type GrowthResource
} from '@/api/growth-workspace'
import MediaUploader from '@/components/MediaUploader.vue'
import type { Account } from '@/types'
import { showConfirm, showError, showSuccess } from '@/utils'
import { useAsyncResourceState } from '@/composables/useAsyncResourceState'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap'

type OperationsView = 'overview' | 'resources' | 'tasks'

const route = useRoute()
const router = useRouter()

const resourceTypes: Array<{ value: ResourceType; label: string; group: string; description: string; guide: string }> = [
  { value: 'MATERIAL', label: '素材库', group: '商品运营', description: '保存待发布商品的标题、价格、库存、图片与详情。', guide: '先关联账号并完善图片、详情和地址，再单条执行或勾选后批量发布。' },
  { value: 'ADDRESS', label: '地址库', group: '商品运营', description: '复用商品发布时需要的发货地址。', guide: '创建地址后，在素材或发布规则中直接选择，无需重复填写。' },
  { value: 'SELECTION_RULE', label: '选品规则', group: '采集管理', description: '按关键词、价格和库存筛选候选货源。', guide: '关联账号并填写关键词，设置首次执行时间；立即执行可先验证规则结果。' },
  { value: 'PUBLISH_RULE', label: '发布规则', group: '任务规则', description: '按固定间隔自动发布指定素材。', guide: '选择素材、账号和地址，设置首次执行时间与间隔，执行结果在任务记录查看。' },
  { value: 'DELETE_RULE', label: '删除规则', group: '任务规则', description: '按计划删除指定闲鱼商品。', guide: '填写远端商品 ID、关联账号和执行时间；删除属于不可逆操作。' },
  { value: 'ANNOUNCEMENT', label: '公告管理', group: '服务管理', description: '保存租户内运营公告与执行提醒。', guide: '使用清晰标题和正文记录重要变更，可随时停用或更新。' },
  { value: 'FEEDBACK', label: '系统反馈', group: '服务管理', description: '集中记录使用问题与改进建议。', guide: '写明现象、期望和复现路径，便于后续处理。' },
  { value: 'RISK_EVENT', label: '风控记录', group: '服务管理', description: '查看或补录平台验证、异常流量等风险事件。', guide: '自动化任务触发验证时会自动写入；人工记录可补充处理结论。' }
]

const normalizeView = (value: unknown): OperationsView =>
  ['overview', 'resources', 'tasks'].includes(String(value))
    ? String(value) as OperationsView
    : 'overview'
const normalizeType = (value: unknown): ResourceType =>
  resourceTypes.some(item => item.value === String(value)) ? String(value) as ResourceType : 'MATERIAL'
const activeView = ref<OperationsView>(normalizeView(route.query.view))
const activeType = ref<ResourceType>(normalizeType(route.query.type))
const viewStates: Record<OperationsView, ReturnType<typeof useAsyncResourceState>> = {
  overview: useAsyncResourceState(),
  resources: useAsyncResourceState(),
  tasks: useAsyncResourceState()
}
const supportingLoading = ref(false)
const currentState = computed(() => viewStates[activeView.value])
const loading = computed(() => supportingLoading.value || currentState.value.busy.value)
const firstLoading = computed(() => supportingLoading.value || currentState.value.firstLoading.value)
const blockingFailure = computed(() => currentState.value.blockingFailure.value)
const hasSuccessfulData = computed(() => currentState.value.hasSuccessfulData.value)
const refreshing = computed(() => currentState.value.refreshing.value)
const loadError = computed(() => currentState.value.errorMessage.value)
const forbidden = computed(() => currentState.value.phase.value === 'forbidden')
const accounts = ref<Account[]>([])
const addresses = ref<MerchantResource[]>([])
const materials = ref<MerchantResource[]>([])
const resources = ref<MerchantResource[]>([])
const tasks = ref<MerchantTask[]>([])
const selectedIds = ref<number[]>([])
const publishAccountIds = ref<number[]>([])
const showEditor = ref(false)
const materialDetail = ref<GrowthResource>()
const materialDetailOpen = ref(false)
const materialDetailDialog = ref<HTMLElement | null>(null)
const overviewCounts = reactive<Record<string, number>>({})
const overviewTaskCount = ref(0)
const overviewFailedCount = ref(0)
const saving = ref(false)
const pendingAction = ref('')
const kamiConfigs = ref<KamiConfig[]>([])
const kamiLoading = ref(false)
const taskFilters = reactive({ taskId: '', requestId: '', accountId: '' })
const pageSize = 25
const resourcePage = ref(1)
const taskPage = ref(1)

const form = reactive<any>({})
const closeMaterialDetail = () => { materialDetailOpen.value = false }
useModalFocusTrap(materialDetailOpen, materialDetailDialog, closeMaterialDetail)
const formDefaults = () => ({
  id: undefined, resourceType: activeType.value, name: '', status: 1, xianyuAccountId: undefined, xianyuAccountIds: [] as number[],
  xyGoodsId: '', stock: 0, amount: undefined, scheduledTime: '', description: '', images: '', videos: '', sourceUrl: '',
  province: '', city: '', detail: '', keyword: '', minAmount: 0,
  maxAmount: 999999, minStock: 0, intervalMinutes: 1440, materialId: undefined, kamiConfigId: undefined,
  targetUrl: '', addressId: undefined, content: '', level: 'INFO'
  , sourceType: 'MANUAL', authorizationStatus: 'DECLARED', licenseType: 'OWNED', licenseNote: '',
  sourceItemId: '', sourceCapturedTime: '', validFrom: '', validUntil: ''
})
Object.assign(form, formDefaults())

const currentType = computed(() => resourceTypes.find(item => item.value === activeType.value)!)
const groups = computed(() => [...new Set(resourceTypes.map(item => item.group))])
const resourcePageCount = computed(() => Math.max(1, Math.ceil(resources.value.length / pageSize)))
const taskPageCount = computed(() => Math.max(1, Math.ceil(tasks.value.length / pageSize)))
const pagedResources = computed(() => resources.value.slice((resourcePage.value - 1) * pageSize, resourcePage.value * pageSize))
const pagedTasks = computed(() => tasks.value.slice((taskPage.value - 1) * pageSize, taskPage.value * pageSize))
const pageRange = (page: number, total: number) => total
  ? `${(page - 1) * pageSize + 1}–${Math.min(page * pageSize, total)}`
  : '0'
const accountName = (id?: number) => accounts.value.find(item => item.id === id)?.accountNote || accounts.value.find(item => item.id === id)?.unb || '-'
const statusText = (status: number) => ({ 0: '停用', 1: '启用', 2: '已完成', '-1': '失败' } as Record<string, string>)[String(status)] || '处理中'
const taskStatusText = (status: number) => ({ 0: '待执行', 1: '执行中', 2: '成功', 3: '已取消', 4: '结果未知', '-1': '失败' } as Record<string, string>)[String(status)] || '-'
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
const taskName = (type: string) => ({ COLLECT: '采集', SELECT: '选品', PUBLISH: '发布', DELETE: '删除', COMPENSATE: '补偿', REFRESH_PROMOTION: '账号状态刷新' } as Record<string, string>)[type] || type
const actionText = (type: ResourceType) => ({ SUPPLY: '立即采集', SELECTION_RULE: '立即选品', PUBLISH_RULE: '立即发布', DELETE_RULE: '立即删除', PROMOTION_ACCOUNT: '刷新状态', MATERIAL: '立即发布' } as Partial<Record<ResourceType, string>>)[type] || '立即执行'
const isActioning = (key: string) => pendingAction.value === key
const taskResultText = (task: MerchantTask) => task.errorMessage || ({ 0: '等待执行', 1: '正在处理', 2: '执行成功', 3: '已取消', '-1': '执行失败' } as Record<string, string>)[String(task.status)] || '-'
const resourceSummary = (resource: MerchantResource) => {
  if (resource.resourceType === 'ADDRESS') return [resource.data?.province, resource.data?.city, resource.data?.detail].filter(Boolean).join(' ') || '地址信息待完善'
  if (resource.resourceType === 'MATERIAL') {
    const price = resource.amount == null ? '价格未设置' : `¥${Number(resource.amount).toFixed(2)}`
    return `${price} · 库存 ${resource.stock || 0}`
  }
  if (resource.resourceType === 'SUPPLY') return resource.data?.sourceUrl || resource.xyGoodsId || '来源待完善'
  if (resource.resourceType.endsWith('_RULE')) return `每 ${resource.data?.intervalMinutes || 1440} 分钟 · ${formatTime(resource.scheduledTime)}`
  return resource.data?.content || accountName(resource.xianyuAccountId)
}

const applyOverview = (overview?: MerchantOverview) => {
  resourceTypes.forEach(item => { overviewCounts[item.value] = Number(overview?.resourceCounts?.[item.value] || 0) })
  overviewTaskCount.value = Number(overview?.taskCount || 0)
  overviewFailedCount.value = Number(overview?.failedTaskCount || 0)
}

const loadOverview = async () => {
  const result = await viewStates.overview.execute(() => getMerchantOverview(), {
    errorMessage: '运营概览读取失败，请重试。'
  })
  if (result.applied && result.value) applyOverview(result.value.data)
}

const runWithAction = async (key: string, action: () => Promise<void>) => {
  if (pendingAction.value) return
  pendingAction.value = key
  try {
    await action()
  } finally {
    pendingAction.value = ''
  }
}

const loadKamiConfigs = async () => {
  if (form.resourceType !== 'MATERIAL' || !form.xianyuAccountId) {
    kamiConfigs.value = []
    kamiLoading.value = false
    return
  }
  const accountId = Number(form.xianyuAccountId)
  kamiLoading.value = true
  try {
    const configs = (await getKamiConfigsByAccountId(accountId)).data || []
    if (Number(form.xianyuAccountId) === accountId) kamiConfigs.value = configs
  } catch {
    if (Number(form.xianyuAccountId) === accountId) kamiConfigs.value = []
  } finally {
    if (Number(form.xianyuAccountId) === accountId) kamiLoading.value = false
  }
}

const loadResources = async () => {
  const type = activeType.value
  const result = await viewStates.resources.execute(async () => {
    if (type !== 'MATERIAL') return getResources(type)
    const response = await getGrowthResources('MATERIAL')
    return {
      ...response,
      data: (response.data || []).map(item => ({
        id: item.id, resourceType: 'MATERIAL' as const, name: item.name, status: item.status,
        xianyuAccountId: item.accountId, xianyuAccountIds: item.accountIds, xyGoodsId: item.goodsId,
        stock: item.stock, amount: item.amount, scheduledTime: undefined, lastRunTime: undefined,
        data: { ...item.payload, growthVersion: item.version },
        createdTime: item.createdTime || '', updatedTime: item.updatedTime || ''
      }))
    }
  }, {
    isEmpty: response => !(response.data || []).length,
    errorMessage: `${currentType.value.label}读取失败，请重试。`
  })
  if (result.applied && result.value && type === activeType.value) {
    const response = result.value
    resources.value = response.data || []
    resourcePage.value = 1
    selectedIds.value = []
  }
}

const loadTasks = async () => {
  const result = await viewStates.tasks.execute(() => getTasks({
      taskId: taskFilters.taskId ? Number(taskFilters.taskId) : undefined,
      requestId: taskFilters.requestId.trim() || undefined,
      accountId: taskFilters.accountId ? Number(taskFilters.accountId) : undefined,
      limit: 1000
    }), {
      isEmpty: response => !(response.data || []).length,
      errorMessage: '运营任务读取失败，请重试。'
    })
  if (result.applied && result.value) {
    tasks.value = result.value.data || []
    taskPage.value = 1
  }
}

const clearTaskFilters = async () => {
  Object.assign(taskFilters, { taskId: '', requestId: '', accountId: '' })
  await loadTasks()
}

const loadCurrentView = async () => {
  if (activeView.value === 'overview') await loadOverview()
  if (activeView.value === 'resources') await loadResources()
  if (activeView.value === 'tasks') await loadTasks()
}

const updateRoute = (view: OperationsView, type = activeType.value) => {
  void router.push({ query: { ...route.query, view, ...(view === 'resources' ? { type } : {}) } })
}

const switchView = async (view: OperationsView) => {
  activeView.value = view
  updateRoute(view)
  await loadCurrentView()
}

const switchType = async (type: ResourceType) => {
  activeType.value = type
  activeView.value = 'resources'
  updateRoute('resources', type)
  await loadResources()
}

const openCreate = () => {
  Object.assign(form, formDefaults())
  if (['SELECTION_RULE', 'PUBLISH_RULE', 'DELETE_RULE'].includes(activeType.value)) {
    const firstRun = new Date(Date.now() + 5 * 60 * 1000)
    form.scheduledTime = new Date(firstRun.getTime() - firstRun.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
  }
  showEditor.value = true
  void loadKamiConfigs()
}

const openEdit = (resource: MerchantResource) => {
  const version = resource.data?.growthVersion
  Object.assign(form, formDefaults(), resource, resource.data || {}, {
    xianyuAccountIds: resource.xianyuAccountIds?.length ? [...resource.xianyuAccountIds] : (resource.xianyuAccountId ? [resource.xianyuAccountId] : []),
    images: Array.isArray(resource.data?.images) ? resource.data.images.join('\n') : resource.data?.images || '',
    videos: Array.isArray(resource.data?.videos) ? resource.data.videos.join('\n') : resource.data?.videos || '',
    sourceType: version?.source?.type || 'MANUAL', sourceUrl: version?.source?.url || resource.data?.sourceUrl || '',
    sourceItemId: version?.source?.itemId || '', authorizationStatus: version?.source?.authorizationStatus || 'UNKNOWN',
    licenseType: version?.license?.type || 'UNKNOWN', licenseNote: version?.license?.note || '',
    validFrom: version?.validFrom?.slice(0, 16) || '', validUntil: version?.validUntil?.slice(0, 16) || ''
  })
  showEditor.value = true
  void loadKamiConfigs()
}

const openMaterialDetail = async (resource: MerchantResource) => {
  materialDetailOpen.value = true
  materialDetail.value = undefined
  materialDetail.value = (await getGrowthResource(resource.id)).data
}

const activateMaterialVersion = async (version: number) => {
  if (!materialDetail.value) return
  materialDetail.value = (await activateGrowthResourceVersion(
    materialDetail.value.id, version, `MATERIAL-ACTIVATE-${crypto.randomUUID()}`.slice(0, 64)
  )).data
  showSuccess(`素材版本 v${version} 已启用`)
  await loadResources()
}

watch(() => form.xianyuAccountId, () => {
  if (showEditor.value && form.resourceType === 'MATERIAL') void loadKamiConfigs()
})

watch(() => form.xianyuAccountIds, (ids: number[]) => {
  if (form.resourceType === 'MATERIAL') form.xianyuAccountId = ids?.[0]
}, { deep: true })

const buildData = () => {
  const images = String(form.images || '').split(/\n|,/).map((item: string) => item.trim()).filter(Boolean)
  const videos = String(form.videos || '').split(/\n|,/).map((item: string) => item.trim()).filter(Boolean)
  return {
    description: form.description || undefined, images: images.length ? images : undefined,
    videos: videos.length ? videos : undefined,
    sourceUrl: form.sourceUrl || undefined,
    province: form.province || undefined, city: form.city || undefined, detail: form.detail || undefined,
    keyword: form.keyword || undefined, minAmount: Number(form.minAmount || 0),
    maxAmount: Number(form.maxAmount || 999999), minStock: Number(form.minStock || 0),
    intervalMinutes: Number(form.intervalMinutes || 1440), materialId: form.materialId ? Number(form.materialId) : undefined,
    kamiConfigId: form.kamiConfigId ? Number(form.kamiConfigId) : undefined,
    targetUrl: form.targetUrl || undefined, addressId: form.addressId ? Number(form.addressId) : undefined,
    content: form.content || undefined, level: form.level || undefined
  }
}

const resourceImages = computed<string[]>({
  get: () => String(form.images || '').split(/\n|,/).map((item: string) => item.trim()).filter(Boolean),
  set: value => { form.images = value.join('\n') }
})
const resourceVideos = computed<string[]>({
  get: () => String(form.videos || '').split(/\n|,/).map((item: string) => item.trim()).filter(Boolean),
  set: value => { form.videos = value.join('\n') }
})

const submitForm = async () => {
  if (saving.value) return
  if (!String(form.name || '').trim()) return showError('请输入名称')
  if (form.resourceType === 'MATERIAL' && !form.xianyuAccountIds?.length) return showError('素材至少需要关联一个发布账号')
  if (form.resourceType === 'ADDRESS' && (!form.province || !form.city || !form.detail)) return showError('请完整填写省份、城市和详细地址')
  if (form.resourceType === 'SUPPLY' && !form.sourceUrl && !form.xyGoodsId) return showError('来源地址和闲鱼商品 ID 至少填写一项')
  if (form.resourceType === 'SELECTION_RULE' && (!form.xianyuAccountId || !form.keyword)) return showError('选品规则需要关联账号并填写关键词')
  if (form.resourceType === 'PUBLISH_RULE' && (!form.xianyuAccountId || !form.materialId)) return showError('发布规则需要选择账号和素材')
  if (form.resourceType === 'DELETE_RULE' && (!form.xianyuAccountId || !form.xyGoodsId)) return showError('删除规则需要关联账号并填写商品 ID')
  if (form.resourceType === 'PROMOTION_ACCOUNT' && !form.xianyuAccountId) return showError('请选择已登录账号')
  if (['SELECTION_RULE', 'PUBLISH_RULE', 'DELETE_RULE'].includes(form.resourceType) && !form.scheduledTime) return showError('请选择首次执行时间')
  if (['ANNOUNCEMENT', 'FEEDBACK', 'RISK_EVENT'].includes(form.resourceType) && !String(form.content || '').trim()) return showError('请输入内容')
  saving.value = true
  try {
    if (form.resourceType === 'MATERIAL') {
      await saveGrowthResourceVersion({
        resourceId: form.id,
        resourceType: 'MATERIAL',
        name: form.name.trim(),
        accountIds: form.xianyuAccountIds.map(Number),
        status: Number(form.status),
        sourceType: form.sourceType,
        sourceUrl: form.sourceUrl || undefined,
        sourceItemId: form.sourceItemId || undefined,
        sourceCapturedTime: form.sourceCapturedTime || undefined,
        authorizationStatus: form.authorizationStatus,
        licenseType: form.licenseType,
        licenseNote: form.licenseNote || undefined,
        validFrom: form.validFrom || undefined,
        validUntil: form.validUntil || undefined,
        requestId: `MATERIAL-${crypto.randomUUID()}`.slice(0, 64),
        payload: {
          ...buildData(),
          ...(form.amount === '' || form.amount == null ? {} : { amount: Number(form.amount) }),
          stock: Number(form.stock || 0)
        }
      })
      showEditor.value = false
      showSuccess('素材草稿版本已保存，请在档案中确认后启用')
      await loadResources()
      return
    }
    await saveResource({
      id: form.id, resourceType: form.resourceType, name: form.name.trim(), status: Number(form.status),
      xianyuAccountId: form.xianyuAccountId ? Number(form.xianyuAccountId) : undefined,
      xianyuAccountIds: form.resourceType === 'MATERIAL' ? form.xianyuAccountIds.map(Number) : undefined,
      xyGoodsId: form.xyGoodsId || undefined, stock: Number(form.stock || 0), amount: Number(form.amount || 0),
      scheduledTime: form.scheduledTime || undefined, data: buildData()
    } as any)
    showEditor.value = false
    showSuccess(form.id ? '保存成功' : '创建成功')
    await loadResources()
    if (activeType.value === 'ADDRESS') addresses.value = [...resources.value]
    if (activeType.value === 'MATERIAL') materials.value = [...resources.value]
  } finally {
    saving.value = false
  }
}

const removeResource = async (resource: MerchantResource) => {
  try {
    await showConfirm(`确认删除“${resource.name}”？`)
  } catch {
    return
  }
  await runWithAction(`delete:${resource.id}`, async () => {
    await deleteResource(resource.id)
    showSuccess('删除成功')
    await loadResources()
  })
}

const runResource = async (resource: MerchantResource) => {
  if (resource.resourceType === 'DELETE_RULE') {
    try {
      await showConfirm(`将立即执行“${resource.name}”，目标商品删除后无法恢复。`, '确认执行删除规则')
    } catch {
      return
    }
  }
  await runWithAction(`run:${resource.id}`, async () => {
    if (resource.resourceType === 'MATERIAL') {
      const tasks = (await batchPublish([resource.id])).data || []
      showSuccess(`已按关联账号创建 ${tasks.length} 个发布任务`)
      return
    }
    const task = (await executeResource(resource.id)).data
    if (task?.status === -1) {
      showError(task.errorMessage || '任务执行失败，请到任务记录查看原因')
    } else {
      showSuccess('任务执行成功，可在任务记录查看结果')
    }
    await loadResources()
  })
}

const compensate = async (resource: MerchantResource) => {
  await runWithAction(`compensate:${resource.id}`, async () => {
    const task = (await compensateResource(resource.id)).data
    if (task?.status === -1) {
      showError(task.errorMessage || '补偿失败，请到任务记录查看原因')
    } else {
      showSuccess('发布信息、短链和卡券绑定已完成检查与补偿')
    }
    await loadResources()
  })
}

const publishSelected = async () => {
  if (!selectedIds.value.length) return showError('请先选择素材')
  await runWithAction('batch-publish', async () => {
    const tasks = (await batchPublish(selectedIds.value, publishAccountIds.value.length ? publishAccountIds.value : undefined)).data || []
    showSuccess(`已创建 ${tasks.length} 个账号发布任务`)
    selectedIds.value = []
  })
}

const retryTask = async (task: MerchantTask) => {
  await runWithAction(`retry:${task.id}`, async () => {
    await requeueTask(task.id)
    showSuccess('任务已重新排队')
    await loadTasks()
  })
}

const cancelPendingTask = async (task: MerchantTask) => {
  try {
    await showConfirm(`确认取消 #${task.id} 吗？取消后任务不会执行，可在需要时重新创建任务。`, '取消待执行任务')
  } catch {
    return
  }
  await runWithAction(`cancel:${task.id}`, async () => {
    await cancelTask(task.id)
    showSuccess('任务已取消，不会继续执行')
    await loadTasks()
  })
}

let initialized = false
watch([() => route.query.view, () => route.query.type], async ([viewValue, typeValue]) => {
  const view = normalizeView(viewValue)
  const type = normalizeType(typeValue)
  const changed = view !== activeView.value || (view === 'resources' && type !== activeType.value)
  if (!changed) return
  activeView.value = view
  if (view === 'resources') activeType.value = type
  if (initialized) await loadCurrentView()
})

onMounted(async () => {
  supportingLoading.value = true
  try {
    const [accountResult, addressResult, materialResult] = await Promise.all([
      getAccountList(), getResources('ADDRESS'), getResources('MATERIAL')
    ])
    accounts.value = accountResult.data?.accounts || []
    addresses.value = addressResult.data || []
    materials.value = materialResult.data || []
  } finally {
    supportingLoading.value = false
  }
  initialized = true
  await loadCurrentView()
})
</script>

<template>
  <div class="operations-page">
    <header class="page-header">
      <div><h1>运营中心</h1><p>版本化素材、运营规则与任务证据统一管理</p></div>
      <button v-if="activeView === 'resources'" class="primary-btn" :disabled="loading || saving" @click="openCreate">新建{{ currentType.label }}</button>
    </header>

    <div class="view-tabs" role="tablist" aria-label="运营中心栏目">
      <button role="tab" :class="{ active: activeView === 'overview' }" :aria-selected="activeView === 'overview'" @click="switchView('overview')">使用向导</button>
      <button role="tab" :class="{ active: activeView === 'resources' }" :aria-selected="activeView === 'resources'" @click="switchView('resources')">资源与规则</button>
      <button role="tab" :class="{ active: activeView === 'tasks' }" :aria-selected="activeView === 'tasks'" @click="switchView('tasks')">任务记录</button>
    </div>
    <div v-if="refreshing" class="loading-bar" role="status"><span></span>正在刷新，已保留上次成功结果</div>
    <section v-if="firstLoading" class="content-card resource-state" role="status"><span class="resource-spinner"></span><strong>正在读取当前运营范围</strong><small>服务端确认后才会显示数量和空状态</small></section>
    <section v-else-if="blockingFailure" class="content-card resource-state resource-state--error" role="alert"><strong>{{ forbidden ? '当前范围不可访问' : '暂时无法读取' }}</strong><small>{{ loadError }}</small><button class="secondary-btn" :disabled="loading" @click="loadCurrentView">重新读取</button></section>
    <div v-else-if="loadError && hasSuccessfulData" class="refresh-warning" role="status"><span>{{ loadError }} 已保留上次成功结果。</span><button class="secondary-btn" :disabled="loading" @click="loadCurrentView">重试</button></div>

    <section v-if="!firstLoading && !blockingFailure && activeView === 'overview'" class="overview">
      <div class="overview-intro"><div><h2>从素材到发布预检的完整流程</h2><p>按顺序完成资料、采集、版本审查和任务验证；没有可靠归因的数据不会显示为分销或佣金。</p></div><button class="primary-btn" @click="switchType('MATERIAL')">从素材库开始</button></div>
      <div class="flow-grid">
        <button @click="switchType('ADDRESS')"><b>1</b><span><strong>准备资料</strong><small>地址库 → 素材库</small></span></button>
        <button @click="switchType('SELECTION_RULE')"><b>2</b><span><strong>采集选品</strong><small>货源库 → 选品规则</small></span></button>
        <button @click="switchType('PUBLISH_RULE')"><b>3</b><span><strong>发布运营</strong><small>发布规则 → 删除规则</small></span></button>
      </div>
      <div class="overview-stats"><span>运营资源 <strong>{{ Object.values(overviewCounts).reduce((sum, count) => sum + count, 0) }}</strong></span><span>累计任务 <strong>{{ overviewTaskCount }}</strong></span><span :class="{ warn: overviewFailedCount > 0 }">失败待处理 <strong>{{ overviewFailedCount }}</strong></span></div>
      <div class="capability-grid">
        <button v-for="item in resourceTypes" :key="item.value" @click="switchType(item.value)"><span><strong>{{ item.label }}</strong><small>{{ overviewCounts[item.value] || 0 }} 条</small></span><p>{{ item.description }}</p></button>
      </div>
    </section>

    <section v-else-if="!firstLoading && !blockingFailure && activeView === 'resources'" class="workspace">
      <aside class="type-nav">
        <div v-for="group in groups" :key="group" class="type-group">
          <span>{{ group }}</span>
          <button v-for="item in resourceTypes.filter(type => type.group === group)" :key="item.value" :class="{ active: activeType === item.value }" @click="switchType(item.value)">{{ item.label }}</button>
        </div>
      </aside>

      <div class="content-card">
        <div class="card-toolbar">
          <div><strong>{{ currentType.label }}</strong><span>{{ resources.length }} 条</span></div>
          <span v-if="activeType === 'MATERIAL'" class="material-boundary">版本化素材仅在此维护；正式发布请进入商品发布中心</span>
        </div>
        <div class="context-guide"><strong>{{ currentType.description }}</strong><span>{{ currentType.guide }}</span></div>
        <div class="table-scroll"><table>
          <thead><tr><th>名称</th><th>关联账号</th><th>商品/库存</th><th v-if="activeType === 'MATERIAL'">版本 / 来源</th><th>状态</th><th>计划时间</th><th class="action-col">操作</th></tr></thead>
          <tbody>
            <tr v-for="resource in pagedResources" :key="resource.id">
              <td><strong>{{ resource.name }}</strong><small>{{ resourceSummary(resource) }}</small></td>
              <td>{{ (resource.xianyuAccountIds?.length ? resource.xianyuAccountIds : [resource.xianyuAccountId]).filter(Boolean).map(id => accountName(id)).join('、') || '-' }}</td>
              <td><span v-if="resource.xyGoodsId">{{ resource.xyGoodsId }}</span><span v-else>库存 {{ resource.stock }}</span></td>
              <td v-if="activeType === 'MATERIAL'"><strong>{{ resource.data?.growthVersion ? `v${resource.data.growthVersion.version}` : '未建立版本' }}</strong><small>{{ resource.data?.growthVersion?.source?.type || '来源未知' }} · {{ resource.data?.growthVersion?.license?.type || '许可未知' }}</small></td>
              <td><span class="status" :class="{ enabled: resource.status === 1 }">{{ statusText(resource.status) }}</span></td>
              <td>{{ formatTime(resource.scheduledTime) }}</td>
              <td class="actions">
                <button v-if="resource.resourceType === 'MATERIAL'" @click="openMaterialDetail(resource)">360 档案</button>
                <button :disabled="!!pendingAction" @click="openEdit(resource)">{{ resource.resourceType === 'MATERIAL' ? '创建新版本' : '编辑' }}</button>
                <button v-if="['SELECTION_RULE','PUBLISH_RULE','DELETE_RULE','PROMOTION_ACCOUNT'].includes(resource.resourceType)" :disabled="!!pendingAction" @click="runResource(resource)">{{ isActioning(`run:${resource.id}`) ? '执行中...' : actionText(resource.resourceType) }}</button>
                <button v-if="resource.resourceType !== 'MATERIAL'" class="danger" :disabled="!!pendingAction" @click="removeResource(resource)">{{ isActioning(`delete:${resource.id}`) ? '删除中...' : '删除' }}</button>
              </td>
            </tr>
            <tr v-if="!loading && !resources.length"><td :colspan="activeType === 'MATERIAL' ? 7 : 6" class="empty">暂无{{ currentType.label }}，创建后按上方指引继续</td></tr>
          </tbody>
        </table></div>
        <footer v-if="resources.length" class="table-pager" aria-label="资源列表分页">
          <span>显示 {{ pageRange(resourcePage, resources.length) }}，共 {{ resources.length }} 条</span>
          <div><button class="secondary-btn" :disabled="resourcePage <= 1" @click="resourcePage--">上一页</button><span>第 {{ resourcePage }}/{{ resourcePageCount }} 页</span><button class="secondary-btn" :disabled="resourcePage >= resourcePageCount" @click="resourcePage++">下一页</button></div>
        </footer>
      </div>
    </section>

    <section v-else-if="!firstLoading && !blockingFailure && activeView === 'tasks'" class="content-card full-card">
      <div class="card-toolbar"><div><strong>任务记录</strong><span>{{ tasks.length }} 条</span></div><button class="secondary-btn" :disabled="loading" @click="loadTasks">{{ loading ? '刷新中...' : '刷新' }}</button></div>
      <form class="task-filters" aria-label="任务精确筛选" @submit.prevent="loadTasks">
        <label><span>任务 ID</span><input v-model.trim="taskFilters.taskId" inputmode="numeric" placeholder="例如 9"></label>
        <label><span>请求 ID</span><input v-model.trim="taskFilters.requestId" maxlength="64" placeholder="完整 requestId"></label>
        <label><span>发布账号</span><select v-model="taskFilters.accountId"><option value="">全部可见账号</option><option v-for="account in accounts" :key="account.id" :value="String(account.id)">{{ account.accountNote || account.unb }} · {{ account.id }}</option></select></label>
        <div><button class="primary-btn" type="submit" :disabled="loading">查询</button><button class="secondary-btn" type="button" :disabled="loading" @click="clearTaskFilters">清空</button></div>
      </form>
      <div class="table-scroll"><table><thead><tr><th>任务</th><th>资源</th><th>账号</th><th>状态</th><th>执行次数</th><th>计划时间</th><th>结果</th><th>操作</th></tr></thead><tbody>
        <tr v-for="task in pagedTasks" :key="task.id"><td><strong>{{ taskName(task.taskType) }}</strong><small>#{{ task.id }}<template v-if="task.batchId"> · 批次 {{ task.batchId.slice(0, 8) }}</template></small><small v-if="task.requestKey" :title="task.requestKey">请求 {{ task.requestKey }}</small></td><td>{{ task.resourceId ? `#${task.resourceId}` : '-' }}</td><td>{{ accountName(task.xianyuAccountId) }}</td><td><span class="status" :class="{ enabled: task.status === 2, failed: task.status === -1 }">{{ taskStatusText(task.status) }}</span><small v-if="task.taskType === 'PUBLISH'">平台校验：{{ task.verificationStatus === 'VERIFIED' ? '已回读确认' : task.verificationStatus === 'PENDING' ? '待人工确认' : task.verificationStatus || '待执行' }}</small></td><td>{{ task.attemptCount }}/{{ task.maxAttempts }}</td><td>{{ formatTime(task.scheduledTime) }}</td><td class="result-cell" :title="task.errorMessage || task.resultJson || ''">{{ taskResultText(task) }}</td><td class="actions"><button v-if="task.status === -1" :disabled="!!pendingAction" @click="retryTask(task)">{{ isActioning(`retry:${task.id}`) ? '排队中...' : '重新执行' }}</button><button v-if="task.status === 0 || task.status === -1" :disabled="!!pendingAction" class="danger" @click="cancelPendingTask(task)">{{ isActioning(`cancel:${task.id}`) ? '取消中...' : '取消任务' }}</button></td></tr>
        <tr v-if="!loading && !tasks.length"><td colspan="8" class="empty">暂无任务记录</td></tr>
      </tbody></table></div>
      <footer v-if="tasks.length" class="table-pager" aria-label="任务列表分页">
        <span>显示 {{ pageRange(taskPage, tasks.length) }}，共 {{ tasks.length }} 条</span>
        <div><button class="secondary-btn" :disabled="taskPage <= 1" @click="taskPage--">上一页</button><span>第 {{ taskPage }}/{{ taskPageCount }} 页</span><button class="secondary-btn" :disabled="taskPage >= taskPageCount" @click="taskPage++">下一页</button></div>
      </footer>
    </section>


    <div v-if="showEditor" class="dialog-mask">
      <form class="editor" @submit.prevent="submitForm">
        <header><div><h2>{{ form.id ? '编辑' : '新建' }}{{ currentType.label }}</h2><p>{{ currentType.guide }}</p></div><button type="button" :disabled="saving" aria-label="关闭" @click="showEditor = false">×</button></header>
        <div class="editor-body">
        <div class="form-grid">
          <label class="wide"><span>名称</span><input v-model="form.name" required maxlength="200" placeholder="输入便于识别的名称"><small class="form-hint">{{ form.name.length }} / 200</small></label>
          <label><span>状态</span><select v-model="form.status"><option :value="1">启用</option><option :value="0">停用</option></select></label>
          <label v-if="form.resourceType !== 'MATERIAL'"><span>关联账号</span><select v-model="form.xianyuAccountId"><option :value="undefined">不关联</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select></label>
          <label v-else class="wide"><span>发布账号（可多选）</span><select v-model="form.xianyuAccountIds" multiple :size="Math.min(accounts.length, 5)"><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select><small class="form-hint">同一份素材统一维护；发布时为每个账号创建独立任务和结果。</small></label>
          <template v-if="['MATERIAL','SUPPLY'].includes(form.resourceType)">
            <label><span>库存</span><input v-model.number="form.stock" type="number" min="0"></label><label><span>价格（可选）</span><input v-model.number="form.amount" type="number" min="0" step="0.01" placeholder="尚未确定可留空"></label>
            <label class="wide"><span>详情描述</span><textarea v-model="form.description" rows="4" placeholder="商品卖点与交付说明"></textarea></label>
            <label class="wide"><span>商品图片</span><MediaUploader v-model="resourceImages" :account-id="form.xianyuAccountId" :max="9" label="上传图片" /><small class="form-hint">优先上传至闲鱼图床；不成功时保存到本机数据卷，发布时再同步。</small></label>
            <label class="wide"><span>本地视频素材</span><MediaUploader v-model="resourceVideos" :max="5" accept="video" label="上传视频" /><small class="form-hint">视频仅保存在本机素材库，可预览、删除；当前闲鱼发布接口不自动提交视频。</small></label>
            <label class="wide"><span>或粘贴图片地址</span><textarea v-model="form.images" rows="3" placeholder="每行一个 HTTPS 图片地址"></textarea></label>
          </template>
          <template v-if="form.resourceType === 'MATERIAL'"><label class="wide"><span>跳转目标</span><input v-model="form.targetUrl" placeholder="用于生成站内短链"></label><label><span>卡券仓库</span><select v-model="form.kamiConfigId" :disabled="!form.xianyuAccountId || kamiLoading"><option :value="undefined">{{ !form.xianyuAccountId ? '请先选择关联账号' : kamiLoading ? '正在加载卡券仓库' : '不绑定卡券仓库' }}</option><option v-for="config in kamiConfigs" :key="config.id" :value="config.id">{{ config.aliasName }}（可用 {{ config.availableCount }}）</option></select><small class="form-hint">绑定后可用于虚拟商品自动交付</small></label></template>
          <template v-if="form.resourceType === 'MATERIAL'"><label><span>来源类型</span><select v-model="form.sourceType"><option value="MANUAL">人工创建</option><option value="PLATFORM_SEARCH">平台搜索</option><option value="PLATFORM_SHOP">平台店铺</option><option value="SUPPLIER_IMPORT">供应商导入</option><option value="LOCAL_DRAFT">本地草稿</option></select></label><label><span>来源授权</span><select v-model="form.authorizationStatus"><option value="VERIFIED">已核验证明</option><option value="DECLARED">人工声明</option><option value="PUBLIC_READ">公开只读</option><option value="UNKNOWN">未知</option><option value="NOT_APPLICABLE">不适用</option></select></label><label class="wide"><span>来源链接</span><input v-model="form.sourceUrl" placeholder="非手工来源必须填写 HTTPS 链接"></label><label><span>来源商品 ID</span><input v-model="form.sourceItemId"></label><label><span>采集时间</span><input v-model="form.sourceCapturedTime" type="datetime-local"></label><label><span>素材许可</span><select v-model="form.licenseType"><option value="OWNED">自有</option><option value="AUTHORIZED">已授权</option><option value="PUBLIC_DOMAIN">公共领域</option><option value="UNKNOWN">未知</option></select></label><label class="wide"><span>许可说明</span><input v-model="form.licenseNote" placeholder="记录授权主体、范围或待确认原因"></label><label><span>生效时间</span><input v-model="form.validFrom" type="datetime-local"></label><label><span>失效时间</span><input v-model="form.validUntil" type="datetime-local"></label></template>
          <template v-if="['MATERIAL','PUBLISH_RULE'].includes(form.resourceType)"><label><span>发布地址</span><select v-model="form.addressId"><option :value="undefined">平台默认地址</option><option v-for="address in addresses" :key="address.id" :value="address.id">{{ address.name }}</option></select></label></template>
          <template v-if="form.resourceType === 'SUPPLY'"><label class="wide"><span>来源地址</span><input v-model="form.sourceUrl" placeholder="货源详情地址"></label><label><span>闲鱼商品 ID</span><input v-model="form.xyGoodsId"></label></template>
          <template v-if="form.resourceType === 'ADDRESS'"><label><span>省份</span><input v-model="form.province"></label><label><span>城市</span><input v-model="form.city"></label><label class="wide"><span>详细地址</span><input v-model="form.detail"></label></template>
          <template v-if="form.resourceType === 'SELECTION_RULE'"><label><span>关键词</span><input v-model="form.keyword"></label><label><span>最低库存</span><input v-model.number="form.minStock" type="number" min="0"></label><label><span>最低价格</span><input v-model.number="form.minAmount" type="number" min="0"></label><label><span>最高价格</span><input v-model.number="form.maxAmount" type="number" min="0"></label></template>
           <template v-if="form.resourceType === 'PUBLISH_RULE'"><label><span>发布素材</span><select v-model="form.materialId"><option :value="undefined">请选择素材</option><option v-for="material in materials" :key="material.id" :value="material.id">{{ material.name }}</option></select></label></template>
          <template v-if="form.resourceType === 'DELETE_RULE'"><label><span>闲鱼商品 ID</span><input v-model="form.xyGoodsId"></label></template>
          <template v-if="['SELECTION_RULE','PUBLISH_RULE','DELETE_RULE'].includes(form.resourceType)"><label><span>执行间隔（分钟）</span><input v-model.number="form.intervalMinutes" type="number" min="5"></label><label><span>下次执行</span><input v-model="form.scheduledTime" type="datetime-local"></label></template>
          <template v-if="['ANNOUNCEMENT','FEEDBACK','RISK_EVENT'].includes(form.resourceType)"><label class="wide"><span>内容</span><textarea v-model="form.content" rows="5"></textarea></label><label v-if="form.resourceType === 'RISK_EVENT'"><span>风险级别</span><select v-model="form.level"><option>INFO</option><option>WARN</option><option>HIGH</option></select></label></template>
         </div>
         <div class="form-help"><strong>保存后的下一步</strong><span>{{ currentType.guide }}</span></div>
        </div>
        <footer><button type="button" class="secondary-btn" :disabled="saving" @click="showEditor = false">取消</button><button type="submit" class="primary-btn" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button></footer>
      </form>
    </div>

    <div v-if="materialDetailOpen" class="dialog-mask" @click.self="closeMaterialDetail">
      <article ref="materialDetailDialog" class="material-detail" role="dialog" aria-modal="true" aria-labelledby="material-detail-dialog-title" tabindex="-1">
        <header><div><small>素材 360 档案 · #{{ materialDetail?.id || '-' }}</small><h2 id="material-detail-dialog-title">{{ materialDetail?.name || '读取中…' }}</h2></div><button aria-label="关闭素材档案" @click="closeMaterialDetail">×</button></header>
        <main v-if="materialDetail">
          <section class="material-detail__metrics"><div><span>当前版本</span><strong>{{ materialDetail.version ? `v${materialDetail.version.version}` : '未建立' }}</strong></div><div><span>来源授权</span><strong>{{ materialDetail.version?.source?.authorizationStatus || '未知' }}</strong></div><div><span>素材许可</span><strong>{{ materialDetail.version?.license?.type || '未知' }}</strong></div><div><span>引用次数</span><strong>{{ materialDetail.version?.referenceCount ?? '未同步' }}</strong></div></section>
          <section><h3>来源证据</h3><dl><div><dt>来源类型</dt><dd>{{ materialDetail.version?.source?.type || '未记录' }}</dd></div><div><dt>来源链接</dt><dd>{{ materialDetail.version?.source?.url || '未记录' }}</dd></div><div><dt>采集时间</dt><dd>{{ formatTime(materialDetail.version?.source?.capturedAt) }}</dd></div><div><dt>适用账号</dt><dd>{{ materialDetail.accountIds.map(id => accountName(id)).join('、') }}</dd></div></dl></section>
          <section><h3>不可变版本</h3><div class="material-detail__versions"><article v-for="version in materialDetail.versions" :key="version.id"><div><strong>v{{ version.version }}</strong><span>{{ version.lifecycleState }} · {{ formatTime(version.createdTime) }}</span><small>内容指纹 {{ version.payloadFingerprint.slice(0, 12) }}… · {{ version.operatorUsername || 'system' }}</small></div><div><em v-if="version.readiness.blockers.length">{{ version.readiness.blockers.join('；') }}</em><button v-if="version.lifecycleState !== 'ACTIVE'" class="secondary-btn" @click="activateMaterialVersion(version.version)">启用版本</button></div></article></div></section>
        </main>
        <main v-else class="empty">正在读取完整档案…</main>
        <footer><span>详情保留列表上下文；不提供无审计删除或直接发布入口。</span><div><button class="secondary-btn" @click="closeMaterialDetail">关闭</button></div></footer>
      </article>
    </div>
  </div>
</template>

<style scoped>
.operations-page{padding:24px;color:#101828}.page-header{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:20px}.page-header h1{font-size:24px;margin:0 0 6px}.page-header p,.editor p{margin:0;color:#667085;font-size:14px}.view-tabs{display:flex;gap:4px;border-bottom:1px solid #e4e7ec;margin-bottom:16px}.view-tabs button{border:0;background:transparent;padding:10px 16px;color:#667085;cursor:pointer;border-bottom:2px solid transparent}.view-tabs button.active{color:#9a6200;border-bottom-color:#9a6200;font-weight:600}.workspace{display:grid;grid-template-columns:180px minmax(0,1fr);gap:16px}.type-nav,.content-card{background:#fff;border:1px solid #e4e7ec;border-radius:8px}.type-nav{padding:12px;height:max-content}.type-group{display:flex;flex-direction:column;margin-bottom:12px}.type-group>span{font-size:12px;color:#98a2b3;padding:7px 10px}.type-group button{border:0;background:transparent;text-align:left;padding:9px 10px;border-radius:6px;color:#475467;cursor:pointer}.type-group button.active{background:#fff8d9;color:#9a6200;font-weight:600}.card-toolbar{min-height:60px;display:flex;align-items:center;justify-content:space-between;padding:0 16px;border-bottom:1px solid #e4e7ec}.card-toolbar strong{font-size:16px}.card-toolbar span{font-size:12px;color:#98a2b3;margin-left:8px}.batch-actions{display:flex;gap:8px}.batch-actions select{min-width:170px}.batch-account-select{height:36px;max-width:230px;overflow:hidden}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;font-size:13px}th{background:#f9fafb;color:#475467;text-align:left;font-weight:600;padding:11px 12px;white-space:nowrap}td{padding:12px;border-top:1px solid #eaecf0;color:#344054}td strong,td small{display:block}td small{color:#98a2b3;margin-top:3px}.check-col{width:32px}.action-col{width:190px}.actions{white-space:nowrap}.actions button{border:0;background:transparent;color:#9a6200;cursor:pointer;padding:4px 6px}.actions .danger{color:#d92d20}.status{display:inline-flex;padding:3px 8px;border-radius:10px;background:#f2f4f7;color:#667085}.status.enabled{background:#ecfdf3;color:#027a48}.status.failed{background:#fef3f2;color:#b42318}.result-cell{max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.empty{text-align:center!important;color:#98a2b3!important;padding:48px!important}.primary-btn,.secondary-btn{height:36px;padding:0 14px;border-radius:6px;cursor:pointer;font-weight:500}.primary-btn{border:1px solid #9a6200;background:#9a6200;color:#fff}.secondary-btn{border:1px solid #d0d5dd;background:#fff;color:#344054}.secondary-btn:disabled{opacity:.5}.table-pager{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 16px;border-top:1px solid #eaecf0;color:#667085;font-size:12px}.table-pager>div{display:flex;align-items:center;gap:10px}.dialog-mask{position:fixed;inset:0;background:rgba(16,24,40,.35);z-index:1000;display:flex;align-items:center;justify-content:center;padding:20px}.editor{display:grid;width:min(720px,100%);max-height:90vh;grid-template-rows:auto minmax(0,1fr) auto;overflow:hidden;overscroll-behavior:contain;background:#fff;border-radius:10px}.editor header,.editor footer{z-index:2;display:flex;align-items:flex-start;justify-content:space-between;padding:18px 22px;border-bottom:1px solid #eaecf0;background:#fff}.editor header>div{min-width:0}.editor header h2{font-size:18px;margin:0 0 4px}.editor header button{flex:none;border:0;background:transparent;font-size:24px;color:#667085;cursor:pointer}.editor footer{align-items:center;border-top:1px solid #eaecf0;border-bottom:0;justify-content:flex-end;gap:10px}.editor-body{min-height:0;overflow:auto;overscroll-behavior:contain}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;padding:22px}.form-grid label{display:flex;flex-direction:column;gap:6px}.form-grid label.wide{grid-column:1/-1}.form-grid label>span{font-size:13px;font-weight:500;color:#344054}input,select,textarea{box-sizing:border-box;width:100%;border:1px solid #d0d5dd;border-radius:6px;background:#fff;padding:9px 10px;color:#101828;font:inherit}textarea{resize:vertical}input:focus,select:focus,textarea:focus{outline:0;border-color:#9a6200;box-shadow:0 0 0 3px #fff8d9}.overview{display:flex;flex-direction:column;gap:16px}.overview-intro{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:22px;background:#fff;border:1px solid #e4e7ec;border-radius:8px}.overview-intro h2{margin:0 0 6px;font-size:20px}.overview-intro p{margin:0;color:#667085;font-size:13px}.flow-grid,.capability-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.flow-grid button,.capability-grid button{display:flex;text-align:left;border:1px solid #e4e7ec;background:#fff;border-radius:8px;padding:15px;cursor:pointer}.flow-grid button{align-items:center;gap:12px}.flow-grid b{display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:#fff8d9;color:#9a6200}.flow-grid span,.capability-grid span{display:flex;flex-direction:column;gap:4px}.flow-grid small,.capability-grid small{color:#98a2b3}.overview-stats{display:flex;gap:12px}.overview-stats span{flex:1;padding:14px 16px;background:#fff;border:1px solid #e4e7ec;border-radius:8px;color:#667085}.overview-stats strong{float:right;color:#101828}.overview-stats .warn,.overview-stats .warn strong{color:#b42318}.capability-grid{grid-template-columns:repeat(2,1fr)}.capability-grid button{flex-direction:column;gap:8px}.capability-grid button>span{flex-direction:row;justify-content:space-between}.capability-grid p{margin:0;color:#667085;font-size:12px;line-height:1.5}.context-guide{display:flex;flex-direction:column;gap:5px;margin:14px 16px 0;padding:11px 13px;border-left:3px solid #9a6200;background:#f8faff}.context-guide strong{font-size:13px}.context-guide span,.form-help span{color:#667085;font-size:12px}.form-help{display:flex;flex-direction:column;gap:4px;margin:0 22px 18px;padding:11px 13px;background:#f9fafb;border-radius:7px}.form-help strong{font-size:12px}@media(max-width:900px){.operations-page{padding:16px}.workspace{grid-template-columns:1fr}.type-nav{display:flex;overflow:auto;gap:4px}.type-group{display:contents}.type-group>span{display:none}.type-group button{white-space:nowrap}.page-header p{display:none}.flow-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:640px){.form-grid{grid-template-columns:1fr}.form-grid label.wide{grid-column:auto}.card-toolbar{gap:8px;align-items:flex-start;flex-direction:column;padding:12px}.batch-actions{width:100%}.batch-account-select{min-width:0!important;max-width:none;flex:1}.page-header h1{font-size:20px}.overview-intro{align-items:flex-start;flex-direction:column}.flow-grid,.capability-grid{grid-template-columns:1fr}.overview-stats{flex-direction:column}.table-pager{align-items:flex-start;flex-direction:column}.dialog-mask{padding:0}.editor{width:100%;height:100dvh;max-height:none;border-radius:0}}
.loading-bar{display:flex;align-items:center;gap:8px;margin:-6px 0 14px;color:#667085;font-size:12px}.loading-bar span{width:14px;height:14px;border:2px solid #d0d5dd;border-top-color:#9a6200;border-radius:50%;animation:operations-spin .7s linear infinite}.view-tabs button:disabled,.actions button:disabled{cursor:not-allowed;opacity:.45}.flow-grid button,.capability-grid button,.type-group button{transition:background-color .15s ease,border-color .15s ease,color .15s ease}.flow-grid button:hover,.capability-grid button:hover{border-color:#efd77f;background:#f8faff}.primary-btn:hover:not(:disabled){background:#714a00;border-color:#714a00}.secondary-btn:hover:not(:disabled){background:#f9fafb;border-color:#98a2b3}.primary-btn:disabled,.editor header button:disabled{cursor:not-allowed;opacity:.55}.form-hint{color:#667085;font-size:12px;line-height:1.4}.editor{box-shadow:0 20px 48px rgba(16,24,40,.18)}@keyframes operations-spin{to{transform:rotate(360deg)}}
.resource-state{display:grid;place-items:center;gap:8px;min-height:240px;padding:32px;color:#667085;text-align:center}.resource-state strong{color:#344054}.resource-state small{font-size:12px}.resource-spinner{width:22px;height:22px;border:2px solid #d0d5dd;border-top-color:#9a6200;border-radius:50%;animation:operations-spin .7s linear infinite}.resource-state--error{border-color:#f0b2aa;background:#fff8f7}.resource-state--error strong,.resource-state--error small{color:#912018}.refresh-warning{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px;padding:10px 12px;border:1px solid #fedf89;border-radius:8px;color:#93370d;background:#fffaeb;font-size:12px}
.task-filters{display:grid;grid-template-columns:minmax(120px,.7fr) minmax(240px,1.6fr) minmax(190px,1fr) auto;gap:12px;align-items:end;padding:14px 16px;border-bottom:1px solid #e4e7ec;background:#fcfcfd}.task-filters label{display:flex;flex-direction:column;gap:5px}.task-filters label span{font-size:12px;color:#475467;font-weight:600}.task-filters>div{display:flex;gap:8px}.task-filters input,.task-filters select{height:36px;padding:6px 9px}
.material-boundary{max-width:460px;color:#694b00!important;text-align:right}.material-detail{display:grid;width:min(1040px,100%);max-height:calc(100dvh - 40px);grid-template-rows:auto minmax(0,1fr) auto;overflow:hidden;border:1px solid #e4e7ec;border-radius:14px;background:#fff;box-shadow:0 24px 80px rgba(16,24,40,.2)}.material-detail>header,.material-detail>footer{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:15px 20px;border-bottom:1px solid #eaecf0}.material-detail>header h2{margin:3px 0 0}.material-detail>header small{color:#9a6200}.material-detail>header button{min-width:44px;min-height:44px;border:0;background:transparent;font-size:25px;cursor:pointer}.material-detail>main{min-height:0;overflow:auto;overscroll-behavior:contain;padding:4px 20px 24px}.material-detail>main section{padding:18px 0;border-bottom:1px solid #eaecf0}.material-detail h3{margin:0 0 12px;font-size:15px}.material-detail>footer{border-top:1px solid #eaecf0;border-bottom:0;color:#667085;background:#fcfcfd;font-size:12px}.material-detail__metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;border:0!important}.material-detail__metrics div,.material-detail dl div{padding:11px 12px;border:1px solid #eaecf0;border-radius:8px;background:#fcfcfd}.material-detail__metrics span,.material-detail__metrics strong{display:block}.material-detail__metrics span,.material-detail dt{color:#667085;font-size:11px}.material-detail__metrics strong{margin-top:5px}.material-detail dl{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:0}.material-detail dd{margin:5px 0 0;word-break:break-all}.material-detail__versions{display:flex;flex-direction:column;gap:8px}.material-detail__versions article{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 12px;border:1px solid #eaecf0;border-radius:8px}.material-detail__versions article>div{display:flex;flex-direction:column;gap:3px}.material-detail__versions span,.material-detail__versions small{color:#667085;font-size:11px}.material-detail__versions em{max-width:480px;color:#b54708;font-size:11px;font-style:normal}
@media(max-width:900px){.task-filters{grid-template-columns:1fr 1fr}.task-filters>div{align-self:end}}@media(max-width:640px){.task-filters{grid-template-columns:1fr}.task-filters>div button{flex:1}}
@media(max-width:640px){.material-boundary{text-align:left}.dialog-mask{padding:0}.material-detail{width:100%;height:100dvh;max-height:none;border:0;border-radius:0}.material-detail__metrics,.material-detail dl{grid-template-columns:1fr 1fr}.material-detail__versions article{align-items:flex-start;flex-direction:column}.material-detail>footer{align-items:flex-start;flex-direction:column}}
</style>
