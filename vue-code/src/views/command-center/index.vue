<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  getAccountCapabilities, getCommandCenterAccounts, getOperationalIssues,
  getConversationAssignments, probeAccountCapabilities, transitionOperationalIssue,
  updateConversationAssignment,
  type AccountCapability, type AccountHealth, type OperationalIssue, type ConversationAssignment
} from '@/api/command-center'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'

const tab = ref<'accounts' | 'issues' | 'conversations' | 'capabilities'>('accounts')
const loading = ref(false)
const loaded = ref(false)
const loadError = ref('')
const accounts = ref<AccountHealth[]>([])
const issues = ref<OperationalIssue[]>([])
const capabilities = ref<AccountCapability[]>([])
const conversations = ref<ConversationAssignment[]>([])
const severity = ref('')
const acting = ref<number>()

const summary = computed(() => ({
  critical: accounts.value.filter(item => item.attentionLevel === 'CRITICAL').length,
  warning: accounts.value.filter(item => item.attentionLevel === 'WARNING').length,
  healthy: accounts.value.filter(item => item.attentionLevel === 'HEALTHY').length,
  issues: issues.value.length
}))

const capabilityGroups = computed(() => {
  const groups = new Map<number, { name: string; items: AccountCapability[] }>()
  for (const item of capabilities.value) {
    const group = groups.get(item.accountId) || { name: item.accountNote || `账号 ${item.accountId}`, items: [] }
    group.items.push(item)
    groups.set(item.accountId, group)
  }
  return [...groups.entries()].map(([accountId, value]) => ({ accountId, ...value }))
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [accountResult, issueResult] = await Promise.all([
      getCommandCenterAccounts(), getOperationalIssues({ severity: severity.value || undefined })
    ])
    accounts.value = accountResult.data || []
    issues.value = issueResult.data || []
    if (tab.value === 'capabilities') capabilities.value = (await getAccountCapabilities()).data || []
    if (tab.value === 'conversations') conversations.value = (await getConversationAssignments()).data || []
  } catch {
    loadError.value = '运营任务暂时无法读取；未知数量不会按 0 展示。'
  } finally {
    loaded.value = true
    loading.value = false
  }
}

function changeTab(value: typeof tab.value) {
  tab.value = value
  if (value === 'capabilities' && !capabilities.value.length) {
    loading.value = true
    void getAccountCapabilities()
      .then(result => { capabilities.value = result.data || [] })
      .catch(() => { loadError.value = '账号能力暂时无法读取；未知数量不会按 0 展示。' })
      .finally(() => { loading.value = false })
  }
  if (value === 'conversations' && !conversations.value.length) {
    loading.value = true
    void getConversationAssignments()
      .then(result => { conversations.value = result.data || [] })
      .catch(() => { loadError.value = '客服队列暂时无法读取；未知数量不会按 0 展示。' })
      .finally(() => { loading.value = false })
  }
}

const commandTabs: Array<typeof tab.value> = ['accounts', 'issues', 'conversations', 'capabilities']
async function moveTabFocus(event: KeyboardEvent, current: typeof tab.value) {
  const direction = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  const edgeIndex = event.key === 'Home' ? 0 : event.key === 'End' ? commandTabs.length - 1 : -1
  if (!direction && edgeIndex < 0) return
  event.preventDefault()
  const nextIndex = edgeIndex >= 0 ? edgeIndex : (commandTabs.indexOf(current) + direction + commandTabs.length) % commandTabs.length
  const nextTab = commandTabs[nextIndex]!
  changeTab(nextTab)
  await nextTick()
  document.getElementById(`command-tab-${nextTab}`)?.focus()
}

const summaryValue = (value: number) => loaded.value && !loadError.value ? value.toLocaleString('zh-CN') : '—'

async function refreshConversations() {
  loading.value = true
  try { conversations.value = (await getConversationAssignments()).data || [] }
  finally { loading.value = false }
}

async function transition(issue: OperationalIssue, status: string) {
  let note: string | undefined
  if (status === 'RESOLVED' || status === 'IGNORED') {
    try {
      await showConfirm(`确认将「${issue.title}」标记为${status === 'RESOLVED' ? '已解决' : '已忽略'}？`, '异常处理确认')
      note = status === 'RESOLVED' ? '已人工核对并完成处理' : '已确认无需处理'
    } catch { return }
  }
  acting.value = issue.id
  try {
    await transitionOperationalIssue(issue.id, status, note)
    toast.success('任务状态已更新')
    await load()
  } finally { acting.value = undefined }
}

async function probe() {
  loading.value = true
  try {
    await probeAccountCapabilities()
    capabilities.value = (await getAccountCapabilities()).data || []
    toast.success('账号能力已重新探测')
  } finally { loading.value = false }
}

async function updateConversation(item: ConversationAssignment, data: { status?: string; priority?: string; claim?: boolean }) {
  acting.value = item.id
  try {
    await updateConversationAssignment(item.id, data)
    conversations.value = (await getConversationAssignments()).data || []
    await load()
    toast.success('客服队列已更新')
  } finally { acting.value = undefined }
}

function labelStatus(status: string) {
  return ({ READY: '可用', DEGRADED: '异常', REQUIRES_PLATFORM_PERMISSION: '需要平台资格', NOT_IMPLEMENTED: '待接入', UNKNOWN: '待确认' } as Record<string, string>)[status] || status
}

function fmt(value?: string) { return value ? value.replace('T', ' ').slice(0, 19) : '-' }
onMounted(load)
</script>

<template>
  <main class="command-page" :aria-busy="loading">
    <header class="hero panel">
      <div><span class="eyebrow">MULTI-ACCOUNT COMMAND CENTER</span><h1>运营驾驶舱</h1><p>按异常优先级管理账号、履约、回复、风控和平台能力。</p></div>
      <button :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新全部' }}</button>
    </header>

    <div v-if="loadError" class="load-error panel" role="alert"><span>{{ loadError }}</span><button @click="load">重试</button></div>

    <section class="summary-grid" aria-label="运营任务摘要">
      <article class="panel critical"><span>紧急账号</span><strong>{{ summaryValue(summary.critical) }}</strong></article>
      <article class="panel warning"><span>提醒账号</span><strong>{{ summaryValue(summary.warning) }}</strong></article>
      <article class="panel healthy"><span>运行正常</span><strong>{{ summaryValue(summary.healthy) }}</strong></article>
      <article class="panel"><span>待处理任务</span><strong>{{ summaryValue(summary.issues) }}</strong></article>
    </section>

    <nav class="tabs" role="tablist" aria-label="运营驾驶舱视图">
      <button id="command-tab-accounts" role="tab" aria-controls="command-panel-accounts" :aria-selected="tab === 'accounts'" :tabindex="tab === 'accounts' ? 0 : -1" :class="{ active: tab === 'accounts' }" @keydown="moveTabFocus($event, 'accounts')" @click="changeTab('accounts')">账号总览</button>
      <button id="command-tab-issues" role="tab" aria-controls="command-panel-issues" :aria-selected="tab === 'issues'" :tabindex="tab === 'issues' ? 0 : -1" :class="{ active: tab === 'issues' }" @keydown="moveTabFocus($event, 'issues')" @click="changeTab('issues')">异常任务</button>
      <button id="command-tab-conversations" role="tab" aria-controls="command-panel-conversations" :aria-selected="tab === 'conversations'" :tabindex="tab === 'conversations' ? 0 : -1" :class="{ active: tab === 'conversations' }" @keydown="moveTabFocus($event, 'conversations')" @click="changeTab('conversations')">客服队列</button>
      <button id="command-tab-capabilities" role="tab" aria-controls="command-panel-capabilities" :aria-selected="tab === 'capabilities'" :tabindex="tab === 'capabilities' ? 0 : -1" :class="{ active: tab === 'capabilities' }" @keydown="moveTabFocus($event, 'capabilities')" @click="changeTab('capabilities')">能力矩阵</button>
    </nav>

    <section v-if="tab === 'accounts'" id="command-panel-accounts" class="account-grid" role="tabpanel" aria-labelledby="command-tab-accounts">
      <article v-for="account in accounts" :key="account.accountId" class="panel account" :class="account.attentionLevel.toLowerCase()">
        <header><div><strong>{{ account.accountNote || account.unb || `账号 ${account.accountId}` }}</strong><small>ID {{ account.accountId }} · {{ account.unb }}</small></div><span class="state">{{ account.attentionLevel === 'HEALTHY' ? '正常' : account.attentionLevel === 'CRITICAL' ? '紧急' : '提醒' }}</span></header>
        <div class="signals"><span :class="{ bad: !account.websocketConnected }">消息连接 {{ account.websocketConnected ? '在线' : '断开' }}</span><span :class="{ bad: account.cookieStatus !== 1 }">凭证 {{ account.cookieStatus === 1 ? '有效' : '异常' }}</span><span :class="{ bad: account.riskState !== 'NORMAL' }">风控 {{ account.riskState }}</span></div>
        <dl><div><dt>今日订单</dt><dd>{{ account.todayOrderCount }}</dd></div><div><dt>24h 消息</dt><dd>{{ account.message24hCount }}</dd></div><div><dt>待发货</dt><dd>{{ account.pendingDeliveryCount }}</dd></div><div><dt>发货异常</dt><dd>{{ account.failedDeliveryCount }}</dd></div><div><dt>回复异常</dt><dd>{{ account.failedReplyCount }}</dd></div><div><dt>待办</dt><dd>{{ account.openIssueCount }}</dd></div></dl>
        <footer><span>最后消息 {{ fmt(account.lastMessageTime) }}</span><span>最后订单 {{ fmt(account.lastOrderTime) }}</span></footer>
      </article>
      <div v-if="!loading && !accounts.length" class="panel empty">当前账号范围内暂无闲鱼账号。</div>
    </section>

    <section v-else-if="tab === 'issues'" id="command-panel-issues" class="panel issue-panel" role="tabpanel" aria-labelledby="command-tab-issues">
      <header class="toolbar"><strong>状态化异常任务</strong><select v-model="severity" @change="load"><option value="">全部严重度</option><option value="CRITICAL">紧急</option><option value="WARNING">提醒</option><option value="INFO">信息</option></select></header>
      <article v-for="issue in issues" :key="issue.id" class="issue">
        <span class="severity" :class="issue.severity.toLowerCase()">{{ issue.severity }}</span>
        <div class="issue-main"><strong>{{ issue.title }}</strong><p>{{ issue.description }}</p><small>{{ issue.accountNote || `账号 ${issue.accountId || '-'}` }} · 出现 {{ issue.occurrenceCount }} 次 · {{ fmt(issue.lastOccurredTime) }}<template v-if="issue.assignedUsername"> · 负责人 {{ issue.assignedUsername }}</template></small></div>
        <div class="issue-actions"><button v-if="issue.status === 'OPEN'" :disabled="acting === issue.id" @click="transition(issue, 'CLAIMED')">认领</button><button v-if="issue.status === 'CLAIMED'" :disabled="acting === issue.id" @click="transition(issue, 'IN_PROGRESS')">开始处理</button><button class="primary" :disabled="acting === issue.id" @click="transition(issue, 'RESOLVED')">解决</button><button :disabled="acting === issue.id" @click="transition(issue, 'IGNORED')">忽略</button></div>
      </article>
      <div v-if="!loading && !issues.length" class="empty">暂无待处理异常。</div>
    </section>

    <section v-else-if="tab === 'conversations'" id="command-panel-conversations" class="panel issue-panel" role="tabpanel" aria-labelledby="command-tab-conversations">
      <header class="toolbar"><div><strong>跨店客服队列</strong><p>首次响应目标 5 分钟，超时会自动生成运营异常。</p></div><button :disabled="loading" @click="refreshConversations">刷新</button></header>
      <article v-for="item in conversations" :key="item.id" class="issue">
        <span class="severity" :class="item.slaBreached ? 'critical' : item.priority.toLowerCase()">{{ item.slaBreached ? 'SLA 超时' : item.priority }}</span>
        <div class="issue-main"><strong>{{ item.accountNote || `账号 ${item.accountId}` }} · 会话 {{ item.sessionId }}</strong><p>买家 {{ item.buyerUserId || '-' }}<template v-if="item.assignedUsername"> · 负责人 {{ item.assignedUsername }}</template></p><small>首次咨询 {{ fmt(item.firstMessageTime) }} · 首次回复 {{ fmt(item.firstResponseTime) }} · 截止 {{ fmt(item.slaDueTime) }}</small></div>
        <div class="issue-actions"><button v-if="!item.assignedUsername" :disabled="acting === item.id" @click="updateConversation(item, { claim: true, status: 'IN_PROGRESS' })">认领</button><select :value="item.priority" @change="updateConversation(item, { priority: ($event.target as HTMLSelectElement).value })"><option value="LOW">低</option><option value="NORMAL">普通</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select><button class="primary" :disabled="acting === item.id" @click="updateConversation(item, { status: 'CLOSED' })">关闭</button></div>
      </article>
      <div v-if="!loading && !conversations.length" class="empty">近 7 天暂无待处理客服会话。</div>
    </section>

    <section v-else id="command-panel-capabilities" class="capability-section" role="tabpanel" aria-labelledby="command-tab-capabilities">
      <div class="cap-head"><p>“需要平台资格”或“待接入”的功能不会显示为执行成功。</p><button class="primary" :disabled="loading" @click="probe">重新探测</button></div>
      <article v-for="group in capabilityGroups" :key="group.accountId" class="panel capability-group"><header><strong>{{ group.name }}</strong><small>ID {{ group.accountId }}</small></header><div class="cap-grid"><div v-for="item in group.items" :key="item.capabilityCode"><span>{{ item.capabilityName }}</span><b :class="item.status.toLowerCase()">{{ labelStatus(item.status) }}</b><small>{{ item.detail }}</small></div></div></article>
    </section>
  </main>
</template>

<style scoped>
.command-page{height:100%;overflow:auto;padding:18px;box-sizing:border-box;background:#f7f8fa;color:#101828}.panel{background:#fff;border:1px solid #e4e7ec;border-radius:10px}.hero{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;padding:20px}.eyebrow{color:#9a6200;font-size:11px;font-weight:700;letter-spacing:.08em}h1,p{margin:0}.hero h1{margin-top:3px;font-size:22px}.hero p,.cap-head p{margin-top:5px;color:#667085;font-size:13px}button,select{padding:8px 12px;border:1px solid #d0d5dd;border-radius:6px;background:#fff;color:#344054;font:inherit;cursor:pointer}.primary{border-color:#9a6200;background:#9a6200;color:#fff}.load-error{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:12px;padding:12px 15px;color:#991b1b;background:#fff1f2}.summary-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:12px 0}.summary-grid article{padding:15px}.summary-grid span{color:#667085;font-size:12px}.summary-grid strong{display:block;margin-top:4px;font-size:25px}.summary-grid .critical strong{color:#b42318}.summary-grid .warning strong{color:#b54708}.summary-grid .healthy strong{color:#067647}.tabs{display:flex;gap:4px;margin:14px 0;border-bottom:1px solid #d0d5dd}.tabs button{border:0;border-radius:6px 6px 0 0;background:transparent}.tabs button.active{color:#9a6200;background:#fff8d9;font-weight:600}.tabs button:focus-visible{outline:3px solid rgba(247,193,33,.42);outline-offset:-2px}.account-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:12px}.account{padding:16px;border-left:4px solid #12b76a}.account.warning{border-left-color:#f79009}.account.critical{border-left-color:#f04438}.account header,.account footer,.toolbar,.cap-head,.capability-group>header{display:flex;justify-content:space-between;gap:12px}.account header small,.account footer,.capability-group header small{display:block;color:#98a2b3;font-size:11px}.state{padding:3px 8px;border-radius:999px;background:#f2f4f7;font-size:12px}.signals{display:flex;flex-wrap:wrap;gap:6px;margin:14px 0}.signals span{padding:4px 7px;border-radius:5px;color:#067647;background:#ecfdf3;font-size:11px}.signals span.bad{color:#b42318;background:#fef3f2}.account dl{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:0}.account dl div{padding:9px;border-radius:6px;background:#f9fafb}.account dt{color:#667085;font-size:11px}.account dd{margin:3px 0 0;font-weight:700}.account footer{margin-top:13px;flex-wrap:wrap}.issue-panel{overflow:hidden}.toolbar{align-items:center;padding:13px 15px;border-bottom:1px solid #eaecf0}.issue{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:13px;padding:14px 15px;border-bottom:1px solid #f2f4f7}.severity{padding:4px 7px;border-radius:5px;background:#f2f4f7;font-size:10px}.severity.critical{color:#b42318;background:#fef3f2}.severity.warning{color:#b54708;background:#fffaeb}.issue-main p{margin-top:4px;color:#667085;font-size:13px}.issue-main small{display:block;margin-top:6px;color:#98a2b3}.issue-actions{display:flex;gap:5px}.cap-head{align-items:center;margin-bottom:10px}.capability-group{margin-bottom:10px;overflow:hidden}.capability-group>header{padding:13px 15px;border-bottom:1px solid #eaecf0}.cap-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:1px;background:#eaecf0}.cap-grid>div{display:grid;gap:5px;padding:12px;background:#fff}.cap-grid b{width:max-content;padding:2px 6px;border-radius:4px;color:#475467;background:#f2f4f7;font-size:11px}.cap-grid b.ready{color:#067647;background:#ecfdf3}.cap-grid b.degraded,.cap-grid b.not_implemented{color:#b42318;background:#fef3f2}.cap-grid b.requires_platform_permission{color:#b54708;background:#fffaeb}.cap-grid small{color:#98a2b3}.empty{padding:70px 20px;text-align:center;color:#98a2b3}@media(max-width:800px){.summary-grid{grid-template-columns:repeat(2,1fr)}.hero,.issue{align-items:stretch;grid-template-columns:1fr;flex-direction:column}.issue-actions{flex-wrap:wrap}.account-grid{grid-template-columns:1fr}}@media(max-width:480px){.command-page{padding:10px}.summary-grid{grid-template-columns:1fr 1fr}.account dl{grid-template-columns:repeat(2,1fr)}}
</style>
