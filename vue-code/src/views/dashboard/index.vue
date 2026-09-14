<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDashboard } from './useDashboard'

const route = useRoute()
const router = useRouter()
const {
  loading, error, periodMode, accountId, groupId, customStart, customEnd, accounts,
  analytics, accountSummary, groups, operations, queryDates,
  selectAccount, selectGroup, loadStatistics
} = useDashboard()

const sourceLabel = (value?: string) => ({
  NONE: '无数据源', LOCAL_EVENT: '本地事件', PLATFORM_API: '平台接口',
  PLATFORM_WEB: '平台页面', QA_FIXTURE: '隔离夹具', MIXED: '混合来源'
}[value || ''] || value || '未知来源')
const coverageLabel = (value?: string) => ({ FULL: '完整覆盖', PARTIAL: '部分覆盖', UNSYNCED: '未同步' }[value || ''] || value || '未同步')
const summary = computed(() => analytics.value?.summary)
const previous = computed(() => analytics.value?.previous)
const evidenceText = computed(() => {
  const current = summary.value
  if (!current || current.coverageStatus === 'UNSYNCED') return `${sourceLabel(current?.source)} · — 条可信样本 · 覆盖 —/— 个店铺`
  return `${sourceLabel(current.source)} · ${current.sampleSize ?? '—'} 条可信样本 · 覆盖 ${current.coveredAccountCount ?? '—'}/${current.requestedAccountCount ?? accountSummary.value?.accountCount ?? '—'} 个店铺`
})

type ValueKind = 'number'|'money'|'percent'
const valueText = (value: unknown, kind: ValueKind = 'number') => {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '—'
  if (kind === 'money') return `¥ ${numeric.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
  if (kind === 'percent') return `${(numeric * 100).toFixed(1)}%`
  return numeric.toLocaleString('zh-CN')
}
const comparison = (current: unknown, old: unknown, kind: ValueKind = 'number') => {
  if (current === null || current === undefined || old === null || old === undefined) return { text: '前周期数据未同步', tone: 'muted' }
  const now = Number(current); const before = Number(old)
  if (!Number.isFinite(now) || !Number.isFinite(before)) return { text: '前周期数据未同步', tone: 'muted' }
  if (before === 0) {
    if (now === 0) return { text: '较前周期持平', tone: 'muted' }
    return { text: `前周期为 0，本期新增 ${kind === 'money' ? valueText(now, 'money') : valueText(now)}`, tone: 'good' }
  }
  const delta = (now - before) / Math.abs(before)
  if (Math.abs(delta) < 0.0005) return { text: '较前周期持平', tone: 'muted' }
  return { text: `较前周期${delta > 0 ? '上升' : '下降'} ${Math.abs(delta * 100).toFixed(1)}%`, tone: delta > 0 ? 'good' : 'warn' }
}

const metrics = computed(() => [
  { key: 'gmv', label: '成交金额', kind: 'money' as const, help: '已同步支付订单金额', primary: true },
  { key: 'paidOrderCount', label: '支付订单', kind: 'number' as const, help: '已同步支付订单数' },
  { key: 'paidBuyerCount', label: '支付买家', kind: 'number' as const, help: '按店铺日去重，跨店可能重复' },
  { key: 'averageOrderValue', label: '客单价', kind: 'money' as const, help: '成交金额 ÷ 支付订单' },
  { key: 'refundAmount', label: '退款金额', kind: 'money' as const, help: `退款率 ${valueText(summary.value?.refundRate, 'percent')}` },
  { key: 'exposureCount', label: '平台曝光', kind: 'number' as const, help: summary.value?.exposureCount == null ? '平台数据未同步' : `访问率 ${valueText(summary.value?.visitRate, 'percent')}` },
  { key: 'visitorCount', label: '详情访客', kind: 'number' as const, help: '仅使用平台同步访客' },
  { key: 'inquiryCount', label: '咨询买家', kind: 'number' as const, help: `回复率 ${valueText(summary.value?.replyRate, 'percent')}` },
  { key: 'activeProductCount', label: '单日动销峰值', kind: 'number' as const, help: '各店单日动销之和的区间峰值' },
  { key: 'connection', label: '连接正常', kind: 'number' as const, help: `${accountSummary.value?.attentionAccountCount ?? '—'} 个店铺需关注`, localValue: accountSummary.value ? `${accountSummary.value.connectedCount}/${accountSummary.value.accountCount}` : '—' }
])
const metricValue = (source: unknown, key: string) => source && typeof source === 'object' ? (source as Record<string, unknown>)[key] : null
const selectPeriod = (value: '1'|'7'|'30'|'custom') => {
  periodMode.value = value
  if (value !== 'custom') void loadStatistics()
}

const trendMetric = ref<'paidOrderCount'|'gmv'|'inquiryCount'|'exposureCount'>('paidOrderCount')
const trendKind = computed<ValueKind>(() => trendMetric.value === 'gmv' ? 'money' : 'number')
const trendLabel = computed(() => ({ paidOrderCount: '支付订单', gmv: '成交金额', inquiryCount: '咨询买家', exposureCount: '平台曝光' }[trendMetric.value]))
const trendValues = computed(() => (analytics.value?.trend || []).map(item => item[trendMetric.value] == null ? null : Number(item[trendMetric.value])))
const maxTrend = computed(() => Math.max(1, ...trendValues.value.filter((value): value is number => value !== null)))

const funnel = computed(() => {
  const data = analytics.value?.funnel || {}
  const stages = [
    { key: 'exposure', label: '曝光' }, { key: 'visit', label: '访问' },
    { key: 'inquiry', label: '咨询' }, { key: 'payment', label: '支付' }, { key: 'deal', label: '成交' }
  ]
  return stages.map((stage, index) => {
    const value = data[stage.key]
    const previousStage = stages[index - 1]
    const prior = previousStage ? data[previousStage.key] : null
    const rate = value == null || prior == null || Number(prior) <= 0 ? null : Number(value) / Number(prior)
    return { ...stage, value, rate }
  })
})

const todoCount = computed(() => operations.value.pendingTaskCount + operations.value.reviewRequiredCount + operations.value.failedTaskCount + operations.value.lowStockConfigCount)
const rankingType = ref<'shop'|'product'>('shop')
const rankingDirection = ref<'top'|'bottom'>('top')
const shopMetric = ref<'gmv'|'paidOrderCount'|'replyRate'|'refundRate'>('gmv')
const productMetric = ref<'paidAmount'|'paidOrderCount'|'exposureCount'|'paymentRate'>('paidAmount')
const rankingMetric = computed(() => rankingType.value === 'shop' ? shopMetric.value : productMetric.value)
const rankValueKind = computed<ValueKind>(() => ['gmv', 'paidAmount'].includes(rankingMetric.value) ? 'money' : ['replyRate', 'refundRate', 'paymentRate'].includes(rankingMetric.value) ? 'percent' : 'number')
const rankedItems = computed(() => {
  const rows = [...(rankingType.value === 'shop' ? analytics.value?.shopRank || [] : analytics.value?.productRank || [])]
  const metric = rankingMetric.value
  return rows.filter(item => item[metric] !== null && item[metric] !== undefined)
    .sort((left, right) => (Number(left[metric]) - Number(right[metric])) * (rankingDirection.value === 'top' ? -1 : 1)).slice(0, 10)
})
const metricOptionLabel = (key: string) => ({ gmv: '成交金额', paidAmount: '支付金额', paidOrderCount: '支付订单', replyRate: '回复率', refundRate: '退款率', exposureCount: '曝光', paymentRate: '访问支付率' }[key] || key)

const accountName = (id: unknown) => accounts.value.find(account => String(account.id) === String(id))?.accountNote || `店铺 ${id ?? '—'}`
type AnomalyItem = Record<string, unknown> & { domain: 'PRODUCT'|'SHOP' }
const anomalies = computed<AnomalyItem[]>(() => [
  ...(analytics.value?.productAnomalies || []).map(item => ({ ...item, domain: 'PRODUCT' as const })),
  ...(analytics.value?.anomalies || []).map(item => ({ ...item, domain: 'SHOP' as const }))
])
const anomalyEvidence = (item: Record<string, unknown>) => {
  if (item.anomalyType === 'HIGH_EXPOSURE_LOW_CLICK') return `曝光 ${valueText(item.exposureCount)} · 点击 ${valueText(item.clickCount)}`
  if (item.anomalyType === 'HIGH_INQUIRY_LOW_PAYMENT') return `咨询 ${valueText(item.inquiryCount)} · 支付 ${valueText(item.paidOrderCount)}`
  if (item.anomalyType === 'LOW_STOCK') return '平台库存已同步且不高于 2'
  if (item.anomalyType === 'HIGH_REFUND_RATE') return `支付 ${valueText(item.paidOrderCount)} · 退款 ${valueText(item.refundOrderCount)}`
  return `${sourceLabel(String(item.source || ''))} · ${coverageLabel(String(item.coverageStatus || ''))}`
}
const navigateSafe = (path: unknown) => {
  const target = String(path || '')
  if (target.startsWith('/') && !target.startsWith('//')) void router.push(target)
}
const selectRankedShop = (id: unknown) => {
  const value = Number(id)
  if (!Number.isSafeInteger(value) || value <= 0) return
  accountId.value = value
  groupId.value = undefined
  void router.replace({ path: '/dashboard', query: { accountId: String(value) } })
  void loadStatistics()
}

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  const routeAccount = Number(route.query.accountId)
  if (Number.isSafeInteger(routeAccount) && routeAccount > 0) accountId.value = routeAccount
  void loadStatistics()
  timer = setInterval(() => document.visibilityState === 'visible' && loadStatistics(), 60000)
})
onUnmounted(() => timer && clearInterval(timer))
</script>

<template>
  <main class="workbench dashboard" :aria-busy="loading">
    <header class="workbench__header dashboard__header">
      <div><span class="eyebrow">BUSINESS COMPASS</span><h1>经营罗盘</h1><p>从全店趋势定位异常，再下钻到具体店铺、商品和订单。</p></div>
      <button class="workbench__btn" :disabled="loading" @click="loadStatistics">{{ loading ? '读取中' : '刷新数据' }}</button>
    </header>

    <section class="workbench__card dashboard__filters" aria-label="经营数据范围">
      <label><span>单个店铺</span><select v-model="accountId" class="workbench__select" @change="selectAccount();loadStatistics()"><option :value="undefined">全部可见店铺</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb || `店铺 ${account.id}` }}</option></select></label>
      <label><span>店铺分组</span><select v-model="groupId" class="workbench__select" @change="selectGroup();loadStatistics()"><option :value="undefined">不限定分组</option><option v-for="group in groups" :key="group.id" :value="group.id">{{ group.groupName }}</option></select></label>
      <div class="dashboard__period" role="group" aria-label="统计周期"><button v-for="value in (['1','7','30','custom'] as const)" :key="value" :class="{active:periodMode===value}" @click="selectPeriod(value)">{{ value === 'custom' ? '自定义' : `${value} 天` }}</button></div>
      <div v-if="periodMode==='custom'" class="dashboard__custom-range"><label><span>开始日期</span><input v-model="customStart" type="date" class="workbench__input"></label><i>至</i><label><span>结束日期</span><input v-model="customEnd" type="date" class="workbench__input"></label><button class="workbench__btn" @click="loadStatistics">应用</button></div>
    </section>

    <div v-if="error" class="dashboard__notice dashboard__notice--error" role="alert"><span>{{ error }}</span><button class="dashboard__link" @click="loadStatistics">重试</button></div>
    <div class="dashboard__notice" role="note"><strong>{{ coverageLabel(summary?.coverageStatus) }}</strong><span>{{ evidenceText }}</span><time>{{ summary?.syncedAt ? `最近同步 ${new Date(summary.syncedAt).toLocaleString('zh-CN')}` : '尚无可核验同步时间' }}</time></div>

    <section class="dashboard__metrics" aria-label="经营核心指标">
      <article v-for="metric in metrics" :key="metric.key" :class="['dashboard__metric', {'dashboard__metric--primary':metric.primary}]">
        <span>{{ metric.label }}</span><strong>{{ metric.localValue ?? valueText(metricValue(summary,metric.key), metric.kind) }}</strong><small>{{ metric.help }}</small>
        <em v-if="!metric.localValue" :class="comparison(metricValue(summary,metric.key), metricValue(previous,metric.key), metric.kind).tone">{{ comparison(metricValue(summary,metric.key), metricValue(previous,metric.key), metric.kind).text }}</em>
      </article>
    </section>

    <section class="dashboard__columns">
      <article class="workbench__card dashboard__trend">
        <div class="dashboard__section-head"><div><h2>{{ trendLabel }}趋势</h2><p>{{ queryDates.start }} 至 {{ queryDates.end }}；缺失日期保持空值</p></div><select v-model="trendMetric" class="workbench__select" aria-label="趋势指标"><option value="paidOrderCount">支付订单</option><option value="gmv">成交金额</option><option value="inquiryCount">咨询买家</option><option value="exposureCount">平台曝光</option></select></div>
        <div v-if="analytics?.trend?.length" class="dashboard__bars" :aria-label="`${trendLabel}每日趋势`"><div v-for="(item,index) in analytics.trend" :key="String(item.date)" class="dashboard__bar-item"><span class="dashboard__bar-value">{{ valueText(item[trendMetric], trendKind) }}</span><div :class="['dashboard__bar-track',{'is-missing':trendValues[index]===null}]"><i :style="{height:trendValues[index]===null?'2px':`${Math.max(8,Number(trendValues[index])/maxTrend*100)}%`}"></i></div><small>{{ String(item.date).slice(5) }}</small></div></div>
        <div v-else class="dashboard__empty">当前范围没有已同步趋势样本。</div>
      </article>

      <article class="workbench__card dashboard__funnel">
        <div class="dashboard__section-head"><div><h2>经营漏斗</h2><p>曝光 → 访问 → 咨询 → 支付 → 成交</p></div><span>{{ coverageLabel(summary?.coverageStatus) }}</span></div>
        <ol><li v-for="stage in funnel" :key="stage.key"><div><strong>{{ stage.label }}</strong><span>{{ valueText(stage.value) }}</span></div><small>{{ stage.rate == null ? '转化率未同步' : `上一步转化 ${valueText(stage.rate,'percent')}` }}</small></li></ol>
        <p>默认使用人数/订单口径；上游未同步时不计算虚假转化率。</p>
      </article>
    </section>

    <section class="dashboard__columns dashboard__columns--secondary">
      <article class="workbench__card dashboard__attention"><div class="dashboard__section-head"><div><h2>履约与连接待办</h2><p>{{ todoCount }} 项本地待办</p></div><button class="dashboard__link" @click="router.push('/operations-health')">进入诊断</button></div><button @click="router.push('/orders?deliveryStatus=REVIEW_REQUIRED')"><span class="dot red"></span><span>履约需人工核对</span><strong>{{ operations.reviewRequiredCount }}</strong></button><button @click="router.push('/orders?deliveryStatus=FAILED')"><span class="dot orange"></span><span>履约失败</span><strong>{{ operations.failedTaskCount }}</strong></button><button @click="router.push('/kami-config?lowStock=1')"><span class="dot yellow"></span><span>卡密库存预警</span><strong>{{ operations.lowStockConfigCount }}</strong></button><button @click="router.push('/accounts')"><span class="dot blue"></span><span>店铺连接或风险</span><strong>{{ accountSummary?.attentionAccountCount ?? '—' }}</strong></button></article>

      <article class="workbench__card dashboard__anomalies"><div class="dashboard__section-head"><div><h2>经营异常</h2><p>只根据已同步证据生成，不把缺失数据当成 0。</p></div><span>{{ anomalies.length }} 项</span></div><div v-if="anomalies.length" class="dashboard__anomaly-list"><article v-for="(item,index) in anomalies.slice(0,8)" :key="`${item.domain}-${item.accountId}-${item.goodsId||item.metricDate}-${index}`"><div><span :class="['status',item.severity==='HIGH'?'bad':'warn']">{{ item.severity==='HIGH'?'重点':'提醒' }}</span><small>{{ accountName(item.accountId) }} · {{ item.domain==='PRODUCT'?'商品':'店铺' }}</small></div><strong>{{ item.title || item.anomalyType }}</strong><p>{{ anomalyEvidence(item) }}</p><em>{{ item.recommendation }}</em><button class="dashboard__link" @click="navigateSafe(item.targetRoute)">查看并处理</button></article></div><div v-else class="dashboard__empty">当前同步范围内没有命中经营异常；这不代表未同步数据没有风险。</div></article>
    </section>

    <section class="workbench__card dashboard__ranking">
      <div class="dashboard__section-head"><div><h2>{{ rankingType==='shop'?'店铺':'商品' }}排行</h2><p>仅对已有同步样本排序，无样本对象不会被填成 0。</p></div><div class="dashboard__ranking-controls"><div class="dashboard__tabs"><button :class="{active:rankingType==='shop'}" @click="rankingType='shop'">店铺</button><button :class="{active:rankingType==='product'}" @click="rankingType='product'">商品</button></div><select v-if="rankingType==='shop'" v-model="shopMetric" class="workbench__select"><option value="gmv">成交金额</option><option value="paidOrderCount">支付订单</option><option value="replyRate">回复率</option><option value="refundRate">退款率</option></select><select v-else v-model="productMetric" class="workbench__select"><option value="paidAmount">支付金额</option><option value="paidOrderCount">支付订单</option><option value="exposureCount">曝光</option><option value="paymentRate">访问支付率</option></select><select v-model="rankingDirection" class="workbench__select"><option value="top">Top 10</option><option value="bottom">Bottom 10</option></select></div></div>
      <div v-if="rankedItems.length" class="dashboard__rank-list"><button v-for="(item,index) in rankedItems" :key="`${rankingType}-${item.accountId}-${item.goodsId||''}`" @click="rankingType==='shop'?selectRankedShop(item.accountId):router.push(`/goods?accountId=${item.accountId}&search=${encodeURIComponent(String(item.goodsId))}`)"><b>{{ index+1 }}</b><span><strong>{{ rankingType==='shop'?(item.accountNote||`店铺 ${item.accountId}`):(item.title||item.goodsId) }}</strong><small>{{ rankingType==='product'?`${item.accountNote||`店铺 ${item.accountId}`} · `:'' }}{{ item.sampleDays }} 天样本 · {{ coverageLabel(String(item.coverageStatus)) }}</small></span><em><small>{{ metricOptionLabel(rankingMetric) }}</small>{{ valueText(item[rankingMetric],rankValueKind) }}</em></button></div>
      <div v-else class="dashboard__empty">当前指标没有可排行的可信样本。</div>
    </section>

    <details class="workbench__card dashboard__definitions"><summary>数据口径与覆盖说明</summary><dl><div v-for="(description,key) in analytics?.definitions" :key="key"><dt>{{ key }}</dt><dd>{{ description }}</dd></div></dl><p>当前区间：{{ queryDates.start }} 至 {{ queryDates.end }}。不同来源、不同覆盖度的数据不会被静默合并成“完整数据”。</p></details>
  </main>
</template>

<style scoped src="./dashboard.css"></style>
