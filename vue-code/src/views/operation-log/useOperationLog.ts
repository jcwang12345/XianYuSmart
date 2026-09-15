import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getAccountList } from '@/api/account'
import { queryOperationLogs, deleteOldLogs, exportOperationLogs } from '@/api/operation-log'
import type { OperationLog } from '@/api/operation-log'
import type { Account } from '@/types'
import { showSuccess, showError, showInfo } from '@/utils'

const operationTypeLabels: Record<string, string> = {
  LOGIN: '扫码登录', WEBSOCKET_CONNECT: '连接闲鱼消息', WEBSOCKET_DISCONNECT: '断开闲鱼消息',
  SEND_MESSAGE: '发送消息', RECEIVE_MESSAGE: '接收消息', AUTO_DELIVERY: '自动发货', AUTO_REPLY: '自动回复',
  CONFIRM_SHIPMENT: '确认收货', TOKEN_REFRESH: '刷新登录令牌', COOKIE_UPDATE: '更新登录凭证',
  GOODS_SYNC: '同步商品', MESSAGE_SYNC: '同步消息', ACCOUNT_CAPABILITY_CHANGED: '账号能力变更',
  ACCOUNT_GROUP_DELETE: '删除账号分组', ACCOUNT_GROUP_MEMBERS: '调整分组成员', ACCOUNT_GROUP_SAVE: '保存账号分组',
  AI_HANDOFF_CLAIM: '领取人工接待任务', AI_HANDOFF_OPEN: '创建人工接待任务', AI_NO_SAFE_ANSWER: 'AI 无安全答案',
  AI_REPLY: 'AI 回复', AI_UNAVAILABLE: 'AI 服务不可用', BACKUP_EXPORT: '导出备份',
  BACKUP_RESTORE_EXECUTE: '执行备份恢复', BACKUP_RESTORE_FAILED: '备份恢复失败',
  BACKUP_RESTORE_PREVIEW: '预检备份恢复', BACKUP_RESTORE_ROLLBACK: '回滚备份恢复', BACKUP_RESTORE_SUCCEEDED: '备份恢复成功',
  BATCH_CANCEL: '取消批量任务', BATCH_NOTIFICATION: '发送批量任务通知', BATCH_RETRY: '重试批量任务',
  DELIVERY_FAILED: '自动发货失败', DELIVERY_SUCCESS: '自动发货成功', DELIVERY_UNCERTAIN: '发货结果待核对',
  GOODS_KNOWLEDGE_VERSION_ACTIVATE: '启用商品知识版本', GOODS_KNOWLEDGE_VERSION_CREATE: '创建商品知识版本',
  GOODS_KNOWLEDGE_VERSION_EXPIRE: '停用商品知识版本', MESSAGE_INTERRUPTED: '消息处理被中断',
  MESSAGE_OUTCOME_UNKNOWN: '消息发送结果待核对', ORDER_NOTE_UPDATE: '更新订单备注',
  ORDER_PHYSICAL_SHIPMENT_RECORD: '记录实物发货事实', PRODUCT_AUTOMATION_UPDATE: '更新商品自动化',
  PRODUCT_BATCH_CANCEL: '取消商品批量任务', PRODUCT_BATCH_CREATE: '创建商品批量任务',
  PRODUCT_BATCH_EXPORT: '导出商品批量任务', PRODUCT_BATCH_FAILED: '商品批量任务失败',
  PRODUCT_BATCH_PARTIAL: '商品批量任务部分成功', PRODUCT_BATCH_RETRY: '重试商品批量任务',
  PRODUCT_BATCH_SUCCEEDED: '商品批量任务成功', PRODUCT_EXPORT: '导出商品', PRODUCT_FILTER_DELETE: '删除商品筛选器',
  PRODUCT_FILTER_SAVE: '保存商品筛选器', PRODUCT_LOCAL_EDIT: '编辑商品本地资料', PRODUCT_PUBLISH: '发布商品',
  PRODUCT_PUBLISH_FAILED: '商品发布失败', PRODUCT_SYNC_EXCEPTION: '商品同步异常', REFUND_NOTE_ADD: '添加售后备注',
  RETURN_SHIPMENT_RECORD: '记录售后运单', RETURN_SHIPMENT_RECORDED: '记录售后运单'
}

// Operation type config
const operationTypes = [
  { label: '全部', value: '' },
  { label: '扫码登录', value: 'LOGIN' },
  { label: 'WS连接', value: 'WEBSOCKET_CONNECT' },
  { label: 'WS断开', value: 'WEBSOCKET_DISCONNECT' },
  { label: '发送消息', value: 'SEND_MESSAGE' },
  { label: '接收消息', value: 'RECEIVE_MESSAGE' },
  { label: '自动发货', value: 'AUTO_DELIVERY' },
  { label: '自动回复', value: 'AUTO_REPLY' },
  { label: '确认收货', value: 'CONFIRM_SHIPMENT' },
  { label: 'Token刷新', value: 'TOKEN_REFRESH' },
  { label: 'Cookie更新', value: 'COOKIE_UPDATE' },
  { label: '商品同步', value: 'GOODS_SYNC' },
  { label: '消息同步', value: 'MESSAGE_SYNC' },
  { label: '创建商品知识版本', value: 'GOODS_KNOWLEDGE_VERSION_CREATE' },
  { label: '启用商品知识版本', value: 'GOODS_KNOWLEDGE_VERSION_ACTIVATE' },
  { label: '停用商品知识版本', value: 'GOODS_KNOWLEDGE_VERSION_EXPIRE' },
  { label: '创建人工接待任务', value: 'AI_HANDOFF_OPEN' },
  { label: '领取人工接待任务', value: 'AI_HANDOFF_CLAIM' },
  { label: '创建商品批量任务', value: 'PRODUCT_BATCH_CREATE' },
  { label: '重试商品批量任务', value: 'PRODUCT_BATCH_RETRY' },
  { label: '取消商品批量任务', value: 'PRODUCT_BATCH_CANCEL' },
  { label: '导出商品批量任务', value: 'PRODUCT_BATCH_EXPORT' },
  { label: '导出商品经营报表', value: 'PRODUCT_EXPORT' },
  { label: '发布商品', value: 'PRODUCT_PUBLISH' },
  { label: '预检备份恢复', value: 'BACKUP_RESTORE_PREVIEW' },
  { label: '执行备份恢复', value: 'BACKUP_RESTORE_EXECUTE' },
  { label: '回滚备份恢复', value: 'BACKUP_RESTORE_ROLLBACK' }
]

const operationModules = [
  { label: '全部', value: '' },
  { label: '账号', value: 'ACCOUNT' },
  { label: '消息', value: 'MESSAGE' },
  { label: '订单', value: 'ORDER' },
  { label: '商品', value: 'GOODS' },
  { label: '商家运营', value: 'MERCHANT_OPERATIONS' },
  { label: '系统', value: 'SYSTEM' }
]

const operationStatuses = [
  { label: '全部', value: '' },
  { label: '成功', value: 1 },
  { label: '失败', value: 0 },
  { label: '部分成功', value: 2 }
]

export function useOperationLog() {
  const loading = ref(false)
  const accounts = ref<Account[]>([])
  const selectedAccountId = ref<number | null>(null)
  const logs = ref<OperationLog[]>([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)

  // Filters
  const filterType = ref('')
  const filterModule = ref('')
  const filterStatus = ref<string | number>('')
  const filterOutcome = ref('')
  const filterOperator = ref('')
  const filterRequestId = ref('')
  const filterKeyword = ref('')
  const filterStart = ref('')
  const filterEnd = ref('')
  const exporting = ref(false)

  // Responsive
  const isMobile = ref(false)
  const mobileView = ref<'accounts' | 'logs'>('accounts')
  const selectedAccountForMobile = ref<Account | null>(null)

  // Detail dialog
  const detailDialogVisible = ref(false)
  const detailLog = ref<OperationLog | null>(null)

  // Delete dialog
  const deleteDialogVisible = ref(false)
  const deleteDays = ref('')

  // Check screen size
  const checkScreenSize = () => {
    isMobile.value = window.innerWidth < 768
    if (!isMobile.value) {
      mobileView.value = 'accounts'
    }
  }

  // Mobile go back
  const goBackToAccounts = () => {
    mobileView.value = 'accounts'
  }

  // Current account
  const currentAccount = computed(() => {
    return accounts.value.find(acc => acc.id === selectedAccountId.value)
  })

  // Total pages
  const totalPages = computed(() => Math.ceil(total.value / pageSize.value))

  // Get account avatar
  const getAccountAvatar = (account: Account) => {
    return account.accountNote?.substring(0, 1) || account.id?.toString().substring(0, 1) || '?'
  }

  // Get account name
  const getAccountName = (account: Account) => {
    return account.accountNote || `账号${account.id}`
  }

  // Get operation type text
  const getOperationTypeText = (type: string) => {
    const item = operationTypes.find(t => t.value === type)
    return operationTypeLabels[type] || item?.label || '其他系统操作'
  }

  const getOperationDescriptionText = (log: OperationLog) => {
    const typeText = getOperationTypeText(log.operationType)
    const description = String(log.operationDesc || '').trim()
    if (!description || description === log.operationType) return typeText
    return description.includes(log.operationType)
      ? description.split(log.operationType).join(typeText)
      : description
  }

  // Get operation type CSS class
  const getOperationTypeClass = (type: string) => {
    const map: Record<string, string> = {
      'LOGIN': 'login',
      'WEBSOCKET_CONNECT': 'ws-connect',
      'WEBSOCKET_DISCONNECT': 'ws-disconnect',
      'SEND_MESSAGE': 'send-msg',
      'RECEIVE_MESSAGE': 'recv-msg',
      'AUTO_DELIVERY': 'auto-delivery',
      'AUTO_REPLY': 'auto-reply',
      'CONFIRM_SHIPMENT': 'confirm-ship',
      'TOKEN_REFRESH': 'token-refresh',
      'COOKIE_UPDATE': 'cookie-update',
      'GOODS_SYNC': 'goods-sync',
      'MESSAGE_SYNC': 'msg-sync'
    }
    return map[type] || 'default'
  }

  // Get status text
  const getStatusText = (status: number) => {
    const statusMap: Record<number, string> = { 0: '失败', 1: '成功', 2: '部分成功' }
    return statusMap[status] || '未知'
  }

  // Get status CSS class
  const getStatusClass = (status: number) => {
    const map: Record<number, string> = { 0: 'fail', 1: 'success', 2: 'partial' }
    return map[status] || 'fail'
  }

  // Format time
  const formatTime = (timestamp: string | number) => {
    const ts = Number(timestamp)
    if (!ts || isNaN(ts)) return '-'
    const date = new Date(ts)
    if (isNaN(date.getTime())) return '-'
    const pad = (value: number) => String(value).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
      + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }

  // Format duration
  const formatDuration = (ms?: number) => {
    if (!ms) return '-'
    if (ms < 1000) return `${ms}ms`
    return `${(ms / 1000).toFixed(2)}s`
  }

  // Load accounts
  const loadAccounts = async () => {
    loading.value = true
    try {
      const response = await getAccountList()
      if (response.code === 0 || response.code === 200) {
        accounts.value = response.data?.accounts || []
        if (accounts.value.length > 0) {
          if (!selectedAccountId.value) selectedAccountId.value = accounts.value[0]?.id ?? null
          await loadLogs()
        }
      }
    } catch (error: any) {
      console.error('加载账号列表失败:', error)
    } finally {
      loading.value = false
    }
  }

  // Select account
  const selectAccount = (accountId: number, account?: Account) => {
    selectedAccountId.value = accountId
    page.value = 1
    loadLogs()

    if (isMobile.value && account) {
      selectedAccountForMobile.value = account
      mobileView.value = 'logs'
    }
  }

  // Handle account select change (for dropdown)
  const handleAccountSelectChange = () => {
    page.value = 1
    loadLogs()
  }

  // Load logs
  const loadLogs = async () => {
    if (!selectedAccountId.value) return

    loading.value = true
    try {
      const response = await queryOperationLogs({
        accountId: selectedAccountId.value,
        operationType: filterType.value || undefined,
        operationModule: filterModule.value || undefined,
        operationStatus: filterStatus.value !== '' ? Number(filterStatus.value) : undefined,
        outcomeState: filterOutcome.value || undefined,
        operatorUsername: filterOperator.value.trim() || undefined,
        requestId: filterRequestId.value.trim() || undefined,
        keyword: filterKeyword.value.trim() || undefined,
        startTime: filterStart.value ? new Date(filterStart.value).getTime() : undefined,
        endTime: filterEnd.value ? new Date(filterEnd.value).getTime() : undefined,
        page: page.value,
        pageSize: pageSize.value
      })

      if (response.code === 0 || response.code === 200) {
        logs.value = response.data?.logs || []
        total.value = response.data?.total || 0
      } else {
        throw new Error(response.msg || '加载失败')
      }
    } catch (error: any) {
      console.error('加载操作记录失败:', error)
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!error.messageShown) {
        showError('加载失败: ' + error.message)
      }
    } finally {
      loading.value = false
    }
  }

  // Filter
  const handleFilter = () => {
    page.value = 1
    loadLogs()
  }

  // Reset filter
  const handleResetFilter = () => {
    filterType.value = ''
    filterModule.value = ''
    filterStatus.value = ''
    filterOutcome.value = ''
    filterOperator.value = ''
    filterRequestId.value = ''
    filterKeyword.value = ''
    filterStart.value = ''
    filterEnd.value = ''
    page.value = 1
    loadLogs()
  }

  // Page change
  const handlePageChange = (newPage: number) => {
    page.value = newPage
    loadLogs()
  }

  // Refresh
  const handleRefresh = () => {
    loadLogs()
    showInfo('已刷新')
  }

  const handleExport = async () => {
    if (!selectedAccountId.value) return
    if (!window.confirm(`将导出账号 ${selectedAccountId.value} 当前筛选范围内最多 10000 条脱敏审计记录，并记录本次导出。是否继续？`)) return
    exporting.value = true
    try {
      const blob = await exportOperationLogs({
        accountId: selectedAccountId.value,
        operationType: filterType.value || undefined,
        operationModule: filterModule.value || undefined,
        operationStatus: filterStatus.value !== '' ? Number(filterStatus.value) : undefined,
        outcomeState: filterOutcome.value || undefined,
        operatorUsername: filterOperator.value.trim() || undefined,
        requestId: filterRequestId.value.trim() || undefined,
        keyword: filterKeyword.value.trim() || undefined,
        startTime: filterStart.value ? new Date(filterStart.value).getTime() : undefined,
        endTime: filterEnd.value ? new Date(filterEnd.value).getTime() : undefined,
        exportRequestId: `audit-export-${crypto.randomUUID()}`
      })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `operation-audit-${selectedAccountId.value}.csv`
      anchor.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
      showSuccess('审计 CSV 已导出，本次导出已留痕')
      await loadLogs()
    } catch (error: any) {
      if (!error.messageShown) showError(error.message || '导出失败')
    } finally {
      exporting.value = false
    }
  }

  // View detail
  const viewDetail = (log: OperationLog) => {
    detailLog.value = log
    detailDialogVisible.value = true
  }

  // Close detail
  const closeDetail = () => {
    detailDialogVisible.value = false
    detailLog.value = null
  }

  // Delete old logs - open dialog
  const openDeleteDialog = () => {
    deleteDays.value = ''
    deleteDialogVisible.value = true
  }

  // Confirm delete old logs
  const confirmDeleteOld = async () => {
    const days = parseInt(deleteDays.value)
    if (isNaN(days) || days <= 0) {
      showError('请输入有效的天数')
      return
    }

    try {
      const response = await deleteOldLogs(days)
      if (response.code === 0 || response.code === 200) {
        showSuccess(`成功删除${response.data}条记录`)
        deleteDialogVisible.value = false
        loadLogs()
      } else {
        throw new Error(response.msg || '删除失败')
      }
    } catch (error: any) {
      console.error('删除旧日志失败:', error)
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!error.messageShown) {
        showError('删除失败: ' + error.message)
      }
    }
  }

  // Lifecycle
  onMounted(() => {
    loadAccounts()
    checkScreenSize()
    window.addEventListener('resize', checkScreenSize)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', checkScreenSize)
  })

  return {
    // State
    loading,
    accounts,
    selectedAccountId,
    logs,
    total,
    page,
    pageSize,
    totalPages,
    filterType,
    filterModule,
    filterStatus,
    filterOutcome,
    filterOperator,
    filterRequestId,
    filterKeyword,
    filterStart,
    filterEnd,
    exporting,
    isMobile,
    mobileView,
    selectedAccountForMobile,
    detailDialogVisible,
    detailLog,
    deleteDialogVisible,
    deleteDays,
    currentAccount,

    // Constants
    operationTypes,
    operationModules,
    operationStatuses,

    // Methods
    selectAccount,
    handleAccountSelectChange,
    loadLogs,
    handleFilter,
    handleResetFilter,
    handlePageChange,
    handleRefresh,
    handleExport,
    viewDetail,
    closeDetail,
    openDeleteDialog,
    confirmDeleteOld,
    goBackToAccounts,
    getAccountAvatar,
    getAccountName,
    getOperationTypeText,
    getOperationDescriptionText,
    getOperationTypeClass,
    getStatusText,
    getStatusClass,
    formatTime,
    formatDuration,
    checkScreenSize
  }
}
