<script setup lang="ts">
import { computed, ref } from 'vue'
import type { MatrixAccount } from '@/api/matrix'

const props = defineProps<{ account: MatrixAccount; loading?: boolean; syncing?: boolean; riskSyncing?: boolean }>()
defineEmits<{
  close: []
  refresh: []
  'sync-profile': []
  'sync-risks': []
  'export-risks': []
  dashboard: []
}>()

const profile = computed<Record<string, any>>(() => props.account.profile || {})
const riskDataset = computed<Record<string, any>>(() => props.account.datasetEvidence?.shopRisks || {})
const avatarValid = computed(() => typeof profile.value.avatarUrl === 'string' && profile.value.avatarUrl.startsWith('https://'))
const score = computed<number | null>(() => {
  const raw = profile.value.shopScore
  if (raw == null || (typeof raw !== 'number' && typeof raw !== 'string') || (typeof raw === 'string' && !raw.trim())) return null
  const number = Number(raw)
  return Number.isFinite(number) ? number : null
})
const scoreWidth = computed(() => score.value === null ? null : Math.min(100, Math.max(0, score.value)))
const fishLabels = computed(() => [
  { key: 'seller', label: profile.value.sellerCredit },
  { key: 'buyer', label: profile.value.buyerCredit },
].filter(item => Boolean(item.label)))
const risks = computed<Array<Record<string, any>>>(() => props.account.risks || [])
const activeRisks = computed(() => risks.value.filter(item => item.riskStatus === 'ACTIVE'))
const severeRisks = computed(() => activeRisks.value.filter(item => ['CRITICAL', 'HIGH'].includes(String(item.severity))))
const recentRisks = computed(() => {
  const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000
  return risks.value.filter(item => {
    const number = Date.parse(String(item.penaltyTime || item.effectiveTime || ''))
    return Number.isFinite(number) && number >= cutoff
  })
})
const riskFilter = ref<'ALL' | 'ACTIVE' | 'APPEALING' | 'APPEALED'>('ALL')
const filteredRisks = computed(() => risks.value.filter(item => {
  if (riskFilter.value === 'ACTIVE') return item.riskStatus === 'ACTIVE'
  if (riskFilter.value === 'APPEALING') return item.appealStatus === 'SUBMITTED'
  if (riskFilter.value === 'APPEALED') return ['ACCEPTED', 'REJECTED'].includes(String(item.appealStatus))
  return true
}))
const online = computed(() => props.account.connectionStatus === 'CONNECTED')
const authorized = computed(() => props.account.authorizationStatus === 'AUTHORIZED')
const messageChannel = computed<Record<string, any> | undefined>(() =>
  props.account.accessChannels?.find(channel => channel.channelCode === 'MESSAGE_WS') as Record<string, any> | undefined,
)
const messageOnline = computed(() => messageChannel.value?.connectionStatus === 'CONNECTED' || online.value)
const credentialValid = computed(() => messageChannel.value?.authorizationStatus === 'AUTHORIZED' || authorized.value)
const credentialScope = computed(() => String(messageChannel.value?.authorizationScope || '仅表示消息会话与登录凭据；不代表店铺、处罚或平台写能力已授权'))
const shopAuthorized = computed(() => {
  const state = profile.value.shopAuthorization
  return state === true || state?.authorized === true || state?.status === 'AUTHORIZED'
})
const shopAuthorizationKnown = computed(() => {
  const state = profile.value.shopAuthorization
  return state === true || state === false || (state && typeof state === 'object' && state.status && state.status !== 'UNSUPPORTED' && state.status !== 'UNSYNCED')
})
const riskRealtimeVerified = computed(() => props.account.accessChannels?.some(channel => {
  if (channel.channelCode !== 'RISK_EVENTS') return false
  const capabilities = channel.capabilities as Record<string, unknown> | undefined
  return channel.connectionStatus === 'CONNECTED' && capabilities?.realtimeRiskEvents === true
}) === true)

const value = (input: unknown) => input === null || input === undefined || input === '' ? '—' : String(input)
const yesNo = (input: unknown) => input === true ? '是' : input === false ? '否' : '—'
const percent = (input: unknown) => {
  if (input === null || input === undefined || input === '') return '—'
  const number = Number(input)
  if (!Number.isFinite(number)) return String(input)
  return `${(number <= 1 ? number * 100 : number).toFixed(1).replace(/\.0$/, '')}%`
}
const time = (input?: string) => input ? new Date(input).toLocaleString('zh-CN', { hour12: false }) : '未同步'
const userType = (input: unknown) => ({ '1': 'C 端 · 普通用户', '2': 'B 端 · 商家用户' }[String(input ?? '')] || value(input))
const riskStatus = (item: Record<string, any>) => ({
  ACTIVE: '生效中', RESOLVED: '已失效', EXPIRED: '已失效', UNKNOWN: '状态待核验',
  SUBMITTED: '申诉中', ACCEPTED: '申诉通过', REJECTED: '申诉未通过',
}[String(item.appealStatus === 'SUBMITTED' ? item.appealStatus : item.riskStatus)] || value(item.riskStatus))
const severity = (input: unknown) => ({ CRITICAL: '严重', HIGH: '高风险', WARNING: '警告', INFO: '提醒' }[String(input)] || '待核验')
</script>

<template>
  <div class="profile-backdrop" @click.self="$emit('close')">
    <section class="profile-dialog" role="dialog" aria-modal="true" aria-labelledby="shop-profile-title" tabindex="-1">
      <header class="profile-header">
        <div class="shop-identity">
          <div class="shop-avatar">
            <img v-if="avatarValid" :src="profile.avatarUrl" alt="店铺头像">
            <span v-else>{{ (profile.shopNickname || account.accountNote || '闲').slice(0, 1) }}</span>
          </div>
          <div class="shop-identity__content">
            <div class="shop-title-row">
              <h2 id="shop-profile-title">{{ profile.shopNickname || account.accountNote || `账号 ${account.accountId}` }}</h2>
              <span v-if="profile.superSeller" class="identity-tag identity-tag--gold">超级卖家</span>
              <span v-if="profile.realNameVerified" class="identity-tag identity-tag--green">◇ 已实名</span>
              <span v-if="shopAuthorized" class="identity-tag identity-tag--green">◇ 店铺授权</span>
              <span v-else-if="!shopAuthorizationKnown" class="identity-tag identity-tag--neutral">店铺授权未同步</span>
            </div>
            <p>
              <span>店铺 ID&nbsp; {{ account.unb || account.accountId }}</span><i></i>
              <span>鱼小铺 {{ value(profile.shopLevel) }}</span><i></i>
              <span>⌖ {{ value(profile.region) }}</span><i></i>
              <b :class="online ? 'online' : 'offline'">● {{ online ? '在线' : '离线' }}</b>
            </p>
          </div>
        </div>
        <div class="profile-actions">
          <div class="session-state">
            <span :class="messageOnline ? 'online' : 'offline'" :aria-label="`消息会话${messageOnline ? '在线' : '离线'}`">● 消息会话&nbsp; {{ messageOnline ? '在线' : '离线' }}</span>
            <span :class="credentialValid ? 'online' : 'offline'" :title="credentialScope" :aria-label="`登录凭据${credentialValid ? '有效' : '无效或待核验'}；${credentialScope}`">● 登录凭据&nbsp; {{ credentialValid ? '有效' : '待核验' }}</span>
          </div>
          <button class="outline-button" type="button" @click="$emit('dashboard')">▥&nbsp; 数据看板</button>
          <button class="outline-button" type="button" :disabled="loading || syncing" @click="$emit('sync-profile')">↻&nbsp; {{ syncing ? '刷新中…' : '刷新店铺详情' }}</button>
          <button class="close-button" type="button" aria-label="关闭店铺详情" @click="$emit('close')">×</button>
        </div>
      </header>

      <div class="profile-body" :aria-busy="loading || syncing || riskSyncing">
        <aside class="profile-left">
          <section class="info-card level-card">
            <header><strong><span class="card-icon">♙</span> 鱼小铺等级</strong><small>module.shop</small></header>
            <div class="level-summary">
              <span class="level-chip">{{ value(profile.shopLevel) }}</span>
              <span v-if="profile.superSeller" class="seller-chip">超级卖家</span>
              <b>{{ value(profile.shopScore) }}</b><small>距下一级 {{ score === null ? '—' : Math.max(0, 100 - score) }} 分</small>
            </div>
            <div :class="['score-bar', { unknown: scoreWidth === null }]" :aria-label="score === null ? '店铺分未同步' : `店铺分 ${score}`"><i v-if="scoreWidth !== null" :style="{ width: `${scoreWidth}%` }"></i></div>
            <div class="level-detail">↗ 等级详情</div>
            <div class="fish-labels">
              <div><strong><span class="spark">✣</span> 鱼力值标签</strong><small>module.base.ylzTags</small></div>
              <p v-if="fishLabels.length"><span v-for="item in fishLabels" :key="item.key" :title="`${item.label}；平台未返回独立信用等级编号`">— {{ item.label }}</span></p>
              <p v-else class="muted-copy">{{ account.profileCoverageStatus === 'UNSYNCED' ? '店铺画像尚未同步，标签未知' : '已有画像未包含鱼力值标签' }}</p>
            </div>
          </section>

          <section class="info-card metrics-card">
            <header><strong><span class="card-icon">⌁</span> 店铺指标</strong><small>user.page.nav</small></header>
            <div class="metric-grid">
              <span><small>在售</small><strong>{{ value(profile.onSaleCount) }}</strong></span>
              <span><small>粉丝</small><strong>{{ value(profile.followerCount) }}</strong></span>
              <span><small>好评率</small><strong class="green-text">{{ percent(profile.positiveRate) }}</strong></span>
              <span><small>店铺分</small><strong>{{ value(profile.shopScore) }}</strong></span>
            </div>
          </section>

          <section class="info-card portrait-card">
            <header><strong><span class="card-icon">♙</span> 信用画像</strong><small>user.page.head</small></header>
            <dl>
              <div><dt>卖家信用</dt><dd>{{ value(profile.sellerCredit) }}</dd></div>
              <div><dt>买家信用</dt><dd>{{ value(profile.buyerCredit) }}</dd></div>
              <div><dt>芝麻认证</dt><dd :class="profile.sesameVerified ? 'green-text' : ''">{{ yesNo(profile.sesameVerified) }}</dd></div>
              <div><dt>实名认证</dt><dd :class="profile.realNameVerified ? 'green-text' : ''">{{ yesNo(profile.realNameVerified) }}</dd></div>
              <div><dt>闲鱼达人</dt><dd :class="profile.xianyuExpert ? 'green-text' : ''">{{ yesNo(profile.xianyuExpert) }}</dd></div>
            </dl>
          </section>

          <section class="info-card business-card">
            <header><strong><span class="card-icon">▥</span> 业务身份</strong><small>{{ time(account.profileSyncedAt) }} 刷新</small></header>
            <dl>
              <div><dt>用户类型</dt><dd>{{ userType(profile.userType) }}</dd></div>
              <div><dt>已升级闲鱼</dt><dd :class="profile.xianyuUpgraded ? 'green-text' : ''">{{ yesNo(profile.xianyuUpgraded) }}</dd></div>
              <div><dt>已绑定淘宝</dt><dd :class="profile.taobaoBound ? 'green-text' : ''">{{ yesNo(profile.taobaoBound) }}</dd></div>
              <div><dt>置顶限额</dt><dd>{{ value(profile.pinLimit) }}{{ profile.pinLimit == null ? '' : ' 个' }}</dd></div>
              <div><dt>累计评价</dt><dd>{{ value(profile.totalReviewCount) }}</dd></div>
              <div><dt>累计买入</dt><dd>{{ value(profile.totalBoughtCount) }}</dd></div>
              <div><dt>累计卖出</dt><dd>{{ value(profile.totalSoldCount) }}</dd></div>
            </dl>
          </section>
        </aside>

        <main class="profile-right">
          <section class="risk-summary">
            <header>
              <strong><span class="shield">♢</span> 闲鱼安全处置消息</strong>
              <div><small>上次同步 {{ time(account.riskSyncedAt) }}</small><button type="button" :disabled="riskSyncing" @click="$emit('sync-risks')">↻ {{ riskSyncing ? '同步中…' : '刷新' }}</button></div>
            </header>
            <div class="risk-metrics">
              <span><small>30 天处罚总数</small><strong>{{ account.riskCoverageStatus === 'UNSYNCED' ? '—' : recentRisks.length }}</strong></span>
              <span><small>当前生效</small><strong class="orange-text">{{ account.riskCoverageStatus === 'UNSYNCED' ? '—' : activeRisks.length }}</strong></span>
              <span><small>严重处罚</small><strong class="red-text">{{ account.riskCoverageStatus === 'UNSYNCED' ? '—' : severeRisks.length }}</strong></span>
            </div>
            <div v-if="riskDataset.syncStatus === 'FAILED'" class="sync-error">
              <strong>平台能力未开通</strong>
              <span>{{ riskDataset.lastErrorMessage }}</span>
              <small>{{ riskDataset.recoveryHint }}</small>
            </div>
          </section>

          <section class="risk-list-card">
            <header class="risk-list-header">
              <div><h3>处罚记录（按时间倒序）</h3><span>{{ account.riskCoverageStatus === 'UNSYNCED' ? '—' : risks.length }}</span></div>
              <div><button type="button" :disabled="riskSyncing" @click="$emit('sync-risks')">↻ 重新查询</button><button type="button" @click="$emit('export-risks')">⇩ 导出 CSV</button><button type="button" disabled title="告警规则在通知中心统一配置">♧ 设置告警</button></div>
            </header>
            <div class="risk-tabs" role="tablist" aria-label="处罚记录筛选">
              <button :class="{ active: riskFilter === 'ALL' }" role="tab" :aria-selected="riskFilter === 'ALL'" @click="riskFilter = 'ALL'">全部</button>
              <button :class="{ active: riskFilter === 'ACTIVE' }" role="tab" :aria-selected="riskFilter === 'ACTIVE'" @click="riskFilter = 'ACTIVE'">生效中</button>
              <button :class="{ active: riskFilter === 'APPEALING' }" role="tab" :aria-selected="riskFilter === 'APPEALING'" @click="riskFilter = 'APPEALING'">申诉中</button>
              <button :class="{ active: riskFilter === 'APPEALED' }" role="tab" :aria-selected="riskFilter === 'APPEALED'" @click="riskFilter = 'APPEALED'">已申诉</button>
            </div>
            <div v-if="filteredRisks.length" class="risk-items">
              <article v-for="risk in filteredRisks" :key="String(risk.riskId || risk.dedupeKey)" :class="`severity-${String(risk.severity || 'WARNING').toLowerCase()}`">
                <header><div><span class="warning-tag">{{ severity(risk.severity) }}</span><span class="active-tag">{{ riskStatus(risk) }}</span><strong>{{ risk.riskName || '闲鱼平台处罚' }}</strong></div><code>{{ risk.ruleCode || risk.platformPenaltyId || '规则编号未返回' }}</code></header>
                <p>{{ risk.impactSummary || '平台未返回处罚原因，请进入闲鱼安全中心核对。' }}</p>
                <details><summary>▶ 运营建议</summary><p>{{ risk.recommendedAction || risk.operationAdvice || '先核对平台原始通知和影响范围，再决定是否人工处理。' }}</p></details>
                <footer><span>处罚于 {{ time(String(risk.penaltyTime || risk.effectiveTime || '')) }}&nbsp;&nbsp; {{ severity(risk.severity) }}</span><b>{{ risk.appealDeadline ? `申诉截止 ${time(String(risk.appealDeadline))}` : risk.appealStatus === 'AVAILABLE' ? '可在平台发起申诉' : '申诉信息未返回' }}</b></footer>
              </article>
            </div>
            <div v-else class="risk-empty">
              <strong>{{ account.riskCoverageStatus === 'UNSYNCED' ? '处罚数据尚未同步' : riskFilter === 'ALL' ? '已同步范围内暂无处罚记录' : '当前筛选下暂无记录' }}</strong>
              <p>{{ account.riskCoverageStatus === 'UNSYNCED' ? '请点击“刷新”从平台只读查询；未同步不等于零处罚。' : '平台后续返回新记录时会按时间倒序显示。' }}</p>
            </div>
          </section>
        </main>
      </div>

      <footer class="profile-footer">
        <div><span :class="riskRealtimeVerified ? 'online' : 'unverified'">● {{ riskRealtimeVerified ? '处罚事件订阅已核验' : '处罚消息能力未核验' }}</span><i></i><span>画像更新于 {{ time(account.profileSyncedAt) }}</span><i></i><kbd>ESC</kbd><span>关闭</span></div>
        <div><button class="footer-button" type="button" @click="$emit('close')">关闭</button><button class="footer-button footer-button--primary" type="button" :disabled="syncing" @click="$emit('sync-profile')">↻ {{ syncing ? '刷新中…' : '刷新店铺详情' }}</button></div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.profile-backdrop{position:fixed;inset:0;z-index:1400;display:grid;place-items:center;padding:32px;background:rgba(28,32,39,.54);backdrop-filter:blur(6px)}.profile-dialog{display:flex;width:min(1480px,calc(100vw - 64px));height:min(900px,calc(100dvh - 64px));min-height:640px;overflow:hidden;flex-direction:column;border:1px solid rgba(255,255,255,.92);border-radius:22px;background:#f8f8f6;box-shadow:0 28px 80px rgba(16,20,29,.3);color:#252522}.profile-header{display:flex;align-items:center;justify-content:space-between;gap:20px;min-height:92px;padding:16px 24px;border-bottom:1px solid #ecebe6;background:#fff}.shop-identity{display:flex;align-items:center;gap:14px;min-width:0}.shop-avatar{display:grid;width:58px;height:58px;flex:0 0 58px;overflow:hidden;place-items:center;border:4px solid #d8f0ed;border-radius:17px;background:linear-gradient(145deg,#bce7e3,#ffe06d);font-size:24px;font-weight:800}.shop-avatar img{width:100%;height:100%;object-fit:cover}.shop-identity__content{min-width:0}.shop-title-row{display:flex;align-items:center;gap:7px;min-width:0}.shop-title-row h2{overflow:hidden;margin:0;font-size:22px;font-weight:800;text-overflow:ellipsis;white-space:nowrap}.identity-tag{padding:4px 8px;border-radius:5px;font-size:11px;font-weight:700;white-space:nowrap}.identity-tag--gold{color:#8e5700;background:#ffefb4}.identity-tag--green{color:#187750;background:#e8faef}.shop-identity p{display:flex;align-items:center;gap:9px;margin:7px 0 0;color:#85827b;font-size:12px;white-space:nowrap}.shop-identity p i,.profile-footer i{width:1px;height:13px;background:#dddcd6}.online{color:#16a262!important}.offline{color:#d9534f!important}.profile-actions{display:flex;align-items:center;gap:8px}.session-state{display:grid;gap:3px;padding:6px 10px;border:1px solid #eeeeea;border-radius:10px;background:#fff;font-size:10px}.outline-button,.footer-button{min-height:38px;padding:0 16px;border:1px solid #e5e4df;border-radius:10px;color:#353631;background:#fff;font-size:13px;font-weight:700}.outline-button:disabled,.footer-button:disabled,.risk-summary button:disabled{cursor:not-allowed;opacity:.55}.close-button{width:38px;height:38px;border:0;color:#76746d;background:transparent;font-size:24px}.profile-body{display:grid;grid-template-columns:40% 60%;min-height:0;flex:1;overflow-y:auto}.profile-left,.profile-right{display:grid;align-content:start;gap:16px;padding:20px;min-width:0}.profile-left{padding-right:16px;border-right:1px solid #e9e8e3}.profile-right{padding-left:16px}.info-card,.risk-summary,.risk-list-card{border:1px solid #ebe6dc;border-radius:16px;background:#fff;box-shadow:0 1px 3px rgba(73,58,22,.06)}.info-card{padding:18px}.info-card>header,.risk-summary>header,.risk-list-header{display:flex;align-items:center;justify-content:space-between;gap:10px}.info-card>header strong,.risk-summary>header strong{font-size:16px}.info-card>header small{color:#aaa69d;font-size:10px}.card-icon{color:#c98d00}.level-summary{display:flex;align-items:center;gap:9px;margin-top:15px}.level-summary b{margin-left:auto;font-size:16px}.level-summary>small{color:#aaa69d;font-size:11px}.level-chip,.seller-chip{padding:4px 8px;border-radius:5px;font-size:11px;font-weight:800}.level-chip{color:#985800;background:#fff0b4}.seller-chip{color:#c33245;background:#ffe8eb}.score-bar{height:7px;margin:10px 0 8px;overflow:hidden;border-radius:99px;background:#f1f0ea}.score-bar i{display:block;height:100%;border-radius:99px;background:linear-gradient(90deg,#ffbd00,#f5a900)}.score-bar.unknown{background:repeating-linear-gradient(135deg,#efeee9,#efeee9 5px,#e3e1db 5px,#e3e1db 10px)}.level-detail{text-align:right;color:#9c7417;font-size:11px}.fish-labels{margin-top:12px;padding-top:12px;border-top:1px solid #efede7}.fish-labels>div{display:flex;align-items:center;justify-content:space-between}.fish-labels strong{font-size:13px}.fish-labels small{color:#aaa69d;font-size:9px}.spark{color:#ff4a84}.fish-labels p{display:flex;flex-wrap:wrap;gap:9px;margin:10px 0 0}.fish-labels p span{padding:5px 10px;border-radius:99px;color:#a04f61;background:#fff1f5;font-size:11px;font-weight:650}.muted-copy{color:#98958e!important;font-size:11px!important}.metric-grid{display:grid;grid-template-columns:repeat(4,1fr);margin-top:14px}.metric-grid span{display:grid;gap:5px;text-align:center}.metric-grid small{color:#8f8b83;font-size:11px}.metric-grid strong{font-size:18px}.green-text{color:#12a04d!important}.portrait-card dl,.business-card dl{display:grid;gap:0;margin:12px 0 0}.portrait-card dl div,.business-card dl div{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:6px 0}.portrait-card dt,.business-card dt{color:#807d76;font-size:13px}.portrait-card dd,.business-card dd{margin:0;color:#df7311;font-size:13px;font-weight:750}.business-card dd{color:#252522}.risk-summary{overflow:hidden}.risk-summary>header{padding:15px 18px;border-bottom:1px solid #eee9df;background:linear-gradient(90deg,#fffdf8,#fff4ef)}.risk-summary>header>div{display:flex;align-items:center;gap:10px}.risk-summary>header small{color:#928f88;font-size:11px}.risk-summary button,.risk-list-header button{border:0;color:#77736b;background:transparent;font-size:12px}.shield{color:#ef7027}.risk-summary>.risk-metrics{display:grid;grid-template-columns:repeat(3,1fr)}.risk-summary>.risk-metrics span{display:grid;gap:6px;padding:16px;border-right:1px solid #f0eee9;text-align:center}.risk-summary>.risk-metrics span:last-child{border:0}.risk-summary>.risk-metrics small{color:#96928a;font-size:11px}.risk-summary>.risk-metrics strong{font-size:28px}.orange-text{color:#ef5f0c!important}.red-text{color:#df1212!important}.sync-error{display:grid;gap:4px;margin:0;padding:11px 16px;border-top:1px solid #ffe0d9;color:#a43b2f;background:#fff4f2;font-size:12px;line-height:1.5}.sync-error strong{font-size:13px}.sync-error small{color:#87534b;font-size:11px}.risk-list-card{min-height:420px;padding:18px}.risk-list-header h3{display:inline;margin:0;font-size:16px}.risk-list-header span{margin-left:7px;padding:3px 7px;border-radius:99px;color:#777;background:#f1f1ee;font-size:10px}.risk-list-header>div:last-child{display:flex;gap:6px}.risk-tabs{display:flex;gap:8px;margin:14px 0}.risk-tabs button{min-height:32px;padding:0 16px;border:0;border-radius:99px;color:#65625b;background:#f0f1f1;font-size:12px}.risk-tabs button.active{color:#fff;background:#b78a00}.risk-items{display:grid;gap:12px}.risk-items article{padding:13px 14px;border:1px solid #eee7d8;border-top:3px solid #efbe00;border-radius:11px;background:#fff}.risk-items article.severity-critical,.risk-items article.severity-high{border-top-color:#e34b3e}.risk-items article>header{display:flex;align-items:center;justify-content:space-between;gap:10px}.risk-items article>header>div{display:flex;align-items:center;gap:6px;min-width:0}.risk-items article>header strong{overflow:hidden;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.warning-tag,.active-tag{padding:4px 7px;border-radius:4px;font-size:10px;white-space:nowrap}.warning-tag{color:#a86b00;background:#fff3c8}.active-tag{color:#ca3154;background:#ffeef2}.risk-items code{overflow:hidden;max-width:160px;color:#b9b5ae;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.risk-items article>p{margin:8px 0;color:#66625b;font-size:11px}.risk-items details{font-size:11px}.risk-items summary{color:#8c6200;cursor:pointer}.risk-items details p{margin:7px 0;color:#69665f}.risk-items footer{display:flex;justify-content:space-between;gap:10px;margin-top:10px;padding-top:8px;border-top:1px solid #f0eee8;color:#aaa69e;font-size:10px}.risk-items footer b{color:#cb3945}.risk-empty{display:grid;min-height:300px;place-content:center;text-align:center}.risk-empty strong{font-size:16px}.risk-empty p{max-width:420px;margin:8px 0 0;color:#89867f;font-size:12px}.profile-footer{display:flex;align-items:center;justify-content:space-between;gap:20px;min-height:56px;padding:8px 24px;border-top:1px solid #e9e8e3;background:#fff}.profile-footer>div{display:flex;align-items:center;gap:9px;color:#9a9790;font-size:11px}.profile-footer kbd{padding:3px 6px;border:1px solid #deddd8;border-radius:4px;background:#f7f7f5;font-size:9px}.footer-button--primary{min-width:150px}.profile-footer>div:first-child .online{color:#9a9790!important}
.identity-tag--neutral{color:#77736b;background:#f0efeb}.unverified{color:#9b7b28!important}
@media(max-width:900px){.profile-backdrop{padding:0}.profile-dialog{width:100vw;height:100dvh;min-height:0;border:0;border-radius:0}.profile-header{align-items:flex-start;padding:11px 12px}.shop-avatar{width:44px;height:44px;flex-basis:44px}.shop-title-row h2{font-size:16px}.shop-title-row .identity-tag--gold{display:none}.shop-identity p span:nth-of-type(n+2),.shop-identity p i{display:none}.session-state,.profile-actions .outline-button:first-of-type{display:none}.profile-body{grid-template-columns:1fr}.profile-left{border-right:0}.profile-right{padding-top:0}.profile-footer>div:first-child{display:none}.profile-footer>div:last-child{width:100%}.footer-button{flex:1;min-height:40px}}
@media(max-width:520px){.profile-dialog{background:#f6f6f3}.profile-header{position:sticky;top:0;z-index:4}.profile-actions .outline-button{display:none}.profile-left,.profile-right{padding:10px}.profile-left{padding-bottom:8px}.metric-grid{grid-template-columns:repeat(2,1fr);gap:12px}.risk-summary>header{align-items:flex-start}.risk-summary>header>div{align-items:flex-end;flex-direction:column;gap:2px}.risk-list-header>div:last-child button:not(:first-child){display:none}.risk-items article>header{align-items:flex-start}.risk-items article>header>div{flex-wrap:wrap}.risk-items code{display:none}.risk-items footer{flex-direction:column}.profile-footer{position:sticky;bottom:0;z-index:4}}
</style>
