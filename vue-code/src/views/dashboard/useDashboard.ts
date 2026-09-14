import { computed, ref } from 'vue'
import { getDashboardStats, type DashboardStats } from '@/api/dashboard'
import type { Account } from '@/types'
import { getBusinessAnalytics, getBusinessAnalyticsScopes, type AccountGroup, type AccountMatrixSummary, type BusinessAnalyticsOverview } from '@/api/matrix'

const emptyOperations: DashboardStats = {
  accountCount: 0, itemCount: 0, sellingItemCount: 0, reviewingItemCount: 0,
  offShelfItemCount: 0, soldItemCount: 0, deletedItemCount: 0, unknownItemCount: 0,
  todayRevenue: 0, todayDeliveryCount: 0, todayReplyCount: 0, pendingTaskCount: 0,
  reviewRequiredCount: 0, failedTaskCount: 0, availableKamiCount: 0, lowStockConfigCount: 0
}

export function useDashboard() {
  const loading = ref(false)
  const error = ref('')
  const periodMode = ref<'1'|'7'|'30'|'custom'>('7')
  const accountId = ref<number | undefined>()
  const groupId = ref<number | undefined>()
  const accounts = ref<Account[]>([])
  const analytics = ref<BusinessAnalyticsOverview | null>(null)
  const accountSummary = ref<AccountMatrixSummary | null>(null)
  const groups = ref<AccountGroup[]>([])
  const operations = ref<DashboardStats>({ ...emptyOperations })
  const localDate = (value: Date) => {
    const year = value.getFullYear()
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const day = String(value.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }
  const today = new Date()
  const initialStart = new Date(today); initialStart.setDate(initialStart.getDate() - 29)
  const customStart = ref(localDate(initialStart))
  const customEnd = ref(localDate(today))
  const queryDates = computed(() => {
    if (periodMode.value === 'custom') return { start: customStart.value, end: customEnd.value }
    const end = new Date(); const start = new Date(end)
    start.setDate(start.getDate() - Number(periodMode.value) + 1)
    return { start: localDate(start), end: localDate(end) }
  })

  const selectAccount = () => { if (accountId.value !== undefined) groupId.value = undefined }
  const selectGroup = () => { if (groupId.value !== undefined) accountId.value = undefined }

  const loadStatistics = async () => {
    if (loading.value) return
    loading.value = true; error.value = ''
    if (!queryDates.value.start || !queryDates.value.end || queryDates.value.start > queryDates.value.end) {
      error.value = '自定义开始日期不能晚于结束日期。'
      loading.value = false
      return
    }
    const params = { ...queryDates.value, accountId: accountId.value, groupId: groupId.value }
    const [analyticsResult, scopeResult, operationResult] = await Promise.allSettled([
      getBusinessAnalytics(params), getBusinessAnalyticsScopes(), getDashboardStats()
    ])
    if (analyticsResult.status === 'fulfilled') analytics.value = analyticsResult.value.data || null
    else error.value = '经营数据暂时无法读取，未同步指标不会按 0 展示。'
    if (scopeResult.status === 'fulfilled' && scopeResult.value.data) {
      accounts.value = scopeResult.value.data.accounts.map(item => ({
        id: item.id, accountNote: item.accountNote || `店铺 ${item.id}`
      } as Account))
      groups.value = scopeResult.value.data.groups || []
      accountSummary.value = scopeResult.value.data.accountSummary || null
    }
    if (operationResult.status === 'fulfilled' && operationResult.value.data) operations.value = operationResult.value.data
    loading.value = false
  }
  return { loading, error, periodMode, accountId, groupId, customStart, customEnd, accounts,
    analytics, accountSummary, groups, operations, queryDates, selectAccount, selectGroup, loadStatistics }
}
