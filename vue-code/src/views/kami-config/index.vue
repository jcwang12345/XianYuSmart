<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, inject, defineComponent, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import '@/styles/header-selectors.css'
import {
  getKamiConfigsByAccountId,
  saveKamiConfig,
  deleteKamiConfig,
  queryKamiItems,
  addKamiItem,
  batchImportKamiItems,
  deleteKamiItem,
  resetKamiItem,
  exportKamiItems,
  getKamiInventoryEvents,
  resetExternalSupplyCircuit,
  getExternalSupplyRequests,
  previewExternalSupplyResolution,
  resolveExternalSupplyRequest,
  type KamiConfig,
  type KamiItem,
  type KamiInventoryEvent,
  type ExternalSupplyRequest,
  type ExternalResolutionPreview
} from '@/api/kami-config'
import { newRequestId } from '@/api/matrix'
import { getAccountList } from '@/api/account'
import type { Account } from '@/types'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import { isLowStockConfig } from './kami-stock'

const route = useRoute()
const router = useRouter()

const accounts = ref<Account[]>([])
const selectedAccountId = ref<number | null>(null)
const kamiConfigs = ref<KamiConfig[]>([])
const configLoading = ref(false)
const lowStockOnly = ref(route.query.lowStock === '1')

const selectedConfigId = ref<number | null>(null)
const kamiItems = ref<KamiItem[]>([])
const itemsLoading = ref(false)

const showCreateDialog = ref(false)
const editingConfigId = ref<number>()
const createForm = ref({
  aliasName: '',
  sharingMode: 'PRIVATE' as 'PRIVATE' | 'SHARED',
  xianyuAccountIds: [] as number[],
  sourceType: 'LOCAL' as 'LOCAL' | 'API',
  externalApiUrl: '',
  externalApiHeaders: '{}',
  externalApiBody: '{\n  "orderId": "{orderId}",\n  "quantity": {quantity},\n  "requestToken": "{requestToken}"\n}',
  externalApiResultPath: 'data.cards',
  externalApiTimeoutSeconds: 10,
  externalDailyQuota: undefined as number | undefined,
  externalFailureThreshold: 3,
  externalCooldownSeconds: 300
})
const createLoading = ref(false)

const showImportDialog = ref(false)
const importContent = ref('')
const importLoading = ref(false)

const showAddDialog = ref(false)
const addContent = ref('')
const addLoading = ref(false)

const showAlertDialog = ref(false)
const alertForm = ref({
  alertEnabled: 0,
  alertThresholdType: 1,
  alertThresholdValue: 10,
  alertEmail: ''
})
const alertLoading = ref(false)

const showExportDialog = ref(false)
const exportStatus = ref<{ unused: boolean; used: boolean }>({ unused: true, used: true })

const detailTab = ref<'inventory' | 'events' | 'external'>('inventory')
const inventoryEvents = ref<KamiInventoryEvent[]>([])
const eventsLoading = ref(false)
const eventPage = ref(1)
const eventTotal = ref(0)
const circuitResetting = ref(false)
const externalRequests = ref<ExternalSupplyRequest[]>([])
const externalRequestsLoading = ref(false)
const externalRequestPage = ref(1)
const externalRequestTotal = ref(0)
const externalRequestStatus = ref('ALL')
const showResolutionDialog = ref(false)
const resolutionSaving = ref(false)
const resolutionRequest = ref<ExternalSupplyRequest | null>(null)
const resolutionPreview = ref<ExternalResolutionPreview | null>(null)
const resolutionAcknowledged = ref(false)
const resolutionForm = ref({
  decision: 'CONFIRMED_NOT_SUPPLIED' as 'CONFIRMED_SUPPLIED' | 'CONFIRMED_NOT_SUPPLIED',
  cardContents: '',
  note: ''
})

const isMobile = ref(false)
const rulesExpanded = ref(false)

const filterStatus = ref<number | undefined>(undefined)
const filterKeyword = ref('')

const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

// 导航栏注入 — 必须在 setup 顶层调用
const setHeaderContent = inject<(content: any) => void>('setHeaderContent')

const HeaderSelectors = defineComponent({
  setup() {
    return () => h('div', { class: 'header-selectors' }, [
      h('div', { class: 'header-select-wrap' }, [
        h('select', {
          class: 'header-select',
          onChange: (e: Event) => {
            const val = (e.target as HTMLSelectElement).value
            selectedAccountId.value = val ? parseInt(val) : null
          }
        }, [
          h('option', { value: '', disabled: true, selected: !selectedAccountId.value }, '账号'),
          ...accounts.value.map(acc =>
            h('option', {
              value: acc.id.toString(),
              selected: selectedAccountId.value === acc.id
            }, acc.accountNote || acc.unb)
          )
        ]),
        h(IconChevronDown, { class: 'header-select-icon' })
      ])
    ])
  }
})

const selectedConfig = computed(() => {
  return kamiConfigs.value.find(c => c.id === selectedConfigId.value)
})

const visibleKamiConfigs = computed(() => lowStockOnly.value
  ? kamiConfigs.value.filter(isLowStockConfig)
  : kamiConfigs.value)

const clearLowStockFilter = () => {
  lowStockOnly.value = false
  router.replace({ query: { ...route.query, lowStock: undefined } })
  if (!selectedConfigId.value && !isMobile.value && visibleKamiConfigs.value[0]) {
    selectedConfigId.value = visibleKamiConfigs.value[0].id
    loadKamiItems()
  }
}

const resetConfigForm = () => {
  editingConfigId.value = undefined
  createForm.value = {
    aliasName: '',
    sharingMode: 'PRIVATE',
    xianyuAccountIds: selectedAccountId.value ? [selectedAccountId.value] : [],
    sourceType: 'LOCAL',
    externalApiUrl: '',
    externalApiHeaders: '{}',
    externalApiBody: '{\n  "orderId": "{orderId}",\n  "quantity": {quantity},\n  "requestToken": "{requestToken}"\n}',
    externalApiResultPath: 'data.cards',
    externalApiTimeoutSeconds: 10,
    externalDailyQuota: undefined,
    externalFailureThreshold: 3,
    externalCooldownSeconds: 300
  }
}

const openCreateDialog = () => {
  resetConfigForm()
  showCreateDialog.value = true
}

const openSourceConfigDialog = () => {
  if (!selectedConfig.value) return
  const config = selectedConfig.value
  editingConfigId.value = config.id
  createForm.value = {
    aliasName: config.aliasName || '',
    sharingMode: config.sharingMode || 'PRIVATE',
    xianyuAccountIds: config.xianyuAccountIds?.length ? [...config.xianyuAccountIds] : [config.xianyuAccountId],
    sourceType: config.sourceType || 'LOCAL',
    externalApiUrl: config.externalApiUrl || '',
    externalApiHeaders: '',
    externalApiBody: config.externalApiBodySensitiveConfigured ? '' : (config.externalApiBody || '{\n  "orderId": "{orderId}",\n  "quantity": {quantity},\n  "requestToken": "{requestToken}"\n}'),
    externalApiResultPath: config.externalApiResultPath || 'data.cards',
    externalApiTimeoutSeconds: config.externalApiTimeoutSeconds || 10,
    externalDailyQuota: config.externalDailyQuota,
    externalFailureThreshold: config.externalFailureThreshold || 3,
    externalCooldownSeconds: config.externalCooldownSeconds || 300
  }
  showCreateDialog.value = true
}

const loadAccounts = async () => {
  try {
    const res = await getAccountList()
    if (res.code === 200 && res.data) {
      accounts.value = res.data.accounts || []
      if (accounts.value.length > 0 && !selectedAccountId.value) {
        selectedAccountId.value = accounts.value[0]!.id
      }
    }
  } catch (e) {
    console.error('加载账号失败', e)
  }
}

const loadKamiConfigs = async () => {
  if (!selectedAccountId.value) return
  configLoading.value = true
  try {
    const res = await getKamiConfigsByAccountId(selectedAccountId.value)
    if (res.code === 200) {
      kamiConfigs.value = res.data || []
      if (selectedConfigId.value && !visibleKamiConfigs.value.some(config => config.id === selectedConfigId.value)) {
        selectedConfigId.value = null
        kamiItems.value = []
      }
      if (visibleKamiConfigs.value.length > 0 && !selectedConfigId.value && !isMobile.value) {
        selectedConfigId.value = visibleKamiConfigs.value[0]!.id
        loadKamiItems()
      } else if (visibleKamiConfigs.value.length === 0) {
        selectedConfigId.value = null
        kamiItems.value = []
      }
    }
  } catch (e) {
    console.error('加载卡密配置失败', e)
  } finally {
    configLoading.value = false
  }
}

const loadKamiItems = async () => {
  if (!selectedConfigId.value) return
  itemsLoading.value = true
  try {
    const res = await queryKamiItems({
      kamiConfigId: selectedConfigId.value,
      status: filterStatus.value,
      keyword: filterKeyword.value || undefined
    })
    if (res.code === 200) {
      kamiItems.value = res.data || []
    }
  } catch (e) {
    console.error('加载卡密列表失败', e)
  } finally {
    itemsLoading.value = false
  }
}

const handleAccountChange = () => {
  selectedConfigId.value = null
  kamiItems.value = []
  loadKamiConfigs()
}

const selectConfig = (config: KamiConfig) => {
  selectedConfigId.value = config.id
  filterStatus.value = undefined
  filterKeyword.value = ''
  detailTab.value = 'inventory'
  eventPage.value = 1
  inventoryEvents.value = []
  externalRequests.value = []
  loadKamiItems()
}

const loadInventoryEvents = async () => {
  if (!selectedConfigId.value) return
  eventsLoading.value = true
  try {
    const res = await getKamiInventoryEvents(selectedConfigId.value, eventPage.value, 20)
    if (res.code === 200 && res.data) {
      inventoryEvents.value = res.data.events || []
      eventTotal.value = res.data.total || 0
    }
  } catch (e) {
    toast.error('库存事件加载失败')
  } finally {
    eventsLoading.value = false
  }
}

const switchDetailTab = (tab: 'inventory' | 'events' | 'external') => {
  detailTab.value = tab
  if (tab === 'events') loadInventoryEvents()
  if (tab === 'external') loadExternalRequests()
}

const itemStatusLabel = (status: number) => ({ 0: '可用', 1: '已交付', 2: '已预占', 3: '待核对' }[status] || '未知')
const eventLabel = (type: string) => ({
  RESERVED: '库存已预占', RESERVED_EXTERNAL: '外部供货已预占', CONSUMED: '库存已消费',
  RELEASED: '预占已释放', REVIEW_REQUIRED: '转人工核对', SUPPLY_UNKNOWN: '外部供货结果未知',
  SUPPLY_FAILED: '外部供货失败', CIRCUIT_RESET: '人工重置熔断',
  MANUAL_SUPPLY_ATTACHED: '人工附加供货并转核对', MANUAL_SUPPLY_NOT_SUPPLIED: '人工确认未出卡',
  IMPORTED: '库存已导入', DELETED: '库存项已删除', RESET_AVAILABLE: '库存项已重置为可用'
}[type] || type)

const externalStatusLabel = (status: string) => ({
  PROCESSING: '处理中', FAILED: '已知失败', REVIEW_REQUIRED: '结果未知', SUCCESS: '供货成功',
  MANUAL_NOT_SUPPLIED: '人工确认未出卡', MANUAL_SUPPLIED_REVIEW: '已附加，待人工核对'
}[status] || status)

const externalRequestPageCount = computed(() => Math.max(1, Math.ceil(externalRequestTotal.value / 20)))
const loadExternalRequests = async () => {
  if (!selectedConfigId.value || selectedConfig.value?.sourceType !== 'API') return
  externalRequestsLoading.value = true
  try {
    const res = await getExternalSupplyRequests(selectedConfigId.value, externalRequestStatus.value,
      externalRequestPage.value, 20)
    if (res.code === 200 && res.data) {
      externalRequests.value = res.data.requests || []
      externalRequestTotal.value = res.data.total || 0
    } else toast.error(res.msg || '外部供货请求加载失败')
  } catch { toast.error('外部供货请求加载失败') }
  finally { externalRequestsLoading.value = false }
}

const changeExternalRequestPage = async (page: number) => {
  externalRequestPage.value = Math.min(externalRequestPageCount.value, Math.max(1, page))
  await loadExternalRequests()
}

const openResolutionDialog = (request: ExternalSupplyRequest) => {
  resolutionRequest.value = request
  resolutionPreview.value = null
  resolutionAcknowledged.value = false
  resolutionForm.value = { decision: 'CONFIRMED_NOT_SUPPLIED', cardContents: '', note: '' }
  showResolutionDialog.value = true
}

const invalidateResolutionPreview = () => {
  resolutionPreview.value = null
  resolutionAcknowledged.value = false
}

const previewResolution = async () => {
  if (!resolutionRequest.value) return
  resolutionSaving.value = true
  try {
    const res = await previewExternalSupplyResolution(resolutionRequest.value.id, resolutionForm.value.decision)
    if (res.code === 200 && res.data) resolutionPreview.value = res.data
    else toast.error(res.msg || '人工处置预检失败')
  } catch { toast.error('人工处置预检失败') }
  finally { resolutionSaving.value = false }
}

const confirmResolution = async () => {
  if (!resolutionRequest.value || !resolutionPreview.value || !resolutionAcknowledged.value) return
  const cards = resolutionForm.value.cardContents.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
  resolutionSaving.value = true
  try {
    const res = await resolveExternalSupplyRequest(resolutionRequest.value.id, {
      decision: resolutionForm.value.decision,
      confirmationText: resolutionPreview.value.confirmationText,
      cardContents: cards,
      note: resolutionForm.value.note || undefined,
      requestId: newRequestId('external-supply-resolution')
    })
    if (res.code === 200) {
      toast.success(resolutionForm.value.decision === 'CONFIRMED_SUPPLIED'
        ? '卡密已附加到待人工核对区，不会自动发送' : '已确认未出卡，可安全进入后续重试')
      showResolutionDialog.value = false
      await Promise.all([loadExternalRequests(), loadKamiItems(), loadKamiConfigs(), loadInventoryEvents()])
    } else toast.error(res.msg || '人工处置失败')
  } catch { toast.error('人工处置失败') }
  finally { resolutionSaving.value = false }
}

const handleResetCircuit = async () => {
  if (!selectedConfigId.value || !selectedConfig.value) return
  try {
    await showConfirm(
      `重置「${selectedConfig.value.aliasName || selectedConfigId.value}」的外部供货熔断？请先确认供应商侧没有未核对订单。`,
      '重置供货熔断'
    )
  } catch { return }
  circuitResetting.value = true
  try {
    const res = await resetExternalSupplyCircuit(selectedConfigId.value, newRequestId('kami-circuit-reset'))
    if (res.code === 200) {
      toast.success('熔断已重置，下一笔请求将重新尝试供货')
      await loadKamiConfigs()
      if (detailTab.value === 'events') await loadInventoryEvents()
    } else toast.error(res.msg || '熔断重置失败')
  } catch (e) {
    toast.error('熔断重置失败')
  } finally {
    circuitResetting.value = false
  }
}

const handleCreate = async () => {
  if (!selectedAccountId.value) {
    toast.warning('请先选择账号')
    return
  }
  if (createForm.value.sharingMode === 'SHARED' && createForm.value.xianyuAccountIds.length < 2) {
    toast.warning('共享库存池请至少选择两个账号')
    return
  }
  createLoading.value = true
  try {
    const res = await saveKamiConfig({
      id: editingConfigId.value,
      xianyuAccountId: selectedAccountId.value,
      xianyuAccountIds: createForm.value.sharingMode === 'SHARED'
        ? createForm.value.xianyuAccountIds
        : [selectedAccountId.value],
      sharingMode: createForm.value.sharingMode,
      aliasName: createForm.value.aliasName || '未命名',
      sourceType: createForm.value.sourceType,
      externalApiUrl: createForm.value.sourceType === 'API' ? createForm.value.externalApiUrl : undefined,
      externalApiHeaders: createForm.value.sourceType === 'API' ? createForm.value.externalApiHeaders : undefined,
      externalApiBody: createForm.value.sourceType === 'API'
        ? (editingConfigId.value && selectedConfig.value?.externalApiBodySensitiveConfigured
          && !createForm.value.externalApiBody.trim() ? undefined : createForm.value.externalApiBody)
        : undefined,
      externalApiResultPath: createForm.value.sourceType === 'API' ? createForm.value.externalApiResultPath : undefined,
      externalApiTimeoutSeconds: createForm.value.sourceType === 'API' ? createForm.value.externalApiTimeoutSeconds : undefined,
      externalDailyQuota: createForm.value.sourceType === 'API' ? createForm.value.externalDailyQuota : undefined,
      externalFailureThreshold: createForm.value.sourceType === 'API' ? createForm.value.externalFailureThreshold : undefined,
      externalCooldownSeconds: createForm.value.sourceType === 'API' ? createForm.value.externalCooldownSeconds : undefined,
      requestId: newRequestId('kami-config')
    })
    if (res.code === 200) {
      toast.success(editingConfigId.value ? '卡密来源已更新' : '创建成功')
      showCreateDialog.value = false
      resetConfigForm()
      await loadKamiConfigs()
      if (res.data?.id) {
        selectedConfigId.value = res.data.id
        loadKamiItems()
      }
    } else {
      toast.error(res.msg || '创建失败')
    }
  } catch (e) {
    toast.error('创建失败')
  } finally {
    createLoading.value = false
  }
}

const handleDeleteConfig = async (config: KamiConfig) => {
  try {
    await showConfirm(
      `确定删除卡密配置「${config.aliasName || config.id}」及其所有卡密？`,
      '删除确认'
    )
    const res = await deleteKamiConfig(config.id, newRequestId('kami-config-delete'))
    if (res.code === 200) {
      toast.success('删除成功')
      if (selectedConfigId.value === config.id) {
        selectedConfigId.value = null
        kamiItems.value = []
      }
      loadKamiConfigs()
    } else {
      toast.error(res.msg || '删除失败')
    }
  } catch {}
}

const handleAddKami = async () => {
  if (!addContent.value.trim()) {
    toast.warning('请输入卡密内容')
    return
  }
  addLoading.value = true
  try {
    const res = await addKamiItem({
      kamiConfigId: selectedConfigId.value!,
      kamiContent: addContent.value.trim(),
      requestId: newRequestId('kami-item-add')
    })
    if (res.code === 200) {
      toast.success('添加成功')
      showAddDialog.value = false
      addContent.value = ''
      loadKamiItems()
      loadKamiConfigs()
    } else {
      toast.error(res.msg || '添加失败')
    }
  } catch (e) {
    toast.error('添加失败')
  } finally {
    addLoading.value = false
  }
}

const handleBatchImport = async () => {
  if (!importContent.value.trim()) {
    toast.warning('请输入卡密内容')
    return
  }
  importLoading.value = true
  try {
    const res = await batchImportKamiItems({
      kamiConfigId: selectedConfigId.value!,
      kamiContents: importContent.value,
      requestId: newRequestId('kami-batch-import')
    })
    if (res.code === 200) {
      toast.success(res.msg || '导入成功')
      showImportDialog.value = false
      importContent.value = ''
      loadKamiItems()
      loadKamiConfigs()
    } else {
      toast.error(res.msg || '导入失败')
    }
  } catch (e) {
    toast.error('导入失败')
  } finally {
    importLoading.value = false
  }
}

const handleDeleteItem = async (item: KamiItem) => {
  try {
    await showConfirm('确定删除该卡密？', '删除确认')
    const res = await deleteKamiItem(item.id, newRequestId('kami-item-delete'))
    if (res.code === 200) {
      toast.success('删除成功')
      loadKamiItems()
      loadKamiConfigs()
    } else {
      toast.error(res.msg || '删除失败')
    }
  } catch {}
}

const handleResetItem = async (item: KamiItem) => {
  try {
    await showConfirm('确定重置该卡密为未使用状态？', '重置确认')
    const res = await resetKamiItem(item.id, newRequestId('kami-item-reset'))
    if (res.code === 200) {
      toast.success('重置成功')
      loadKamiItems()
      loadKamiConfigs()
    } else {
      toast.error(res.msg || '重置失败')
    }
  } catch {}
}

const handleFilterChange = () => {
  loadKamiItems()
}

const eventPageCount = computed(() => Math.max(1, Math.ceil(eventTotal.value / 20)))
const changeEventPage = async (page: number) => {
  const target = Math.min(eventPageCount.value, Math.max(1, page))
  if (target === eventPage.value) return
  eventPage.value = target
  await loadInventoryEvents()
}

const openAlertDialog = () => {
  if (!selectedConfig.value) return
  alertForm.value = {
    alertEnabled: selectedConfig.value.alertEnabled || 0,
    alertThresholdType: selectedConfig.value.alertThresholdType || 1,
    alertThresholdValue: selectedConfig.value.alertThresholdValue || 10,
    alertEmail: selectedConfig.value.alertEmail || ''
  }
  showAlertDialog.value = true
}

const handleSaveAlert = async () => {
  if (!selectedConfigId.value) return
  alertLoading.value = true
  try {
    const res = await saveKamiConfig({
      id: selectedConfigId.value,
      xianyuAccountId: selectedAccountId.value!,
      aliasName: selectedConfig.value?.aliasName,
      sourceType: selectedConfig.value?.sourceType || 'LOCAL',
      externalApiUrl: selectedConfig.value?.externalApiUrl,
      externalApiHeaders: selectedConfig.value?.externalApiHeaders,
      externalApiBody: selectedConfig.value?.externalApiBody,
      externalApiResultPath: selectedConfig.value?.externalApiResultPath,
      externalApiTimeoutSeconds: selectedConfig.value?.externalApiTimeoutSeconds,
      externalDailyQuota: selectedConfig.value?.externalDailyQuota,
      externalFailureThreshold: selectedConfig.value?.externalFailureThreshold,
      externalCooldownSeconds: selectedConfig.value?.externalCooldownSeconds,
      requestId: newRequestId('kami-alert'),
      alertEnabled: alertForm.value.alertEnabled,
      alertThresholdType: alertForm.value.alertThresholdType,
      alertThresholdValue: alertForm.value.alertThresholdValue,
      alertEmail: alertForm.value.alertEmail
    })
    if (res.code === 200) {
      toast.success('设置保存成功')
      showAlertDialog.value = false
      loadKamiConfigs()
    } else {
      toast.error(res.msg || '保存失败')
    }
  } catch (e) {
    toast.error('保存失败')
  } finally {
    alertLoading.value = false
  }
}

const openExportDialog = () => {
  exportStatus.value = { unused: true, used: true }
  showExportDialog.value = true
}

const handleExport = async () => {
  if (!selectedConfigId.value) return
  if (!exportStatus.value.unused && !exportStatus.value.used) {
    toast.warning('请至少选择一种状态')
    return
  }

  try {
    const res = await exportKamiItems({
      kamiConfigId: selectedConfigId.value,
      includeUnused: exportStatus.value.unused,
      includeUsed: exportStatus.value.used,
      requestId: newRequestId('kami-export')
    })
    const allItems = res.data || []

    if (allItems.length === 0) {
      toast.warning('没有可导出的数据')
      return
    }

    const configName = selectedConfig.value?.aliasName || `配置${selectedConfigId.value}`
    const timestamp = new Date().toISOString().slice(0, 19).replace(/[:-]/g, '').replace('T', '_')

    const header = '序号\t卡密内容\t状态\t订单ID\t使用时间\t添加时间\n'
    const rows = allItems.map(item =>
      `${item.sortOrder}\t${item.kamiContent}\t${item.status === 0 ? '未使用' : '已使用'}\t${item.orderId || ''}\t${item.usedTime || ''}\t${item.createTime}`
    ).join('\n')
    const content = header + rows
    const blob = new Blob(['\ufeff' + content], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${configName}_${timestamp}.txt`
    a.click()
    URL.revokeObjectURL(url)
    toast.success(`已导出 ${allItems.length} 条数据`)
    showExportDialog.value = false
  } catch (e) {
    toast.error('导出失败')
  }
}

watch(selectedAccountId, () => {
  if (selectedAccountId.value) {
    selectedConfigId.value = null
    kamiItems.value = []
    loadKamiConfigs()
  }
})

onMounted(async () => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
  if (setHeaderContent) setHeaderContent(HeaderSelectors)
  await loadAccounts()
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})
</script>

<template>
  <div class="kami-page">

    <div v-if="lowStockOnly" class="kami-page__filter-notice">
      <span>当前仅显示已触发预警的低库存仓库</span>
      <button class="btn-text" @click="clearLowStockFilter">查看全部</button>
    </div>

    <!-- ===== 手机端 ===== -->
    <template v-if="isMobile">

      <!-- 配置列表视图 -->
      <div v-if="!selectedConfigId" class="kami-mobile">
        <header class="kami-mobile__header">
          <div class="kami-mobile__header-top">
            <h1 class="kami-page__title">卡密仓库</h1>
            <button class="btn-primary btn-sm" @click="openCreateDialog" :disabled="!selectedAccountId">
              新建
            </button>
          </div>
        </header>

        <div class="kami-mobile__list">
          <div v-if="configLoading" class="kami-page__empty">加载中...</div>
          <div v-else-if="visibleKamiConfigs.length === 0" class="kami-page__empty">{{ lowStockOnly ? '暂无低库存仓库' : '暂无配置，点击右上角新建' }}</div>
          <div
            v-for="config in visibleKamiConfigs"
            :key="config.id"
            class="config-card"
            @click="selectConfig(config)"
          >
            <div class="config-card__name">{{ config.aliasName || `配置#${config.id}` }}</div>
            <div class="config-card__stats">
              <span v-if="config.sourceType === 'API'" class="tag tag--info">接口供货</span>
              <span class="config-card__stat">总量 {{ config.totalCount }}</span>
              <span class="config-card__stat used">已用 {{ config.usedCount }}</span>
              <span class="config-card__stat avail">可用 {{ config.availableCount }}</span>
              <span v-if="isLowStockConfig(config)" class="tag tag--warning" style="margin-left: 4px;">低库存</span>
            </div>
            <button
              class="config-card__del btn-danger btn-text btn-sm"
              @click.stop="handleDeleteConfig(config)"
            >删除</button>
          </div>
        </div>
      </div>

      <!-- 卡密详情视图 -->
      <div v-else class="kami-mobile">
        <header class="kami-mobile__header">
          <div class="kami-mobile__header-top">
            <button class="kami-mobile__back" @click="selectedConfigId = null; kamiItems = []">
              ← 返回
            </button>
            <span class="kami-mobile__config-name">{{ selectedConfig?.aliasName || `配置#${selectedConfigId}` }}</span>
          </div>
          <div class="kami-mobile__detail-actions">
            <button v-if="selectedConfig?.sourceType !== 'API'" class="btn-default btn-sm" @click="showAddDialog = true">添加</button>
            <button v-if="selectedConfig?.sourceType !== 'API'" class="btn-primary btn-sm" @click="showImportDialog = true">批量导入</button>
            <button class="btn-default btn-sm" @click="openSourceConfigDialog">来源配置</button>
            <button class="btn-success btn-sm" @click="openExportDialog">导出</button>
            <button class="btn-warning btn-sm" @click="openAlertDialog">预警</button>
          </div>
        </header>

        <section v-if="selectedConfig" class="kami-health kami-health--mobile" aria-label="库存状态摘要">
          <div><strong>r{{ selectedConfig.configVersion || 1 }}</strong><span>配置版本</span></div>
          <div><strong>{{ selectedConfig.availableCount }}</strong><span>可用</span></div>
          <div><strong>{{ selectedConfig.reservedCount || 0 }}</strong><span>预占</span></div>
          <div><strong>{{ selectedConfig.reviewRequiredCount || 0 }}</strong><span>待核对</span></div>
        </section>
        <div class="kami-tabs" role="tablist" aria-label="卡密仓库详情">
          <button role="tab" :aria-selected="detailTab === 'inventory'" :class="{ active: detailTab === 'inventory' }" @click="switchDetailTab('inventory')">库存明细</button>
          <button role="tab" :aria-selected="detailTab === 'events'" :class="{ active: detailTab === 'events' }" @click="switchDetailTab('events')">事件记录</button>
          <button v-if="selectedConfig?.sourceType === 'API'" role="tab" :aria-selected="detailTab === 'external'" :class="{ active: detailTab === 'external' }" @click="switchDetailTab('external')">供货请求</button>
        </div>

        <template v-if="detailTab === 'inventory'">
        <div class="kami-mobile__filters">
          <select
            v-model="filterStatus"
            class="native-select"
            style="flex: 1;"
            @change="handleFilterChange"
          >
            <option :value="undefined">全部状态</option>
            <option :value="0">未使用</option>
            <option :value="1">已使用</option>
            <option :value="2">已预占</option>
            <option :value="3">待核对</option>
          </select>
          <input
            v-model="filterKeyword"
            class="native-input"
            placeholder="搜索卡密"
            style="flex: 2;"
            @keyup.enter="handleFilterChange"
          />
          <button class="btn-default" @click="handleFilterChange">搜索</button>
        </div>

        <div class="kami-mobile__items">
          <div v-if="itemsLoading" class="kami-page__empty">加载中...</div>
          <div v-else-if="kamiItems.length === 0" class="kami-page__empty">暂无卡密</div>
          <div
            v-for="item in kamiItems"
            :key="item.id"
            class="kami-item-card"
            :class="{ 'kami-item-card--used': item.status === 1 }"
          >
            <div class="kami-item-card__content">{{ item.kamiContent }}</div>
            <div class="kami-item-card__meta">
              <span class="tag" :class="`kami-status--${item.status}`">
                {{ itemStatusLabel(item.status) }}
              </span>
              <span v-if="item.sourceConfigVersion">r{{ item.sourceConfigVersion }}</span>
              <span v-if="item.usedTime" class="kami-item-card__time">{{ item.usedTime }}</span>
            </div>
            <div class="kami-item-card__actions">
              <button v-if="item.status === 1" class="btn-warning btn-text btn-sm" @click="handleResetItem(item)">重置</button>
              <button v-if="item.status !== 2 && item.status !== 3" class="btn-danger btn-text btn-sm" @click="handleDeleteItem(item)">删除</button>
            </div>
          </div>
        </div>
        </template>
        <div v-else-if="detailTab === 'events'" class="kami-event-list" role="tabpanel">
          <div v-if="eventsLoading" class="kami-page__empty">正在加载库存事件…</div>
          <div v-else-if="inventoryEvents.length === 0" class="kami-page__empty">暂无库存事件；导入后发生预占、消费或释放时会记录在这里。</div>
          <article v-for="event in inventoryEvents" :key="event.id" class="kami-event">
            <div class="kami-event__top"><strong>{{ eventLabel(event.eventType) }}</strong><span>{{ event.createdTime }}</span></div>
            <div class="kami-event__meta"><span>账号 {{ event.accountId || '—' }}</span><span>订单 {{ event.orderId || '—' }}</span><span>r{{ event.configVersion || '—' }}</span></div>
            <div class="kami-event__request">请求 {{ event.requestId }}</div>
          </article>
          <div v-if="eventTotal > 20" class="kami-event__pager">
            <button class="btn-default btn-sm" :disabled="eventPage <= 1" @click="changeEventPage(eventPage - 1)">上一页</button>
            <span>{{ eventPage }} / {{ eventPageCount }}</span>
            <button class="btn-default btn-sm" :disabled="eventPage >= eventPageCount" @click="changeEventPage(eventPage + 1)">下一页</button>
          </div>
        </div>
        <div v-else class="external-request-list" role="tabpanel">
          <div class="external-request-toolbar"><select v-model="externalRequestStatus" class="native-select" @change="externalRequestPage=1;loadExternalRequests()"><option value="ALL">全部状态</option><option value="REVIEW_REQUIRED">结果未知</option><option value="FAILED">已知失败</option><option value="SUCCESS">供货成功</option><option value="MANUAL_NOT_SUPPLIED">人工确认未出卡</option><option value="MANUAL_SUPPLIED_REVIEW">已附加待核对</option></select></div>
          <div v-if="externalRequestsLoading" class="kami-page__empty">正在读取脱敏供货状态…</div>
          <div v-else-if="!externalRequests.length" class="kami-page__empty">暂无外部供货请求。</div>
          <article v-for="request in externalRequests" :key="request.id" class="external-request-card">
            <header><strong>订单 {{ request.orderId }}</strong><span :class="{ danger: request.resultUnknown === 1 }">{{ externalStatusLabel(request.requestStatus) }}</span></header>
            <p>账号 {{ request.accountId }} · 数量 {{ request.quantity }} · 尝试 {{ request.attemptCount }} 次</p><small>{{ request.errorMessage || '无错误详情' }}</small>
            <footer><time>{{ request.updateTime }}</time><button v-if="request.resultUnknown === 1 || request.requestStatus === 'REVIEW_REQUIRED'" class="btn-warning btn-sm" @click="openResolutionDialog(request)">人工核对</button></footer>
          </article>
          <div v-if="externalRequestTotal > 20" class="kami-event__pager"><button class="btn-default btn-sm" :disabled="externalRequestPage<=1" @click="changeExternalRequestPage(externalRequestPage-1)">上一页</button><span>{{ externalRequestPage }} / {{ externalRequestPageCount }}</span><button class="btn-default btn-sm" :disabled="externalRequestPage>=externalRequestPageCount" @click="changeExternalRequestPage(externalRequestPage+1)">下一页</button></div>
        </div>
      </div>

    </template>

    <!-- ===== 桌面端 ===== -->
    <template v-else>
      <header class="kami-page__header">
        <h1 class="kami-page__title">卡密仓库</h1>
        <div class="kami-page__actions">
          <select
            v-model="selectedAccountId"
            class="account-select native-select"
            @change="handleAccountChange"
          >
            <option value="" disabled>选择账号</option>
            <option
              v-for="acc in accounts"
              :key="acc.id"
              :value="acc.id"
            >{{ acc.accountNote || `账号${acc.id}` }}</option>
          </select>
          <button class="btn-primary" @click="openCreateDialog" :disabled="!selectedAccountId">
            新建密钥仓库
          </button>
        </div>
      </header>

      <div class="kami-page__body">
        <div class="kami-page__sidebar">
          <div v-if="configLoading" class="kami-page__empty">加载中...</div>
          <div v-else-if="visibleKamiConfigs.length === 0" class="kami-page__empty">{{ lowStockOnly ? '暂无低库存仓库' : '暂无配置，点击右上角新建' }}</div>
          <div
            v-for="config in visibleKamiConfigs"
            :key="config.id"
            class="config-card"
            :class="{ 'config-card--active': selectedConfigId === config.id }"
            @click="selectConfig(config)"
          >
            <div class="config-card__name">{{ config.aliasName || `配置#${config.id}` }}</div>
            <div class="config-card__stats">
              <span v-if="config.sourceType === 'API'" class="tag tag--info">接口供货</span>
              <span class="config-card__stat">总量 {{ config.totalCount }}</span>
              <span class="config-card__stat used">已用 {{ config.usedCount }}</span>
              <span class="config-card__stat avail">可用 {{ config.availableCount }}</span>
              <span v-if="isLowStockConfig(config)" class="tag tag--warning" style="margin-left: 4px;">低库存</span>
            </div>
            <button
              class="config-card__del btn-danger btn-text btn-sm"
              @click.stop="handleDeleteConfig(config)"
            >删除</button>
          </div>
        </div>

        <div class="kami-page__main">
          <div v-if="!selectedConfig" class="kami-page__empty-main">请选择左侧卡密配置</div>
          <template v-else>
            <div class="kami-detail__header">
              <div>
                <h2>{{ selectedConfig.aliasName || `配置#${selectedConfig.id}` }}</h2>
                <p class="kami-detail__subtitle">{{ selectedConfig.sourceType === 'API' ? '外部接口供货' : '本地原子库存' }} · 配置版本 r{{ selectedConfig.configVersion || 1 }}</p>
              </div>
              <div class="kami-detail__actions">
                <button v-if="selectedConfig.sourceType !== 'API'" class="btn-default" @click="showAddDialog = true">添加卡密</button>
                <button v-if="selectedConfig.sourceType !== 'API'" class="btn-primary" @click="showImportDialog = true">批量导入</button>
                <button class="btn-default" @click="openSourceConfigDialog">来源配置</button>
                <button class="btn-success" @click="openExportDialog">导出</button>
                <button class="btn-warning" @click="openAlertDialog">预警配置</button>
              </div>
            </div>

            <section class="kami-health" aria-label="库存状态摘要">
              <div><strong>{{ selectedConfig.totalCount }}</strong><span>总库存</span></div>
              <div><strong>{{ selectedConfig.availableCount }}</strong><span>可用</span></div>
              <div><strong>{{ selectedConfig.reservedCount || 0 }}</strong><span>已预占</span></div>
              <div :class="{ danger: (selectedConfig.reviewRequiredCount || 0) > 0 }"><strong>{{ selectedConfig.reviewRequiredCount || 0 }}</strong><span>待人工核对</span></div>
              <div v-if="selectedConfig.sourceType === 'API'" :class="{ danger: selectedConfig.externalCircuitState === 'OPEN', warning: selectedConfig.externalCircuitState === 'HALF_OPEN' }">
                <strong>{{ selectedConfig.externalCircuitState === 'OPEN' ? '已熔断' : selectedConfig.externalCircuitState === 'HALF_OPEN' ? '探测中' : '正常' }}</strong>
                <span>外部供货 · 今日 {{ selectedConfig.externalQuotaUsed || 0 }}/{{ selectedConfig.externalDailyQuota || '不限' }}</span>
              </div>
              <button v-if="selectedConfig.sourceType === 'API' && selectedConfig.externalCircuitState === 'OPEN'" class="btn-warning btn-sm" :disabled="circuitResetting" @click="handleResetCircuit">人工复核后重置</button>
            </section>

            <div class="kami-tabs" role="tablist" aria-label="卡密仓库详情">
              <button role="tab" :aria-selected="detailTab === 'inventory'" :class="{ active: detailTab === 'inventory' }" @click="switchDetailTab('inventory')">库存明细</button>
              <button role="tab" :aria-selected="detailTab === 'events'" :class="{ active: detailTab === 'events' }" @click="switchDetailTab('events')">事件记录 <span v-if="eventTotal">{{ eventTotal }}</span></button>
              <button v-if="selectedConfig.sourceType === 'API'" role="tab" :aria-selected="detailTab === 'external'" :class="{ active: detailTab === 'external' }" @click="switchDetailTab('external')">供货请求 <span v-if="externalRequestTotal">{{ externalRequestTotal }}</span></button>
            </div>

            <template v-if="detailTab === 'inventory'">
            <div class="kami-detail__filters">
              <select
                v-model="filterStatus"
                class="native-select"
                style="width: 120px; margin-right: 8px;"
                @change="handleFilterChange"
              >
                <option :value="undefined">全部状态</option>
                <option :value="0">未使用</option>
                <option :value="1">已使用</option>
                <option :value="2">已预占</option>
                <option :value="3">待核对</option>
              </select>
              <input
                v-model="filterKeyword"
                class="native-input"
                placeholder="搜索卡密内容"
                style="width: 200px; margin-right: 8px;"
                @keyup.enter="handleFilterChange"
              />
              <button class="btn-default" @click="handleFilterChange">搜索</button>
            </div>

            <div class="kami-detail__table">
              <div v-if="itemsLoading" class="kami-page__empty">加载中...</div>
              <template v-else>
                <div v-if="kamiItems.length === 0" class="kami-page__empty">暂无卡密</div>
                <table v-else class="kami-table">
                  <thead>
                    <tr>
                      <th>序号</th>
                      <th>卡密内容</th>
                      <th>状态</th>
                      <th>订单ID</th>
                      <th>使用时间</th>
                      <th>添加时间</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="item in kamiItems" :key="item.id" :class="{ 'kami-table__row--used': item.status === 1 }">
                      <td class="kami-table__cell--num">{{ item.sortOrder }}</td>
                      <td class="kami-table__cell--content">{{ item.kamiContent }}</td>
                      <td>
                        <span class="kami-table__status" :class="`kami-status--${item.status}`">
                          {{ itemStatusLabel(item.status) }}
                        </span>
                      </td>
                      <td class="kami-table__cell--id">{{ item.orderId || '-' }}</td>
                      <td class="kami-table__cell--time">{{ item.usedTime || '-' }}</td>
                      <td class="kami-table__cell--time">{{ item.createTime }}</td>
                      <td>
                        <div class="kami-table__actions">
                          <button v-if="item.status === 1" class="kami-table__action-btn kami-table__action-btn--reset" @click="handleResetItem(item)">重置</button>
                          <button v-if="item.status !== 2 && item.status !== 3" class="kami-table__action-btn kami-table__action-btn--delete" @click="handleDeleteItem(item)">删除</button>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </template>
            </div>
            </template>
            <div v-else-if="detailTab === 'events'" class="kami-event-list kami-event-list--desktop" role="tabpanel">
              <div v-if="eventsLoading" class="kami-page__empty">正在加载库存事件…</div>
              <div v-else-if="inventoryEvents.length === 0" class="kami-page__empty">暂无库存事件；预占、消费、释放、待核对和外部供货熔断会记录在这里。</div>
              <article v-for="event in inventoryEvents" :key="event.id" class="kami-event">
                <div class="kami-event__top"><strong>{{ eventLabel(event.eventType) }}</strong><span>{{ event.createdTime }}</span></div>
                <div class="kami-event__meta"><span>账号 {{ event.accountId || '—' }}</span><span>订单 {{ event.orderId || '—' }}</span><span>版本 r{{ event.configVersion || '—' }}</span><span>来源 {{ event.source }}</span></div>
                <div class="kami-event__request">请求 ID：{{ event.requestId }}</div>
              </article>
              <div v-if="eventTotal > 20" class="kami-event__pager">
                <button class="btn-default btn-sm" :disabled="eventPage <= 1" @click="changeEventPage(eventPage - 1)">上一页</button>
                <span>{{ eventPage }} / {{ eventPageCount }}</span>
                <button class="btn-default btn-sm" :disabled="eventPage >= eventPageCount" @click="changeEventPage(eventPage + 1)">下一页</button>
              </div>
            </div>
            <div v-else class="external-request-list external-request-list--desktop" role="tabpanel">
              <div class="external-request-toolbar"><div><strong>外部供货请求</strong><small>请求密钥、令牌、载荷指纹和卡密内容不会在列表中回显。</small></div><select v-model="externalRequestStatus" class="native-select" @change="externalRequestPage=1;loadExternalRequests()"><option value="ALL">全部状态</option><option value="REVIEW_REQUIRED">结果未知</option><option value="FAILED">已知失败</option><option value="SUCCESS">供货成功</option><option value="MANUAL_NOT_SUPPLIED">人工确认未出卡</option><option value="MANUAL_SUPPLIED_REVIEW">已附加待核对</option></select></div>
              <div v-if="externalRequestsLoading" class="kami-page__empty">正在读取脱敏供货状态…</div>
              <div v-else-if="!externalRequests.length" class="kami-page__empty">暂无外部供货请求。</div>
              <article v-for="request in externalRequests" :key="request.id" class="external-request-card">
                <header><div><strong>订单 {{ request.orderId }}</strong><small>请求 #{{ request.id }} · 账号 {{ request.accountId }}</small></div><span :class="{ danger: request.resultUnknown === 1 }">{{ externalStatusLabel(request.requestStatus) }}</span></header>
                <dl><div><dt>请求数量</dt><dd>{{ request.quantity }}</dd></div><div><dt>尝试次数</dt><dd>{{ request.attemptCount }}</dd></div><div><dt>请求时熔断</dt><dd>{{ request.circuitStateAtRequest || '—' }}</dd></div><div><dt>附加待核对</dt><dd>{{ request.attachedReviewCount || 0 }}</dd></div></dl>
                <p>{{ request.errorMessage || '无错误详情' }}</p>
                <footer><time>更新 {{ request.updateTime }}</time><button v-if="request.resultUnknown === 1 || request.requestStatus === 'REVIEW_REQUIRED'" class="btn-warning btn-sm" @click="openResolutionDialog(request)">人工核对结果</button></footer>
              </article>
              <div v-if="externalRequestTotal > 20" class="kami-event__pager"><button class="btn-default btn-sm" :disabled="externalRequestPage<=1" @click="changeExternalRequestPage(externalRequestPage-1)">上一页</button><span>{{ externalRequestPage }} / {{ externalRequestPageCount }}</span><button class="btn-default btn-sm" :disabled="externalRequestPage>=externalRequestPageCount" @click="changeExternalRequestPage(externalRequestPage+1)">下一页</button></div>
            </div>
          </template>
        </div>
      </div>
    </template>

    <!-- ===== 弹窗（共用） ===== -->
    <Teleport to="body">
      <!-- 新建卡密配置 -->
      <Transition name="modal">
        <div v-if="showCreateDialog" class="modal-overlay" @click.self="showCreateDialog = false">
          <div class="modal-container">
            <div class="modal-header">
              <h2 class="modal-title">{{ editingConfigId ? '编辑卡密来源' : '新建卡密配置' }}</h2>
              <button class="modal-close" @click="showCreateDialog = false">×</button>
            </div>
            <div class="modal-body">
              <div class="form-row">
                <label class="form-label">别名</label>
                <input v-model="createForm.aliasName" class="form-input" placeholder="请输入别名" maxlength="50" />
                <small class="form-hint">{{ createForm.aliasName.length }} / 50</small>
              </div>
              <div class="form-row">
                <label class="form-label">库存使用方式</label>
                <div class="form-radio-group">
                  <label class="form-radio" :class="{ 'is-active': createForm.sharingMode === 'PRIVATE' }">
                    <input v-model="createForm.sharingMode" type="radio" value="PRIVATE" />私有库存
                  </label>
                  <label class="form-radio" :class="{ 'is-active': createForm.sharingMode === 'SHARED' }">
                    <input v-model="createForm.sharingMode" type="radio" value="SHARED" />共享库存池
                  </label>
                </div>
                <span class="form-suffix">私有库存仅当前账号使用；共享库存池由所选账号原子消费，同一张卡密不会重复发放。</span>
              </div>
              <div v-if="createForm.sharingMode === 'SHARED'" class="form-row">
                <label class="form-label">共享账号（可多选）</label>
                <select v-model="createForm.xianyuAccountIds" class="form-input" multiple :size="Math.min(accounts.length, 5)">
                  <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
                </select>
              </div>
              <div class="form-row">
                <label class="form-label">卡密来源</label>
                <div class="form-radio-group">
                  <label class="form-radio" :class="{ 'is-active': createForm.sourceType === 'LOCAL' }">
                    <input v-model="createForm.sourceType" type="radio" value="LOCAL" />本地库存
                  </label>
                  <label class="form-radio" :class="{ 'is-active': createForm.sourceType === 'API' }">
                    <input v-model="createForm.sourceType" type="radio" value="API" />外部接口
                  </label>
                </div>
              </div>
              <template v-if="createForm.sourceType === 'API'">
                <p class="form-hint">下单时实时调用供货接口。请求会携带 Idempotency-Key，超时或结果不确定时自动转人工核对。</p>
                <div class="form-row">
                  <label class="form-label">接口地址</label>
                  <input v-model="createForm.externalApiUrl" class="form-input" placeholder="https://supplier.example.com/cards" />
                </div>
                <div class="form-row">
                  <label class="form-label">请求头 JSON</label>
                  <textarea v-model="createForm.externalApiHeaders" class="form-textarea" rows="3" autocomplete="new-password" :placeholder="editingConfigId && selectedConfig?.externalApiHeadersConfigured ? '已保存请求头；留空保持不变' : '{&quot;Authorization&quot;:&quot;Bearer xxx&quot;}'"></textarea>
                  <span class="form-suffix">认证请求头不进入业务备份，恢复数据后需要重新填写。</span>
                </div>
                <div class="form-row">
                  <label class="form-label">请求体 JSON</label>
                  <textarea v-model="createForm.externalApiBody" class="form-textarea" rows="6" autocomplete="off" :placeholder="editingConfigId && selectedConfig?.externalApiBodySensitiveConfigured ? '旧请求体含敏感字段，已隐藏；留空保持原值，建议把密钥迁移到只写请求头' : 'JSON 请求体模板'"></textarea>
                  <span v-if="editingConfigId && selectedConfig?.externalApiBodySensitiveConfigured" class="form-suffix">检测到旧请求体含疑似密钥，本页不回显。留空保持原值；填入新模板会替换旧值。</span>
                  <span class="form-suffix">变量：{orderId}、{quantity}、{requestToken}</span>
                </div>
                <div class="form-row">
                  <label class="form-label">结果路径</label>
                  <input v-model="createForm.externalApiResultPath" class="form-input" placeholder="例如 data.cards" />
                </div>
                <div class="form-row">
                  <label class="form-label">超时秒数</label>
                  <input v-model.number="createForm.externalApiTimeoutSeconds" type="number" min="3" max="30" class="form-input form-input--num" />
                </div>
                <div class="form-section-title">配额与熔断</div>
                <div class="form-row">
                  <label class="form-label">每日配额</label>
                  <input v-model.number="createForm.externalDailyQuota" type="number" min="1" max="100000" class="form-input form-input--num" placeholder="不限" />
                  <span class="form-suffix">按实际外部请求数量计数；留空不限。</span>
                </div>
                <div class="form-row">
                  <label class="form-label">失败阈值</label>
                  <input v-model.number="createForm.externalFailureThreshold" type="number" min="1" max="20" class="form-input form-input--num" />
                  <span class="form-suffix">连续确定性失败达到阈值后停止新请求。</span>
                </div>
                <div class="form-row">
                  <label class="form-label">冷却秒数</label>
                  <input v-model.number="createForm.externalCooldownSeconds" type="number" min="30" max="86400" class="form-input form-input--num" />
                  <span class="form-suffix">超时或结果未知会立即熔断，且不会自动重试。</span>
                </div>
              </template>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" @click="showCreateDialog = false">取消</button>
              <button class="btn btn-primary" :class="{ 'is-loading': createLoading }" :disabled="createLoading" @click="handleCreate">确定</button>
            </div>
          </div>
        </div>
      </Transition>

      <!-- 添加卡密 -->
      <Transition name="modal">
        <div v-if="showAddDialog" class="modal-overlay" @click.self="showAddDialog = false">
          <div class="modal-container">
            <div class="modal-header">
              <h2 class="modal-title">添加卡密</h2>
              <button class="modal-close" @click="showAddDialog = false">×</button>
            </div>
            <div class="modal-body">
              <textarea v-model="addContent" class="form-textarea" :rows="3" placeholder="请输入卡密内容"></textarea>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" @click="showAddDialog = false">取消</button>
              <button class="btn btn-primary" :class="{ 'is-loading': addLoading }" :disabled="addLoading" @click="handleAddKami">确定</button>
            </div>
          </div>
        </div>
      </Transition>

      <!-- 批量导入 -->
      <Transition name="modal">
        <div v-if="showImportDialog" class="modal-overlay" @click.self="showImportDialog = false">
          <div class="modal-container modal-container--lg">
            <div class="modal-header">
              <h2 class="modal-title">批量导入卡密</h2>
              <button class="modal-close" @click="showImportDialog = false">×</button>
            </div>
            <div class="modal-body">
              <p class="form-hint">每行一条卡密；重复内容会安全跳过，不会生成重复库存。</p>
              <textarea v-model="importContent" class="form-textarea" :rows="10" placeholder="卡密1&#10;卡密2&#10;卡密3"></textarea>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" @click="showImportDialog = false">取消</button>
              <button class="btn btn-primary" :class="{ 'is-loading': importLoading }" :disabled="importLoading" @click="handleBatchImport">导入</button>
            </div>
          </div>
        </div>
      </Transition>

      <!-- 预警配置 -->
      <Transition name="modal">
        <div v-if="showAlertDialog" class="modal-overlay" @click.self="showAlertDialog = false">
          <div class="modal-container">
            <div class="modal-header">
              <h2 class="modal-title">预警配置</h2>
              <button class="modal-close" @click="showAlertDialog = false">×</button>
            </div>
            <div class="modal-body">
              <div class="form-row">
                <label class="form-label">开启预警</label>
                <label class="form-switch">
                  <input type="checkbox" :checked="alertForm.alertEnabled === 1" @change="alertForm.alertEnabled = alertForm.alertEnabled === 1 ? 0 : 1" />
                  <span class="form-switch-track"></span>
                </label>
              </div>
              <div class="form-row">
                <label class="form-label">阈值类型</label>
                <div class="form-radio-group">
                  <label class="form-radio" :class="{ 'is-active': alertForm.alertThresholdType === 1 }">
                    <input type="radio" :value="1" v-model="alertForm.alertThresholdType" />数量
                  </label>
                  <label class="form-radio" :class="{ 'is-active': alertForm.alertThresholdType === 2 }">
                    <input type="radio" :value="2" v-model="alertForm.alertThresholdType" />百分比
                  </label>
                </div>
              </div>
              <div class="form-row">
                <label class="form-label">阈值数值</label>
                <input type="number" v-model.number="alertForm.alertThresholdValue" class="form-input form-input--num" :min="1" :max="alertForm.alertThresholdType === 2 ? 100 : 99999" />
                <span class="form-suffix">{{ alertForm.alertThresholdType === 1 ? '可用卡密低于此数量时预警' : '可用比例低于此百分比时预警' }}</span>
              </div>
              <div class="form-row">
                <label class="form-label">预警邮箱</label>
                <input v-model="alertForm.alertEmail" class="form-input" placeholder="留空则使用系统设置的邮箱" />
              </div>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" @click="showAlertDialog = false">取消</button>
              <button class="btn btn-primary" :class="{ 'is-loading': alertLoading }" :disabled="alertLoading" @click="handleSaveAlert">保存</button>
            </div>
          </div>
        </div>
      </Transition>

      <!-- 导出卡密 -->
      <Transition name="modal">
        <div v-if="showExportDialog" class="modal-overlay" @click.self="showExportDialog = false">
          <div class="modal-container">
            <div class="modal-header">
              <h2 class="modal-title">导出卡密</h2>
              <button class="modal-close" @click="showExportDialog = false">×</button>
            </div>
            <div class="modal-body">
              <div class="form-row">
                <label class="form-label">导出状态</label>
                <div class="form-checkbox-group">
                  <label class="form-checkbox">
                    <input type="checkbox" v-model="exportStatus.unused" />未使用
                  </label>
                  <label class="form-checkbox">
                    <input type="checkbox" v-model="exportStatus.used" />已使用
                  </label>
                </div>
              </div>
              <div class="export-warning">
                <strong>敏感数据导出</strong>
                <span>文件包含完整卡密内容。本次范围、数量、操作者和请求 ID 会写入不可丢失审计。</span>
              </div>
              <p class="form-hint form-hint--indent">导出为制表符文本（.txt，Excel 可直接打开）。</p>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" @click="showExportDialog = false">取消</button>
              <button class="btn btn-primary" @click="handleExport">导出</button>
            </div>
          </div>
        </div>
      </Transition>

      <!-- 外部供货结果未知：人工补偿 -->
      <Transition name="modal">
        <div v-if="showResolutionDialog" class="modal-overlay" @click.self="showResolutionDialog = false">
          <div class="modal-container modal-container--lg external-resolution-dialog" role="dialog" aria-modal="true" aria-labelledby="external-resolution-title">
            <div class="modal-header">
              <div><h2 id="external-resolution-title" class="modal-title">人工核对外部供货</h2><p>订单 {{ resolutionRequest?.orderId }} · 请求 {{ resolutionRequest?.id }} · 应出 {{ resolutionRequest?.quantity }} 条</p></div>
              <button class="modal-close" aria-label="关闭" @click="showResolutionDialog = false">×</button>
            </div>
            <div class="modal-body">
              <div class="external-resolution-warning"><strong>先到供应商后台核对真实结果</strong><span>这里不会再次调用供应商，也不会向买家自动发送。错误结论可能造成重复取卡或漏发。</span></div>
              <div class="form-row"><label class="form-label">核对结论</label><select v-model="resolutionForm.decision" class="form-input" @change="invalidateResolutionPreview"><option value="CONFIRMED_NOT_SUPPLIED">确认供应商未出卡，可安全重试</option><option value="CONFIRMED_SUPPLIED">确认供应商已出卡，附加后转人工核对</option></select></div>
              <div v-if="resolutionForm.decision === 'CONFIRMED_SUPPLIED'" class="form-row"><label class="form-label">供应商实际返回的卡密</label><textarea v-model="resolutionForm.cardContents" class="form-textarea" rows="7" autocomplete="off" :placeholder="`每行一条，必须正好 ${resolutionRequest?.quantity || 0} 条`" @input="invalidateResolutionPreview"></textarea><span class="form-suffix">完整卡密仅写入库存；操作日志只记录数量，不记录内容。</span></div>
              <div class="form-row"><label class="form-label">核对备注（选填）</label><textarea v-model="resolutionForm.note" class="form-textarea" rows="3" maxlength="500" placeholder="例如：供应商后台工单号、核对时间；不要填写密钥"></textarea></div>
              <section v-if="resolutionPreview" class="external-resolution-preview"><strong>最终影响</strong><p>{{ resolutionPreview.effect }}</p><code>{{ resolutionPreview.confirmationText }}</code><label><input v-model="resolutionAcknowledged" type="checkbox">我已在供应商侧核对订单和数量，并确认执行以上处置。</label></section>
            </div>
            <div class="modal-footer"><button class="btn btn-secondary" @click="showResolutionDialog = false">取消</button><button v-if="!resolutionPreview" class="btn btn-primary" :disabled="resolutionSaving" @click="previewResolution">生成确认范围</button><button v-else class="btn btn-primary" :disabled="resolutionSaving || !resolutionAcknowledged" @click="confirmResolution">{{ resolutionSaving ? '处理中…' : '确认并记录' }}</button></div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.kami-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 16px;
  background: rgba(255,255,255,0.55);
  overflow: hidden;
  box-sizing: border-box;
}

/* ===== 桌面端 ===== */
.kami-page__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-shrink: 0;
}
.kami-page__filter-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 12px;
  border: 1px solid rgba(255,159,10,0.2);
  border-radius: 8px;
  background: rgba(255,159,10,0.08);
  color: #8a5a00;
  font-size: 13px;
  flex-shrink: 0;
}
.kami-page__title {
  font-size: 20px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
}
.kami-page__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.account-select {
  width: 180px;
}
.kami-page__body {
  flex: 1;
  display: flex;
  gap: 16px;
  min-height: 0;
  overflow: hidden;
}
.kami-page__sidebar {
  width: 260px;
  flex-shrink: 0;
  overflow-y: auto;
  border-right: 1px solid rgba(60,60,67,.12);
  padding-right: 12px;
}
.kami-page__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.kami-page__empty,
.kami-page__empty-main {
  color: rgba(28,28,30,.55);
  font-size: 14px;
  text-align: center;
  padding: 40px 0;
}
.config-card {
  padding: 12px;
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
}
.config-card:hover {
  border-color: rgba(0,122,255,0.3);
}
.config-card--active {
  border-color: #0A84FF;
  background: rgba(10,132,255,0.06);
}
.config-card__name {
  font-size: 14px;
  font-weight: 600;
  color: #1c1c1e;
  margin-bottom: 6px;
}
.config-card__stats {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: rgba(28,28,30,.55);
  margin-bottom: 4px;
  flex-wrap: wrap;
}
.config-card__stat.used { color: #FF9F0A; }
.config-card__stat.avail { color: #30D158; }
.config-card__del {
  position: absolute;
  top: 8px;
  right: 8px;
}
.kami-detail__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-shrink: 0;
}
.kami-detail__header h2 {
  font-size: 16px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
}
.kami-detail__subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: rgba(28,28,30,.55);
}
.kami-health {
  display: flex;
  align-items: stretch;
  gap: 8px;
  padding: 10px;
  margin-bottom: 10px;
  border: 1px solid rgba(60,60,67,.1);
  border-radius: 12px;
  background: rgba(255,255,255,.58);
  flex-shrink: 0;
}
.kami-health > div {
  min-width: 92px;
  padding: 2px 10px;
  border-right: 1px solid rgba(60,60,67,.1);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.kami-health > div:last-of-type { border-right: 0; }
.kami-health strong { font-size: 16px; color: #1c1c1e; }
.kami-health span { font-size: 11px; color: rgba(28,28,30,.58); }
.kami-health .danger strong { color: #d92d20; }
.kami-health .warning strong { color: #9a6700; }
.kami-health button { margin-left: auto; align-self: center; }
.kami-health--mobile {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 10px 0 8px;
}
.kami-health--mobile > div { min-width: 0; padding: 0 6px; }
.kami-tabs {
  display: flex;
  gap: 4px;
  padding: 3px;
  margin-bottom: 10px;
  border-radius: 10px;
  background: rgba(60,60,67,.08);
  width: fit-content;
  flex-shrink: 0;
}
.kami-tabs button {
  border: 0;
  border-radius: 8px;
  padding: 7px 14px;
  background: transparent;
  color: rgba(28,28,30,.64);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.kami-tabs button.active {
  color: #1c1c1e;
  background: rgba(255,255,255,.9);
  box-shadow: 0 1px 3px rgba(0,0,0,.08);
}
.kami-tabs button:focus-visible { outline: 3px solid rgba(255,204,0,.55); outline-offset: 2px; }
.kami-event-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.kami-event-list--desktop { padding-right: 6px; }
.kami-event {
  border: 1px solid rgba(60,60,67,.1);
  border-radius: 12px;
  padding: 12px 14px;
  background: rgba(255,255,255,.56);
}
.kami-event__top { display: flex; justify-content: space-between; gap: 12px; }
.kami-event__top strong { font-size: 13px; color: #1c1c1e; }
.kami-event__top span, .kami-event__request { font-size: 11px; color: rgba(28,28,30,.5); }
.kami-event__meta { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 7px; font-size: 12px; color: rgba(28,28,30,.72); }
.kami-event__request { margin-top: 5px; overflow-wrap: anywhere; }
.kami-event__pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 10px 0 2px;
  color: rgba(28,28,30,.62);
  font-size: 12px;
}
.external-request-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 5px;
}
.external-request-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.external-request-toolbar > div { display: grid; gap: 3px; }
.external-request-toolbar small { color: rgba(28,28,30,.55); font-size: 11px; }
.external-request-card { padding: 14px; border: 1px solid rgba(60,60,67,.1); border-radius: 13px; background: rgba(255,255,255,.62); }
.external-request-card > header, .external-request-card > footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.external-request-card header div { display: grid; gap: 3px; }
.external-request-card header small, .external-request-card time { color: rgba(28,28,30,.5); font-size: 11px; }
.external-request-card header > span { padding: 4px 9px; border-radius: 999px; background: rgba(60,60,67,.09); font-size: 11px; }
.external-request-card header > span.danger { color: #b42318; background: #fff0ef; }
.external-request-card dl { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 8px; margin: 12px 0; }
.external-request-card dl div { padding: 9px; border-radius: 9px; background: rgba(60,60,67,.05); }
.external-request-card dt { color: rgba(28,28,30,.5); font-size: 10px; }
.external-request-card dd { margin: 4px 0 0; font-size: 12px; font-weight: 650; }
.external-request-card > p { margin: 10px 0; color: rgba(28,28,30,.68); font-size: 12px; }
.external-request-card > small { display: block; margin: 8px 0; color: rgba(28,28,30,.58); }
.external-resolution-dialog { max-width: 640px; }
.modal-header p { margin: 4px 0 0; color: rgba(28,28,30,.55); font-size: 11px; }
.external-resolution-warning { display: grid; gap: 4px; padding: 12px; border: 1px solid rgba(255,159,10,.28); border-radius: 11px; background: rgba(255,159,10,.08); color: #724600; }
.external-resolution-warning span { font-size: 11px; line-height: 1.5; }
.external-resolution-preview { display: grid; gap: 8px; padding: 13px; border: 1px solid rgba(255,204,0,.5); border-radius: 12px; background: rgba(255,251,224,.72); }
.external-resolution-preview p { margin: 0; font-size: 12px; line-height: 1.5; }
.external-resolution-preview code { padding: 9px; border-radius: 8px; background: rgba(255,255,255,.75); overflow-wrap: anywhere; white-space: normal; }
.external-resolution-preview label { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; line-height: 1.5; }
.kami-detail__actions {
  display: flex;
  gap: 8px;
}
.kami-detail__filters {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  flex-shrink: 0;
}
.kami-detail__table {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.kami-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: auto;
}

.kami-table th {
  background: rgba(255,255,255,0.55);
  backdrop-filter: blur(16px) saturate(1.6);
  -webkit-backdrop-filter: blur(16px) saturate(1.6);
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  color: #1c1c1e;
  letter-spacing: .4px;
  text-align: left;
  border-bottom: 1px solid rgba(60,60,67,.12);
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 1;
}

.kami-table td {
  padding: 10px 16px;
  font-size: 13px;
  color: #1c1c1e;
  border-bottom: 1px solid rgba(60,60,67,.08);
}

.kami-table tbody tr:hover {
  background: rgba(255,255,255,0.38);
}

.kami-table__row--used {
  opacity: .6;
}

.kami-table__cell--num {
  font-size: 12px;
  color: rgba(28,28,30,.55);
}

.kami-table__cell--content {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.kami-table__cell--id {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'SF Mono', 'Menlo', monospace;
  font-size: 12px;
}

.kami-table__cell--time {
  white-space: nowrap;
  font-size: 12px;
  color: rgba(28,28,30,.55);
}

.kami-table__status {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 100px;
  font-size: 12px;
  font-weight: 500;
}

.kami-table__status--unused {
  background: rgba(48,209,88,0.12);
  color: #30D158;
}

.kami-table__status--used {
  background: rgba(120,120,128,0.12);
  color: rgba(28,28,30,.55);
}
.kami-status--0 { background: rgba(48,209,88,.12); color: #147a35; }
.kami-status--1 { background: rgba(120,120,128,.12); color: rgba(28,28,30,.66); }
.kami-status--2 { background: rgba(0,122,255,.12); color: #0059b3; }
.kami-status--3 { background: rgba(255,69,58,.12); color: #b42318; }

.kami-table__actions {
  display: flex;
  gap: 8px;
}

.kami-table__action-btn {
  padding: 4px 12px;
  border: none;
  border-radius: 100px;
  font-size: 12px;
  font-weight: 590;
  cursor: pointer;
  transition: opacity .15s, transform .12s;
  font-family: inherit;
}

.kami-table__action-btn:active { opacity: .80; transform: scale(.96); }

.kami-table__action-btn--reset {
  color: #FF9F0A;
  background: rgba(255,159,10,0.12);
}

.kami-table__action-btn--delete {
  color: #FF453A;
  background: rgba(255,69,58,0.12);
}

/* ===== 手机端 ===== */
.kami-mobile {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.kami-mobile__header {
  flex-shrink: 0;
  padding: 0 0 12px;
  border-bottom: 1px solid rgba(60,60,67,.12);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kami-mobile__header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.kami-mobile__select {
  width: 100%;
}

.kami-mobile__back {
  background: none;
  border: none;
  color: #0A84FF;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  padding: 0;
  -webkit-tap-highlight-color: transparent;
}

.kami-mobile__config-name {
  font-size: 15px;
  font-weight: 600;
  color: #1c1c1e;
  flex: 1;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 8px;
}

.kami-mobile__detail-actions {
  display: flex;
  gap: 6px;
}

.kami-mobile__filters {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
  padding: 10px 0;
  border-bottom: 1px solid rgba(60,60,67,.12);
}

.kami-mobile__list {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.kami-mobile__list::-webkit-scrollbar { display: none; }

.kami-mobile__items {
  flex: 1;
  overflow-y: auto;
  padding-top: 8px;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.kami-mobile__items::-webkit-scrollbar { display: none; }

/* 卡密条目卡片 */
.kami-item-card {
  padding: 10px 12px;
  border-bottom: 0.5px solid rgba(60,60,67,.12);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.kami-item-card:nth-child(even) {
  background: rgba(255,255,255,0.15);
}
.kami-item-card--used {
  opacity: 0.6;
}
.kami-item-card__content {
  font-size: 13px;
  font-weight: 500;
  color: #1c1c1e;
  word-break: break-all;
}
.kami-item-card__meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kami-item-card__time {
  font-size: 11px;
  color: rgba(28,28,30,.55);
}
.kami-item-card__actions {
  display: flex;
  gap: 4px;
}

/* ===== 弹窗样式 ===== */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.20);
  backdrop-filter: blur(28px) saturate(1.8);
  -webkit-backdrop-filter: blur(28px) saturate(1.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: rgba(255,255,255,0.72);
  backdrop-filter: blur(40px) saturate(2);
  -webkit-backdrop-filter: blur(40px) saturate(2);
  border: 1px solid rgba(255,255,255,0.75);
  border-radius: 20px;
  width: 100%;
  max-width: 400px;
  max-height: 85vh;
  box-shadow: 0 16px 48px rgba(0,0,0,0.16), 0 2px 8px rgba(0,0,0,0.08);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-container--lg {
  max-width: 480px;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: rgba(28,28,30,.55);
  font-size: 18px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.modal-body {
  padding: 0 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
  min-height: 0;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.form-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.form-label {
  font-size: 13px;
  color: #1c1c1e;
  font-weight: 500;
  min-width: 70px;
  flex-shrink: 0;
}

.form-input {
  flex: 1;
  min-width: 0;
  padding: 8px 12px;
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 8px;
  font-size: 13px;
  background: rgba(255,255,255,0.55);
  color: #1c1c1e;
  transition: border-color 0.15s ease;
  box-sizing: border-box;
}

.form-input:focus {
  outline: none;
  border-color: #0A84FF;
}

.form-input--num {
  width: 100px;
  flex: none;
}

.form-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.5;
  resize: vertical;
  background: rgba(255,255,255,0.55);
  color: #1c1c1e;
  font-family: inherit;
  box-sizing: border-box;
}

.form-textarea:focus {
  outline: none;
  border-color: #0A84FF;
}

.form-hint {
  font-size: 12px;
  color: rgba(28,28,30,.55);
  margin: 0;
}

.form-hint--indent {
  margin-left: 70px;
}
.form-section-title {
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px solid rgba(60,60,67,.1);
  font-size: 13px;
  font-weight: 700;
  color: #1c1c1e;
}
.export-warning {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid rgba(255,159,10,.25);
  border-radius: 10px;
  background: rgba(255,159,10,.08);
  color: #784c00;
  font-size: 12px;
  line-height: 1.5;
}

.form-suffix {
  font-size: 12px;
  color: rgba(28,28,30,.55);
}

.form-switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 24px;
  cursor: pointer;
}

.form-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.form-switch-track {
  position: absolute;
  inset: 0;
  background: #e5e5e5;
  border-radius: 12px;
  transition: background 0.2s ease;
}

.form-switch-track::after {
  content: '';
  position: absolute;
  width: 20px;
  height: 20px;
  left: 2px;
  top: 2px;
  background: rgba(255,255,255,0.55);
  border-radius: 50%;
  transition: transform 0.2s ease;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.form-switch input:checked + .form-switch-track {
  background: #30D158;
}

.form-switch input:checked + .form-switch-track::after {
  transform: translateX(16px);
}

.form-radio-group {
  display: flex;
  gap: 12px;
}

.form-radio {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  color: #1c1c1e;
  cursor: pointer;
}

.form-radio input {
  margin: 0;
}

.form-checkbox-group {
  display: flex;
  gap: 16px;
}

.form-checkbox {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #1c1c1e;
  cursor: pointer;
}

.form-checkbox input {
  margin: 0;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 18px;
  border-radius: 100px;
  font-size: 13px;
  font-weight: 590;
  cursor: pointer;
  transition: opacity .15s, transform .12s, box-shadow .15s;
  border: none;
  font-family: inherit;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}

.btn:active { opacity: .80; transform: scale(.96); }

.btn-secondary {
  color: #0A84FF;
  background: rgba(255,255,255,0.70);
  backdrop-filter: blur(16px) saturate(1.6);
  -webkit-backdrop-filter: blur(16px) saturate(1.6);
  border: 1px solid rgba(255,255,255,0.85);
  box-shadow: 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
}

@media (hover: hover) {
  .btn-secondary:hover {
    background: rgba(255,255,255,0.80);
  }
}

.btn-primary {
  background: rgba(10,132,255,0.85);
  backdrop-filter: blur(20px) saturate(1.8);
  -webkit-backdrop-filter: blur(20px) saturate(1.8);
  color: #fff;
  border: 1px solid rgba(255,255,255,0.35);
  box-shadow: 0 4px 16px rgba(10,132,255,0.35), 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
}

@media (hover: hover) {
  .btn-primary:hover:not(:disabled) {
    background: rgba(10,132,255,0.95);
    box-shadow: 0 6px 20px rgba(10,132,255,0.45), 0 8px 32px rgba(0,0,0,0.08), 0 1.5px 4px rgba(0,0,0,0.04);
  }
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn.is-loading {
  opacity: 0.6;
  pointer-events: none;
}

/* Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}

.btn-primary, .btn-default, .btn-success, .btn-warning, .btn-danger, .btn-text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 100px;
  font-size: 13px;
  font-weight: 590;
  cursor: pointer;
  transition: opacity .15s, transform .12s;
  border: none;
  font-family: inherit;
  user-select: none;
  white-space: nowrap;
}
.btn-primary:active, .btn-default:active, .btn-success:active, .btn-warning:active, .btn-danger:active { opacity: .80; transform: scale(.96); }
.btn-primary { background: rgba(10,132,255,0.85); color: #fff; border: 1px solid rgba(255,255,255,0.35); box-shadow: 0 4px 16px rgba(10,132,255,0.35), 0 8px 32px rgba(0,0,0,0.08); }
.btn-default { background: rgba(255,255,255,0.70); color: #0A84FF; border: 1px solid rgba(255,255,255,0.85); box-shadow: 0 8px 32px rgba(0,0,0,0.08); }
.btn-success { background: rgba(48,209,88,0.85); color: #fff; border: 1px solid rgba(255,255,255,0.35); }
.btn-warning { background: rgba(255,159,10,0.85); color: #fff; border: 1px solid rgba(255,255,255,0.35); }
.btn-danger { color: #FF453A; background: rgba(255,69,58,0.15); border: 1px solid rgba(255,69,58,0.2); }
.btn-text { background: transparent; color: #0A84FF; padding: 4px 8px; }
.btn-sm { padding: 4px 12px; font-size: 12px; }
.btn-primary:disabled, .btn-default:disabled { opacity: 0.5; cursor: not-allowed; }

.tag { display: inline-flex; align-items: center; padding: 2px 10px; border-radius: 100px; font-size: 12px; font-weight: 500; }
.tag--success { background: rgba(48,209,88,0.12); color: #30D158; }
.tag--warning { background: rgba(255,159,10,0.12); color: #FF9F0A; }
.tag--info { background: rgba(120,120,128,0.12); color: rgba(28,28,30,.55); }

.native-select {
  padding: 8px 12px;
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 8px;
  background: rgba(255,255,255,0.55);
  color: #1c1c1e;
  font-size: 13px;
  outline: none;
  cursor: pointer;
  font-family: inherit;
}
.native-select:focus { border-color: #0A84FF; }

.native-input {
  padding: 8px 12px;
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 8px;
  background: rgba(255,255,255,0.55);
  color: #1c1c1e;
  font-size: 13px;
  outline: none;
  font-family: inherit;
  box-sizing: border-box;
}
.native-input:focus { border-color: #0A84FF; }
</style>
