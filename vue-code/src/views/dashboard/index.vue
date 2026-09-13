<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useDashboard } from './useDashboard'

const router = useRouter()
const { loading, error, days, groupId, analytics, accountSummary, groups, operations, loadStatistics } = useDashboard()
const summary = computed(() => analytics.value?.summary)
const sourceLabel = (v?: string) => ({ NONE:'无数据源',LOCAL_EVENT:'本地事件',PLATFORM_API:'平台接口',PLATFORM_WEB:'平台页面',MIXED:'混合来源' }[v || ''] || v || '未知来源')
const coverageLabel = (v?: string) => ({ FULL:'完整覆盖',PARTIAL:'部分覆盖',UNSYNCED:'未同步' }[v || ''] || v || '未同步')
const evidenceText = computed(() => {
  const current = summary.value
  if (!current || current.coverageStatus === 'UNSYNCED') return `${sourceLabel(current?.source)} · — 条可信样本 · 覆盖 —/— 个店铺`
  return `${sourceLabel(current.source)} · ${current.sampleSize ?? '—'} 条可信样本 · 覆盖 ${current.coveredAccountCount ?? '—'}/${current.requestedAccountCount ?? accountSummary.value?.accountCount ?? '—'} 个店铺`
})
const valueText = (value: unknown, kind: 'number'|'money'|'percent'='number') => {
  if (value === null || value === undefined || value === '') return '—'
  const n=Number(value); if(!Number.isFinite(n)) return '—'
  if(kind==='money') return `¥ ${n.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`
  if(kind==='percent') return `${(n*100).toFixed(1)}%`
  return n.toLocaleString('zh-CN')
}
const todoCount = computed(() => operations.value.pendingTaskCount+operations.value.reviewRequiredCount+operations.value.failedTaskCount+operations.value.lowStockConfigCount)
const maxTrend = computed(() => Math.max(1,...(analytics.value?.trend||[]).map(i=>Number(i.paidOrderCount||0))))
let timer: ReturnType<typeof setInterval>|undefined
onMounted(()=>{void loadStatistics();timer=setInterval(()=>document.visibilityState==='visible'&&loadStatistics(),60000)})
onUnmounted(()=>timer&&clearInterval(timer))
</script>

<template>
  <main class="workbench dashboard" :aria-busy="loading">
    <header class="workbench__header dashboard__header">
      <div><h1>经营概览</h1><p>先看异常，再看增长；每项经营数值都标明来源与覆盖度。</p></div>
      <div class="dashboard__scope" aria-label="统计范围">
        <select v-model.number="groupId" class="workbench__select" aria-label="店铺分组" @change="loadStatistics"><option :value="undefined">全部可见店铺</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.groupName }}</option></select>
        <div class="dashboard__period" role="group" aria-label="统计周期"><button v-for="v in [1,7,30]" :key="v" :class="{active:days===v}" @click="days=v;loadStatistics()">{{ v }} 天</button></div>
        <button class="workbench__btn" :disabled="loading" @click="loadStatistics">{{ loading?'刷新中':'刷新' }}</button>
      </div>
    </header>
    <div v-if="error" class="dashboard__notice dashboard__notice--error" role="status">{{ error }}</div>
    <div class="dashboard__notice" role="note"><strong>{{ coverageLabel(summary?.coverageStatus) }}</strong><span>{{ evidenceText }}</span><time>{{ summary?.syncedAt?`最近同步 ${new Date(summary.syncedAt).toLocaleString('zh-CN')}`:'尚无可核验同步时间' }}</time></div>
    <section class="dashboard__metrics" aria-label="经营指标">
      <article class="dashboard__metric dashboard__metric--primary"><span>成交金额</span><strong>{{ valueText(summary?.gmv,'money') }}</strong><small>已同步支付订单金额</small></article>
      <article class="dashboard__metric"><span>支付订单</span><strong>{{ valueText(summary?.paidOrderCount) }}</strong><small>客单价 {{ valueText(summary?.averageOrderValue,'money') }}</small></article>
      <article class="dashboard__metric"><span>咨询买家</span><strong>{{ valueText(summary?.inquiryCount) }}</strong><small>回复率 {{ valueText(summary?.replyRate,'percent') }}</small></article>
      <article class="dashboard__metric"><span>平台曝光</span><strong>{{ valueText(summary?.exposureCount) }}</strong><small>{{ summary?.exposureCount==null?'平台数据未同步':`访问率 ${valueText(summary?.visitRate,'percent')}` }}</small></article>
      <article class="dashboard__metric"><span>退款金额</span><strong>{{ valueText(summary?.refundAmount,'money') }}</strong><small>退款率 {{ valueText(summary?.refundRate,'percent') }}</small></article>
      <article class="dashboard__metric"><span>连接正常</span><strong>{{ accountSummary?`${accountSummary.connectedCount}/${accountSummary.accountCount}`:'—' }}</strong><small>{{ accountSummary?.attentionAccountCount ?? '—' }} 个店铺需关注</small></article>
    </section>
    <section class="dashboard__columns">
      <article class="workbench__card dashboard__trend"><div class="dashboard__section-head"><div><h2>订单趋势</h2><p>{{ analytics?.range.start }} 至 {{ analytics?.range.end }}</p></div><span>{{ coverageLabel(summary?.coverageStatus) }}</span></div><div v-if="analytics?.trend?.length" class="dashboard__bars" aria-label="每日支付订单趋势"><div v-for="item in analytics.trend" :key="String(item.date)" class="dashboard__bar-item"><span class="dashboard__bar-value">{{ valueText(item.paidOrderCount) }}</span><div class="dashboard__bar-track"><i :style="{height:item.paidOrderCount==null?'2px':`${Math.max(8,Number(item.paidOrderCount)/maxTrend*100)}%`}"></i></div><small>{{ String(item.date).slice(5) }}</small></div></div><div v-else class="dashboard__empty">当前范围没有已同步趋势样本。</div></article>
      <article class="workbench__card dashboard__attention"><div class="dashboard__section-head"><div><h2>优先处理</h2><p>{{ todoCount }} 项本地待办</p></div><button class="dashboard__link" @click="router.push('/operations-health')">进入诊断</button></div><button @click="router.push('/orders?deliveryStatus=REVIEW_REQUIRED')"><span class="dot red"></span><span>履约需人工核对</span><strong>{{ operations.reviewRequiredCount }}</strong></button><button @click="router.push('/orders?deliveryStatus=FAILED')"><span class="dot orange"></span><span>履约失败</span><strong>{{ operations.failedTaskCount }}</strong></button><button @click="router.push('/kami-config?lowStock=1')"><span class="dot yellow"></span><span>卡密库存预警</span><strong>{{ operations.lowStockConfigCount }}</strong></button><button @click="router.push('/accounts')"><span class="dot blue"></span><span>店铺连接或风险</span><strong>{{ accountSummary?.attentionAccountCount ?? '—' }}</strong></button></article>
    </section>
    <section class="workbench__card dashboard__ranking"><div class="dashboard__section-head"><div><h2>店铺排行</h2><p>只按已有同步样本排序，不给无样本店铺填 0。</p></div><button class="dashboard__link" @click="router.push('/accounts')">管理店铺</button></div><div v-if="analytics?.shopRank?.length" class="dashboard__rank-list"><button v-for="(item,index) in analytics.shopRank.slice(0,8)" :key="String(item.accountId)" @click="router.push(`/accounts?accountId=${item.accountId}`)"><b>{{ index+1 }}</b><span><strong>{{ item.accountNote||`店铺 ${item.accountId}` }}</strong><small>{{ item.sampleDays }} 天样本 · {{ coverageLabel(String(item.coverageStatus)) }}</small></span><em>{{ valueText(item.gmv,'money') }}</em></button></div><div v-else class="dashboard__empty">暂无可排行的店铺经营样本。</div></section>
  </main>
</template>
<style scoped src="./dashboard.css"></style>
