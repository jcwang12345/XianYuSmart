<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getDataPanelStats, getDataPanelTrend, getRealtimeRevenue, type DataPanelStats, type DataPanelTrend } from '@/api/data-panel'

const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const stats = ref<DataPanelStats | null>(null)
const trend = ref<DataPanelTrend | null>(null)
const revenue = ref<number | null>(null)

const hasData = computed(() => stats.value?.hasData === true)
const maxTrend = computed(() => Math.max(1, ...(trend.value?.deliverySuccess || []), ...(trend.value?.deliveryFail || []), ...(trend.value?.aiReplies || [])))
const points = (values: number[]) => values.map((value, index) => {
  const x = values.length <= 1 ? 0 : index * 100 / (values.length - 1)
  const y = 90 - value * 80 / maxTrend.value
  return `${x},${y}`
}).join(' ')
const metric = (value: number | null | undefined, money = false) => {
  if (!loaded.value || error.value || !hasData.value || value === null || value === undefined) return '—'
  return money ? `¥ ${Number(value).toFixed(2)}` : Number(value).toLocaleString('zh-CN')
}

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const [statsResult, trendResult, revenueResult] = await Promise.all([
      getDataPanelStats(new Date().toISOString().slice(0, 10)),
      getDataPanelTrend(),
      getRealtimeRevenue()
    ])
    stats.value = statsResult.data || null
    trend.value = trendResult.data || null
    revenue.value = revenueResult.data === null || revenueResult.data === undefined ? null : Number(revenueResult.data)
  } catch {
    stats.value = null
    trend.value = null
    revenue.value = null
    error.value = '实时服务数据暂时无法读取；未知数据不会按 0 展示。'
  } finally {
    loaded.value = true
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="realtime" :aria-busy="loading">
    <div class="realtime__head">
      <div><h2>实时服务</h2><p>成交、履约与客服的当日运行信号；经营统计仍以“经营总览”口径为准。</p></div>
      <button class="workbench__btn" :disabled="loading" @click="load">{{ loading ? '读取中' : '刷新实时数据' }}</button>
    </div>

    <div v-if="error" class="realtime__notice realtime__notice--error" role="alert"><span>{{ error }}</span><button @click="load">重试</button></div>
    <div v-else class="realtime__notice" role="note"><strong>{{ hasData ? '本地事件已接入' : loaded ? '暂无可信样本' : '正在核对数据源' }}</strong><span>来源：本地订单、发货与消息事件</span><time>{{ loaded ? '最近读取：刚刚' : '尚未完成读取' }}</time></div>

    <div class="workbench__grid">
      <article class="workbench__card workbench__metric"><span>实时成交额</span><strong>{{ metric(revenue, true) }}</strong><small>无订单样本时显示 —</small></article>
      <article class="workbench__card workbench__metric"><span>今日订单</span><strong>{{ metric(stats?.orderCount) }}</strong><small>本地已同步订单</small></article>
      <article class="workbench__card workbench__metric"><span>交付成功</span><strong>{{ metric(stats?.deliverySuccessCount) }}</strong><small>本地成功事件</small></article>
      <article class="workbench__card workbench__metric"><span>AI 回复</span><strong>{{ metric(stats?.aiReplyCount) }}</strong><small>本地回复事件</small></article>
    </div>

    <article class="workbench__card workbench__section">
      <div class="realtime__section-title"><div><h3>近 7 天服务趋势</h3><p>三条曲线仅使用已记录的本地事件。</p></div><span>{{ hasData ? '7 天' : '未同步' }}</span></div>
      <div v-if="hasData && trend?.dates?.length" class="trend-chart">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label="近 7 天交付成功、交付失败与 AI 回复趋势">
          <line v-for="y in [10,30,50,70,90]" :key="y" x1="0" :y1="y" x2="100" :y2="y" />
          <polyline class="trend-chart__success" :points="points(trend.deliverySuccess)" />
          <polyline class="trend-chart__failed" :points="points(trend.deliveryFail)" />
          <polyline class="trend-chart__reply" :points="points(trend.aiReplies)" />
        </svg>
        <div class="trend-chart__labels"><span v-for="date in trend.dates" :key="date">{{ date }}</span></div>
        <div class="workbench__tags"><span class="workbench__tag workbench__tag--good">交付成功</span><span class="workbench__tag workbench__tag--warn">交付失败</span><span class="workbench__tag">AI 回复</span></div>
      </div>
      <div v-else-if="!loading" class="workbench__empty">产生并同步订单、发货与回复事件后，这里才会形成趋势。</div>
      <div v-else class="workbench__empty" role="status">正在读取实时服务数据…</div>
    </article>
  </section>
</template>

<style scoped>
.realtime{display:grid;gap:14px}.realtime__head,.realtime__section-title{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.realtime__head h2,.realtime__section-title h3{margin:0}.realtime__head p,.realtime__section-title p{margin:4px 0 0;color:#77736b;font-size:12px}.realtime__notice{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:12px;padding:11px 14px;border:1px solid #eed47a;border-radius:12px;color:#725000;background:var(--xy-yellow-soft);font-size:12px}.realtime__notice time{color:#8b702b}.realtime__notice--error{grid-template-columns:1fr auto;border-color:#fecaca;color:#991b1b;background:#fff1f2}.realtime__notice button{border:0;color:inherit;background:transparent;font-weight:700;cursor:pointer}.workbench__metric small{display:block;margin-top:5px;color:#8b877f;font-size:11px}.realtime__section-title>span{padding:4px 8px;border-radius:999px;color:#805700;background:#fff8d9;font-size:11px}.trend-chart{min-height:300px}.trend-chart svg{width:100%;height:250px;overflow:visible}.trend-chart line{stroke:#eaecf0;stroke-width:.3}.trend-chart polyline{fill:none;stroke-width:1.5;vector-effect:non-scaling-stroke}.trend-chart__success{stroke:#12b76a}.trend-chart__failed{stroke:#f04438}.trend-chart__reply{stroke:#9a6200}.trend-chart__labels{display:flex;justify-content:space-between;margin:6px 0 14px;color:#98a2b3;font-size:11px}
@media(max-width:767px){.realtime__head,.realtime__section-title{align-items:stretch;flex-direction:column}.realtime__notice{grid-template-columns:1fr;gap:4px}.trend-chart{min-height:220px}.trend-chart svg{height:170px}}
</style>
