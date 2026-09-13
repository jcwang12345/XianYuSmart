import { computed, ref } from 'vue'
import { getDashboardStats, type DashboardStats } from '@/api/dashboard'
import { getAccountGroups, getAccountMatrixSummary, getBusinessAnalytics, type AccountGroup, type AccountMatrixSummary, type BusinessAnalyticsOverview } from '@/api/matrix'

const emptyOperations: DashboardStats = {
  accountCount: 0, itemCount: 0, sellingItemCount: 0, reviewingItemCount: 0,
  offShelfItemCount: 0, soldItemCount: 0, deletedItemCount: 0, unknownItemCount: 0,
  todayRevenue: 0, todayDeliveryCount: 0, todayReplyCount: 0, pendingTaskCount: 0,
  reviewRequiredCount: 0, failedTaskCount: 0, availableKamiCount: 0, lowStockConfigCount: 0
}

export function useDashboard() {
  const loading = ref(false)
  const error = ref('')
  const days = ref(7)
  const groupId = ref<number | undefined>()
  const analytics = ref<BusinessAnalyticsOverview | null>(null)
  const accountSummary = ref<AccountMatrixSummary | null>(null)
  const groups = ref<AccountGroup[]>([])
  const operations = ref<DashboardStats>({ ...emptyOperations })
  const queryDates = computed(() => {
    const end = new Date(); const start = new Date(end); start.setDate(start.getDate() - days.value + 1)
    const date = (value: Date) => value.toISOString().slice(0, 10)
    return { start: date(start), end: date(end) }
  })

  const loadStatistics = async () => {
    if (loading.value) return
    loading.value = true; error.value = ''
    const params = { ...queryDates.value, groupId: groupId.value }
    const [analyticsResult, accountResult, groupResult, operationResult] = await Promise.allSettled([
      getBusinessAnalytics(params), getAccountMatrixSummary(), getAccountGroups(), getDashboardStats()
    ])
    if (analyticsResult.status === 'fulfilled') analytics.value = analyticsResult.value.data || null
    else error.value = '经营数据暂时无法读取，未同步指标不会按 0 展示。'
    if (accountResult.status === 'fulfilled') accountSummary.value = accountResult.value.data || null
    if (groupResult.status === 'fulfilled') groups.value = groupResult.value.data || []
    if (operationResult.status === 'fulfilled' && operationResult.value.data) operations.value = operationResult.value.data
    loading.value = false
  }
  return { loading, error, days, groupId, analytics, accountSummary, groups, operations, loadStatistics }
}
