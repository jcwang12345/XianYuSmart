<script setup lang="ts">
import LegacyRouteNotice from '@/components/navigation/LegacyRouteNotice.vue'
</script>

<template>
  <LegacyRouteNotice
    eyebrow="ROUTE CONSOLIDATED"
    title="数据看板已并入经营罗盘"
    description="原页面的实时成交、履约和客服信号现在作为经营罗盘中的独立页签展示，避免同一个经营指标在两个入口出现不同解释。"
    reason="这个兼容地址只保留导航说明，不再独立取数，也不会在数据尚未同步时显示为 0。"
    :actions="[
      { label: '打开实时服务', path: '/dashboard?view=realtime', primary: true, description: '查看当日成交、履约与客服信号' },
      { label: '打开经营总览', path: '/dashboard', description: '查看统一口径的趋势、漏斗与排行' }
    ]"
  />
</template>
