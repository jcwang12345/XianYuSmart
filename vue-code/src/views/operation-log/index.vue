<script setup lang="ts">
import { computed, inject, defineComponent, h, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useOperationLog } from './useOperationLog'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap'
import { hasPermission } from '@/utils/permission'
import './operation-log.css'
import '@/styles/header-selectors.css'

import IconLog from '@/components/icons/IconLog.vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconChevronRight from '@/components/icons/IconChevronRight.vue'
import IconClock from '@/components/icons/IconClock.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconEmpty from '@/components/icons/IconEmpty.vue'
import IconInfo from '@/components/icons/IconInfo.vue'

type DiffChange = { field: string; label?: string; before?: unknown; after?: unknown }
const parseDiff = (value?: string) => {
  if (!value) return null
  try { return JSON.parse(value) as { changedFieldCount?: number; fields?: Record<string, Omit<DiffChange, 'field'>> } }
  catch { return null }
}
const detailChanges = (value?: string): DiffChange[] => {
  const fields = parseDiff(value)?.fields || {}
  return Object.entries(fields).map(([field, change]) => ({ field, ...change }))
}
const diffValue = (value: unknown) => value === null || value === undefined || value === ''
  ? '（空）' : typeof value === 'boolean' ? (value ? '开启' : '关闭')
    : typeof value === 'object' ? JSON.stringify(value) : String(value)

const route = useRoute()
const router = useRouter()
const detailReturnScroll = ref(0)
const appScrollRoot = () => document.querySelector<HTMLElement>('.app-main')
const outcomeText = (value?: string) => ({
  LOCAL_SUCCESS: '本地处理成功',
  PLATFORM_CONFIRMED: '平台已确认',
  PLATFORM_REJECTED: '平台已拒绝',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '处理失败',
  UNKNOWN: '结果未知',
  SKIPPED: '已跳过'
}[value || ''] || '其他结果')
const sourceText = (value?: string) => ({
  LOCAL: '本地系统',
  SYSTEM: '系统任务',
  PLATFORM_API: '闲鱼接口',
  PLATFORM_WEB: '闲鱼网页',
  WEBHOOK: '平台回调',
  QA_MOCK: '隔离测试', QA_FIXTURE: '隔离测试数据'
}[value || ''] || '其他来源')
const moduleText = (value?: string) => ({
  ACCOUNT: '账号', MESSAGE: '消息', ORDER: '订单', GOODS: '商品', SYSTEM: '系统',
  PRODUCT: '商品', BUYER: '买家', BATCH: '批量任务', BACKUP: '备份恢复',
  MERCHANT_OPERATIONS: '商家运营', PRODUCT_PUBLISHING: '商品发布'
}[value || ''] || '其他模块')
const targetText = (value?: string) => ({
  ACCOUNT: '账号', ORDER: '订单', GOODS: '商品', PRODUCT: '商品', BUYER: '买家',
  BATCH: '批量任务', BATCH_TASK: '批量任务', BACKUP: '备份', BACKUP_RESTORE_JOB: '备份恢复任务',
  MESSAGE: '消息', GOODS_KNOWLEDGE_VERSION: '商品知识版本', AI_HANDOFF: '人工接待任务',
  PUBLISH: '发布任务', REFUND: '售后记录', RETURN_SHIPMENT: '售后运单'
}[value || ''] || '业务对象')

const {
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
  operationTypes,
  operationModules,
  operationStatuses,
  isMobile,
  mobileView,
  selectedAccountForMobile,
  detailDialogVisible,
  detailLog,
  selectAccount,
  handlePageChange,
  handleRefresh,
  handleFilter,
  handleResetFilter,
  handleExport,
  viewDetail,
  closeDetail,
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
  handleAccountSelectChange,
  loadLogs
} = useOperationLog()

const detailSummary = computed(() => {
  if (!detailLog.value) return ''
  const log = detailLog.value
  const operator = log.operatorUsername || '系统任务'
  const target = log.targetId ? `${targetText(log.targetType)} ${log.targetId}` : moduleText(log.operationModule)
  return `${operator} 于 ${formatTime(log.createTime)} 对${target}执行“${getOperationTypeText(log.operationType)}”，结果为${getStatusText(log.operationStatus)}。`
})

const openLogDetail = async (log: typeof logs.value[number], fromDeepLink = false) => {
  if (!fromDeepLink) detailReturnScroll.value = appScrollRoot()?.scrollTop || 0
  viewDetail(log)
  await router.replace({ query: { ...route.query, accountId: String(log.xianyuAccountId), logId: String(log.id), page: String(page.value) } })
  await nextTick()
}

const closeLogDetail = async () => {
  const scrollTop = detailReturnScroll.value
  closeDetail()
  await router.replace({ query: { ...route.query, logId: undefined } })
  await nextTick()
  appScrollRoot()?.scrollTo({ top: scrollTop })
}
useModalFocusTrap(detailDialogVisible, () => document.querySelector<HTMLElement>('.ol__dialog'), () => void closeLogDetail())

watch([() => logs.value.map(log => String(log.id)).join(','), () => route.query.logId], async () => {
  const logId = Number(route.query.logId)
  if (!Number.isSafeInteger(logId) || logId <= 0 || detailLog.value) return
  const match = logs.value.find(log => Number(log.id) === logId)
  if (match) await openLogDetail(match, true)
}, { immediate: true, flush: 'post' })
watch([selectedAccountId, page, filterType, filterModule, filterStatus, filterOutcome, filterOperator, filterRequestId, filterKeyword, filterStart, filterEnd], ([accountId, currentPage]) => {
  void router.replace({ query: {
    ...route.query,
    accountId: accountId ? String(accountId) : undefined,
    page: currentPage === 1 ? undefined : String(currentPage),
    operationType: filterType.value || undefined,
    operationModule: filterModule.value || undefined,
    operationStatus: filterStatus.value === '' ? undefined : String(filterStatus.value),
    outcomeState: filterOutcome.value || undefined,
    operatorUsername: filterOperator.value.trim() || undefined,
    requestId: filterRequestId.value.trim() || undefined,
    keyword: filterKeyword.value.trim() || undefined,
    startTime: filterStart.value || undefined,
    endTime: filterEnd.value || undefined
  } })
})

// 导航栏注入
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
            handleAccountSelectChange()
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
      ]),
      h('button', {
        class: ['header-refresh-btn', { 'header-refresh-btn--loading': loading.value }],
        disabled: loading.value,
        onClick: handleRefresh
      }, [
        h(IconRefresh, { class: 'header-refresh-icon' })
      ])
    ])
  }
})

onMounted(async () => {
  const accountId = Number(route.query.accountId)
  const requestedPage = Number(route.query.page)
  if (Number.isSafeInteger(accountId) && accountId > 0) selectedAccountId.value = accountId
  if (Number.isSafeInteger(requestedPage) && requestedPage > 0) page.value = requestedPage
  filterType.value = String(route.query.operationType || '')
  filterModule.value = String(route.query.operationModule || '')
  filterStatus.value = String(route.query.operationStatus || '')
  filterOutcome.value = String(route.query.outcomeState || '')
  filterOperator.value = String(route.query.operatorUsername || '')
  filterRequestId.value = String(route.query.requestId || '')
  filterKeyword.value = String(route.query.keyword || '')
  filterStart.value = String(route.query.startTime || '')
  filterEnd.value = String(route.query.endTime || '')
  if (setHeaderContent) setHeaderContent(HeaderSelectors)
  if (selectedAccountId.value) {
    await loadLogs()
    const logId = Number(route.query.logId)
    const match = logs.value.find(log => Number(log.id) === logId)
    if (Number.isSafeInteger(logId) && logId > 0 && match && !detailLog.value) await openLogDetail(match, true)
  }
})
</script>

<template>
  <div class="ol">
    <!-- Header -->
    <div class="ol__header">
      <div class="ol__title-row">
        <div class="ol__title-icon">
          <IconLog />
        </div>
        <h1 class="ol__title">操作记录</h1>
      </div>
      <div class="ol__actions">
        <!-- Account Select -->
        <div class="ol__header-select-wrap">
          <select
            v-model="selectedAccountId"
            class="ol__header-select"
            @change="handleAccountSelectChange"
          >
            <option :value="null" disabled>选择账号</option>
            <option
              v-for="account in accounts"
              :key="account.id"
              :value="account.id"
            >
              {{ getAccountName(account) }}
            </option>
          </select>
          <span class="ol__select-icon">
            <IconChevronDown />
          </span>
        </div>
        <button class="btn btn--secondary" @click="handleRefresh">
          <IconRefresh />
          <span class="mobile-hidden">刷新</span>
        </button>
      </div>
    </div>

    <!-- Body -->
    <div class="ol__body">
      <!-- Mobile: Account Panel -->
      <div
        v-if="isMobile"
        class="ol__account-panel"
        :class="{ 'ol__account-panel--hidden': mobileView === 'logs' }"
      >
        <div class="ol__account-toolbar">
          <span class="ol__account-toolbar-title">闲鱼账号</span>
          <span v-if="accounts.length > 0" class="ol__account-toolbar-count">共 {{ accounts.length }} 个</span>
        </div>

        <!-- Loading -->
        <div v-if="loading && accounts.length === 0" class="ol__loading">
          <div class="ol__spinner"></div>
          <span>加载中...</span>
        </div>

        <!-- Mobile: Account List -->
        <div v-else class="ol__account-list">
          <div
            v-for="account in accounts"
            :key="account.id"
            class="ol__account-item"
            :class="{ 'ol__account-item--active': selectedAccountId === account.id }"
            @click="selectAccount(account.id, account)"
          >
            <div class="ol__account-avatar">{{ getAccountAvatar(account) }}</div>
            <div class="ol__account-info">
              <div class="ol__account-name">{{ getAccountName(account) }}</div>
              <div class="ol__account-id">ID: {{ account.id }}</div>
            </div>
          </div>

          <!-- Empty -->
          <div v-if="accounts.length === 0" class="ol__empty">
            <IconEmpty />
            <span class="ol__empty-text">暂无账号数据</span>
          </div>
        </div>
      </div>

      <!-- Logs Panel -->
      <div
        class="ol__logs-panel"
        :class="{ 'ol__logs-panel--hidden': isMobile && mobileView === 'accounts' }"
      >
        <!-- Mobile Header -->
        <div v-if="isMobile" class="ol__logs-toolbar">
          <button class="ol__back-btn" @click="goBackToAccounts">
            <IconChevronLeft />
            返回
          </button>
          <div v-if="selectedAccountForMobile" style="display:flex;align-items:center;gap:6px;">
            <div class="ol__account-avatar" style="width:28px;height:28px;font-size:13px;">
              {{ getAccountAvatar(selectedAccountForMobile) }}
            </div>
            <span style="font-size:13px;font-weight:500;color:var(--d-text-primary);max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
              {{ getAccountName(selectedAccountForMobile) }}
            </span>
          </div>
        </div>

        <!-- Desktop Header -->
        <template v-if="!isMobile">
          <div v-if="!selectedAccountId" class="ol__empty" style="border:none;">
            <IconInfo />
            <span class="ol__empty-text">请选择一个账号查看操作记录</span>
          </div>
        </template>

        <template v-if="selectedAccountId">
          <section class="ol__filter-bar" aria-label="审计筛选范围">
            <input v-model="filterKeyword" class="ol__filter-input ol__filter-input--wide" placeholder="描述、对象 ID 或请求 ID" @keyup.enter="handleFilter">
            <select v-model="filterType" class="ol__select" aria-label="操作类型" @change="handleFilter">
              <option v-for="option in operationTypes" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <select v-model="filterModule" class="ol__select" aria-label="业务模块" @change="handleFilter">
              <option v-for="option in operationModules" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <select v-model="filterStatus" class="ol__select" aria-label="处理状态" @change="handleFilter">
              <option v-for="option in operationStatuses" :key="String(option.value)" :value="option.value">{{ option.label }}</option>
            </select>
            <select v-model="filterOutcome" class="ol__select" aria-label="结果层" @change="handleFilter">
              <option value="">全部结果层</option>
              <option value="LOCAL_SUCCESS">本地成功</option>
              <option value="PLATFORM_CONFIRMED">平台确认</option>
              <option value="PARTIAL">部分成功</option>
              <option value="FAILED">失败</option>
              <option value="UNKNOWN">结果未知</option>
            </select>
            <input v-model="filterOperator" class="ol__filter-input" placeholder="操作者" @keyup.enter="handleFilter">
            <input v-model="filterRequestId" class="ol__filter-input" placeholder="精确请求 ID" @keyup.enter="handleFilter">
            <input v-model="filterStart" class="ol__filter-input" type="datetime-local" aria-label="开始时间">
            <input v-model="filterEnd" class="ol__filter-input" type="datetime-local" aria-label="结束时间">
            <button class="btn btn--secondary btn--sm" @click="handleFilter">查询</button>
            <button class="btn btn--ghost btn--sm" @click="handleResetFilter">重置</button>
            <button v-if="hasPermission('action:audit-export')" class="btn btn--secondary btn--sm" :disabled="exporting" @click="handleExport">
              {{ exporting ? '导出中' : '导出当前范围' }}
            </button>
          </section>
          <!-- Desktop Logs Table -->
          <div v-if="!isMobile" class="ol__logs-content">
            <!-- Loading -->
            <div v-if="loading" class="ol__loading">
              <div class="ol__spinner"></div>
              <span>加载中...</span>
            </div>

            <!-- Table -->
            <table v-if="!loading && logs.length > 0" class="ol__logs-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>操作类型</th>
                  <th>操作描述</th>
                  <th>操作人</th>
                  <th>状态</th>
                  <th>时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="log in logs" :key="log.id">
                  <td style="font-size:12px;color:var(--d-text-tertiary);">{{ log.id }}</td>
                  <td>
                    <span class="ol__log-type" :class="`ol__log-type--${getOperationTypeClass(log.operationType)}`">
                      {{ getOperationTypeText(log.operationType) }}
                    </span>
                  </td>
                  <td>
                    <span class="ol__log-desc" :title="getOperationDescriptionText(log)">{{ getOperationDescriptionText(log) }}</span>
                  </td>
                  <td>{{ log.operatorUsername || '系统任务' }}</td>
                  <td>
                    <span class="ol__log-status" :class="`ol__log-status--${getStatusClass(log.operationStatus)}`">
                      {{ getStatusText(log.operationStatus) }}
                    </span>
                  </td>
                  <td>
                    <span class="ol__log-time">{{ formatTime(log.createTime) }}</span>
                  </td>
                  <td>
                    <button class="ol__log-action-btn" @click="openLogDetail(log)">
                      详情
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>

            <!-- Empty -->
            <div v-if="!loading && logs.length === 0" class="ol__empty">
              <IconEmpty />
              <span class="ol__empty-text">暂无操作记录</span>
            </div>
          </div>

          <!-- Mobile Logs List -->
          <div v-if="isMobile" class="ol__logs-content">
            <!-- Loading -->
            <div v-if="loading" class="ol__loading">
              <div class="ol__spinner"></div>
              <span>加载中...</span>
            </div>

            <!-- Log Cards -->
            <template v-if="!loading">
              <div
                v-for="log in logs"
                :key="log.id"
                class="ol__log-card"
                @click="openLogDetail(log)"
              >
                <div class="ol__log-card-header">
                  <span class="ol__log-type" :class="`ol__log-type--${getOperationTypeClass(log.operationType)}`">
                    {{ getOperationTypeText(log.operationType) }}
                  </span>
                  <span class="ol__log-status" :class="`ol__log-status--${getStatusClass(log.operationStatus)}`">
                    {{ getStatusText(log.operationStatus) }}
                  </span>
                </div>
                <div class="ol__log-card-desc">{{ getOperationDescriptionText(log) }}</div>
                <div class="ol__log-card-meta">
                  <span class="ol__log-card-meta-item">
                    <IconClock />
                    {{ formatTime(log.createTime) }}
                  </span>
                  <span v-if="log.durationMs" class="ol__log-card-meta-item">
                    {{ formatDuration(log.durationMs) }}
                  </span>
                </div>
              </div>

              <!-- Empty -->
              <div v-if="logs.length === 0" class="ol__empty">
                <IconEmpty />
                <span class="ol__empty-text">暂无操作记录</span>
              </div>
            </template>
          </div>

          <!-- Pagination -->
          <div v-if="totalPages > 1" class="ol__pagination">
            <button
              class="ol__page-btn"
              :class="{ 'ol__page-btn--disabled': page <= 1 }"
              @click="handlePageChange(page - 1)"
            >
              <IconChevronLeft />
            </button>

            <template v-for="p in (() => {
              const btns: number[] = []
              const max = 5
              let start = Math.max(1, page - Math.floor(max / 2))
              const end = Math.min(totalPages, start + max - 1)
              start = Math.max(1, end - max + 1)
              for (let i = start; i <= end; i++) btns.push(i)
              return btns
            })()" :key="p">
              <button
                class="ol__page-btn"
                :class="{ 'ol__page-btn--active': p === page }"
                @click="handlePageChange(p)"
              >
                {{ p }}
              </button>
            </template>

            <button
              class="ol__page-btn"
              :class="{ 'ol__page-btn--disabled': page >= totalPages }"
              @click="handlePageChange(page + 1)"
            >
              <IconChevronRight />
            </button>

            <span class="ol__page-info">{{ page }} / {{ totalPages }}</span>
          </div>
        </template>
      </div>
    </div>

    <!-- Detail Dialog -->
    <Transition name="overlay-fade">
      <div
        v-if="detailDialogVisible && detailLog"
        class="ol__dialog-overlay"
        @click.self="closeLogDetail"
      >
        <div class="ol__dialog" role="dialog" aria-modal="true" aria-labelledby="operation-detail-title" tabindex="-1">
          <div class="ol__dialog-header">
            <div>
              <span class="ol__dialog-eyebrow">审计 360 档案</span>
              <h3 id="operation-detail-title" class="ol__dialog-title">{{ getOperationTypeText(detailLog.operationType) }}</h3>
            </div>
            <button class="ol__dialog-close" aria-label="关闭操作详情" @click="closeLogDetail">
              <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
          <div class="ol__dialog-body">
            <section class="ol__detail-summary" aria-label="操作结论">
              <div>
                <span>处理结论</span>
                <strong>{{ detailSummary }}</strong>
              </div>
              <span class="ol__log-status" :class="`ol__log-status--${getStatusClass(detailLog.operationStatus)}`">
                {{ getStatusText(detailLog.operationStatus) }}
              </span>
            </section>
            <section class="ol__detail-grid" aria-label="操作事实">
            <div class="ol__detail-row">
              <span class="ol__detail-label">操作类型</span>
              <span class="ol__detail-value">
                <span class="ol__log-type" :class="`ol__log-type--${getOperationTypeClass(detailLog.operationType)}`">
                  {{ getOperationTypeText(detailLog.operationType) }}
                </span>
              </span>
            </div>
            <div class="ol__detail-row">
              <span class="ol__detail-label">操作描述</span>
              <span class="ol__detail-value">{{ getOperationDescriptionText(detailLog) }}</span>
            </div>
            <div class="ol__detail-row">
              <span class="ol__detail-label">操作人</span>
              <span class="ol__detail-value">{{ detailLog.operatorUsername || '系统任务' }}</span>
            </div>
            <div class="ol__detail-row">
              <span class="ol__detail-label">状态</span>
              <span class="ol__detail-value">
                <span class="ol__log-status" :class="`ol__log-status--${getStatusClass(detailLog.operationStatus)}`">
                  {{ getStatusText(detailLog.operationStatus) }}
                </span>
              </span>
            </div>
            <div v-if="detailLog.operationModule" class="ol__detail-row">
              <span class="ol__detail-label">模块</span>
              <span class="ol__detail-value">{{ moduleText(detailLog.operationModule) }}</span>
            </div>
            <div v-if="detailLog.targetType" class="ol__detail-row">
              <span class="ol__detail-label">目标类型</span>
              <span class="ol__detail-value">{{ targetText(detailLog.targetType) }}</span>
            </div>
            <div v-if="detailLog.targetId" class="ol__detail-row">
              <span class="ol__detail-label">目标ID</span>
              <span class="ol__detail-value">{{ detailLog.targetId }}</span>
            </div>
            <div v-if="detailLog.durationMs" class="ol__detail-row">
              <span class="ol__detail-label">耗时</span>
              <span class="ol__detail-value" style="font-family:'SF Mono','Menlo',monospace;">{{ formatDuration(detailLog.durationMs) }}</span>
            </div>
            <div v-if="detailLog.requestId" class="ol__detail-row">
              <span class="ol__detail-label">请求 ID</span>
              <span class="ol__detail-value ol__detail-mono">{{ detailLog.requestId }}</span>
            </div>
            <div v-if="detailLog.outcomeState || detailLog.dataSource" class="ol__detail-row">
              <span class="ol__detail-label">结果 / 来源</span>
              <span class="ol__detail-value">{{ outcomeText(detailLog.outcomeState) }} · {{ sourceText(detailLog.dataSource) }}</span>
            </div>
            <div v-if="detailChanges(detailLog.fieldDiffJson).length" class="ol__detail-row ol__detail-row--stack">
              <span class="ol__detail-label">字段变更</span>
              <div class="ol__diff-list">
                <div v-for="change in detailChanges(detailLog.fieldDiffJson)" :key="change.field" class="ol__diff-item">
                  <strong>{{ change.label || change.field }}</strong>
                  <span class="ol__diff-before">{{ diffValue(change.before) }}</span>
                  <b aria-hidden="true">→</b>
                  <span class="ol__diff-after">{{ diffValue(change.after) }}</span>
                </div>
              </div>
            </div>
            <div v-else-if="parseDiff(detailLog.fieldDiffJson)?.changedFieldCount === 0" class="ol__detail-row">
              <span class="ol__detail-label">字段变更</span><span class="ol__detail-value">本次保存未产生字段变化</span>
            </div>
            <div class="ol__detail-row">
              <span class="ol__detail-label">时间</span>
              <span class="ol__detail-value">{{ formatTime(detailLog.createTime) }}</span>
            </div>
            </section>
            <section v-if="detailLog.errorMessage" class="ol__detail-error" role="alert">
              <strong>失败原因</strong><span>{{ detailLog.errorMessage }}</span>
            </section>
            <details v-if="detailLog.requestParams || detailLog.responseResult || detailLog.errorMessage || detailLog.fieldDiffJson" class="ol__advanced">
              <summary>查看脱敏高级详情与机器码</summary>
              <dl class="ol__machine-facts">
                <div><dt>操作代码</dt><dd>{{ detailLog.operationType }}</dd></div>
                <div><dt>结果代码</dt><dd>{{ detailLog.outcomeState || '—' }}</dd></div>
                <div><dt>来源代码</dt><dd>{{ detailLog.dataSource || '—' }}</dd></div>
              </dl>
              <div v-if="detailLog.requestParams" class="ol__detail-row ol__detail-row--stack">
                <span class="ol__detail-label">请求参数</span>
                <pre class="ol__detail-pre">{{ detailLog.requestParams }}</pre>
              </div>
              <div v-if="detailLog.responseResult" class="ol__detail-row ol__detail-row--stack">
                <span class="ol__detail-label">响应结果</span>
                <pre class="ol__detail-pre">{{ detailLog.responseResult }}</pre>
              </div>
              <div v-if="detailLog.errorMessage" class="ol__detail-row ol__detail-row--stack">
                <span class="ol__detail-label">错误信息</span>
                <pre class="ol__detail-pre ol__detail-pre--error">{{ detailLog.errorMessage }}</pre>
              </div>
            </details>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.overlay-fade-enter-active,
.overlay-fade-leave-active {
  transition: opacity 0.2s ease;
}

.overlay-fade-enter-from,
.overlay-fade-leave-to {
  opacity: 0;
}
</style>
