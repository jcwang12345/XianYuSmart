<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getPdcaItems, getPdcaOverview, type PdcaItems, type PdcaOverview, type PdcaQuery, type PdcaRole, type PdcaType } from '@/api/pdca'
import { hasPermission, isTenantManager, loadCurrentUser, permissionState } from '@/utils/permission'

const overview = ref<PdcaOverview>()
const items = ref<PdcaItems>()
const busy = ref(false)
const error = ref('')
const oldData = ref(false)
const announcement = ref('')
const type = ref<PdcaType>('requirement')
const owner = ref<PdcaRole | ''>('')
const batch = ref('')
const state = ref('')
const severity = ref('')
const keyword = ref('')
const appliedKeyword = ref('')
const page = ref(1)
const canRead = computed(() => !!permissionState.value && isTenantManager.value && hasPermission('menu:settings'))
const identity = computed(() => `${permissionState.value?.username ?? ''}:${permissionState.value?.tenantId ?? ''}:${canRead.value}`)
const labels: Record<string, string> = {
  UNKNOWN: '未核实', NOT_IMPLEMENTED: '已确认未实现', IMPLEMENTED_PENDING_CHECK: '已开发，待验收',
  HISTORICAL_PENDING_REGRESSION: '历史证据，待当前回归', DEGRADED_PLATFORM: '平台能力降级', CANCELLED_BY_USER: '用户取消',
  WAITING_FOR_USER: '等待用户', ACCEPTED: '当前候选已验收', NOT_CHECKED: '未验收', CHECKING: '验收中', FAILED: '验收失败',
  PASSED_ISOLATED: '隔离验收通过', PASSED_REAL: '真实验收通过', WAITING_FOR_EVIDENCE: '等待证据',
  OPEN: '未关闭', FIXED_PENDING_CHECK: '已修复，待验收', CLOSED_HISTORICAL: '历史已关闭', CLOSED_CURRENT: '当前候选已关闭', WAITING: '等待',
  IN_DEVELOPMENT: '开发中', NOT_RELEASED: '未发布', REQUIREMENT: '需求', ACCEPTANCE_CHECK: '验收检查', GATE: '门禁', DEFECT: '缺陷',
  HISTORICAL: '历史证据', D_DEV: '开发自测', C_ACCEPTANCE: '独立验收', A_RELEASE: '发布证据'
}
const label = (value?: string) => value ? labels[value] ?? value : '未登记'
const states = computed(() => type.value === 'bug'
  ? ['OPEN', 'FIXED_PENDING_CHECK', 'CLOSED_HISTORICAL', 'CLOSED_CURRENT', 'WAITING']
  : ['UNKNOWN', 'NOT_IMPLEMENTED', 'IMPLEMENTED_PENDING_CHECK', 'HISTORICAL_PENDING_REGRESSION', 'DEGRADED_PLATFORM', 'CANCELLED_BY_USER', 'WAITING_FOR_USER', 'ACCEPTED'])
const sourceTitles = computed(() => new Map(overview.value?.sourceTitles?.map(s => [s.sourceId, s.safeTitle]) ?? []))
const sourceTitle = (id: string) => sourceTitles.value.get(id) ?? id
const time = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未登记'
const statistics = computed(() => {
  const c = overview.value?.counts
  if (!c) return []
  return [
    { title: '需求与检查登记项', value: c.registeredItems }, { title: '未核实', value: c.implementation.UNKNOWN ?? 0 },
    { title: '已开发待验收', value: c.implementation.IMPLEMENTED_PENDING_CHECK ?? 0 },
    { title: '历史待回归', value: c.implementation.HISTORICAL_PENDING_REGRESSION ?? 0 },
    { title: '平台降级', value: c.implementation.DEGRADED_PLATFORM ?? 0 }, { title: '用户取消', value: c.implementation.CANCELLED_BY_USER ?? 0 },
    { title: '未关闭缺陷', value: c.bugStatus.OPEN ?? 0 }, { title: '修复待验收', value: c.bugStatus.FIXED_PENDING_CHECK ?? 0 },
    { title: '缺陷等待', value: c.bugStatus.WAITING ?? 0 }, { title: '历史关闭缺陷', value: c.bugStatus.CLOSED_HISTORICAL ?? 0 },
    { title: '当前关闭缺陷', value: c.bugStatus.CLOSED_CURRENT ?? 0 }, { title: '缺陷登记总数', value: c.registeredBugs }
  ]
})
let generation = 0
let abort: AbortController | undefined
let disposed = false
function invalidate(clear = false) {
  generation++
  abort?.abort()
  abort = undefined
  busy.value = false
  if (clear) { overview.value = undefined; items.value = undefined; oldData.value = false; error.value = ''; announcement.value = '' }
}
function failureCode(cause: unknown): number | undefined {
  if (!cause || typeof cause !== 'object') return undefined
  const e = cause as { businessCode?: number; response?: { status?: number; data?: { code?: number } } }
  return e.businessCode ?? e.response?.data?.code ?? e.response?.status
}
async function load(refreshIdentity = false) {
  invalidate()
  if (!canRead.value || disposed) return
  const mine = generation, subject = identity.value
  const active = () => !disposed && mine === generation && subject === identity.value && canRead.value
  abort = new AbortController()
  const signal = abort.signal
  busy.value = true
  error.value = ''
  announcement.value = ''
  try {
    if (refreshIdentity) { await loadCurrentUser(true); if (!active()) return }
    const query: PdcaQuery = { type: type.value, page: page.value, pageSize: 20,
      ...(owner.value ? { ownerRole: owner.value } : {}), ...(batch.value ? { batch: batch.value } : {}),
      ...(state.value ? { state: state.value } : {}), ...(severity.value ? { severity: severity.value } : {}),
      ...(appliedKeyword.value ? { keyword: appliedKeyword.value } : {}) }
    let selectedOverview: PdcaOverview | undefined, selectedItems: PdcaItems | undefined
    for (let attempt = 0; attempt < 2; attempt++) {
      const a = await getPdcaOverview(overview.value?.revision ?? undefined, signal)
      if (!active()) return
      if (!a.data) throw new Error('Missing snapshot')
      if (overview.value?.revision && overview.value.revision !== a.data.revision) query.page = 1
      const b = await getPdcaItems({ ...query, knownRevision: a.data.revision ?? undefined }, signal)
      if (!active()) return
      if (!b.data) throw new Error('Missing records')
      if (a.data.revision === b.data.revision && a.data.snapshotAvailable === b.data.snapshotAvailable) {
        selectedOverview = a.data; selectedItems = b.data; break
      }
      query.page = 1
    }
    if (!selectedOverview || !selectedItems) throw new Error('Snapshot changed')
    overview.value = selectedOverview
    items.value = selectedItems
    page.value = selectedItems.page
    oldData.value = false
    announcement.value = selectedOverview.snapshotAvailable ? '进度已更新' : '当前租户尚未初始化进度来源'
  } catch (cause) {
    if (!active()) return
    const code = failureCode(cause)
    if (code === 401 || code === 403) {
      overview.value = undefined; items.value = undefined; oldData.value = false
      error.value = code === 401 ? '登录已失效，请重新登录。' : '当前身份无权读取进度，已清除缓存。'
    } else {
      oldData.value = !!overview.value?.snapshotAvailable
      if (items.value) page.value = items.value.page
      error.value = oldData.value ? '更新失败，以下显示上次成功读取的数据。请稍后重试。' : '进度读取失败，请稍后重试。'
    }
  } finally { if (mine === generation) { busy.value = false; abort = undefined } }
}
function filter() { page.value = 1; items.value = undefined; void load() }
function applyKeyword() { appliedKeyword.value = keyword.value.trim(); filter() }
function changeType() { state.value = ''; filter() }
function clearFilters() { owner.value = ''; batch.value = ''; state.value = ''; severity.value = ''; keyword.value = ''; appliedKeyword.value = ''; filter() }
function turn(delta: number) { page.value += delta; void load() }
watch(identity, () => { invalidate(true); page.value = 1; if (canRead.value) void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; invalidate(true) })
</script>

<template>
  <main class="pdca" :aria-busy="busy">
    <header class="pdca__header">
      <div><p class="pdca__eyebrow">计划 · 开发 · 验收 · 发布</p><h1>PDCA进度</h1><p class="pdca__muted">按来源逐项核对，开发自测与独立验收分别记录。</p></div>
      <button class="pdca__button pdca__button--primary" :disabled="busy || !canRead" @click="load(true)">{{ busy ? '正在更新…' : '刷新进度' }}</button>
    </header>
    <p class="pdca__sr" role="status" aria-live="polite">{{ announcement }}</p>
    <p v-if="error" class="pdca__notice pdca__notice--error" role="alert">{{ error }}</p>
    <p v-if="!canRead" class="pdca__notice">仅当前租户管理者且具有系统设置权限的用户可查看进度。</p>
    <p v-else-if="busy && !overview" class="pdca__notice" role="status">正在读取已发布的进度来源…</p>
    <p v-else-if="overview && !overview.snapshotAvailable" class="pdca__notice">{{ overview.reason === 'NOT_CONFIGURED' ? '进度来源尚未配置。' : '当前租户尚未发布进度清单。' }}初始化后可刷新查看。</p>
    <template v-if="canRead && overview?.snapshotAvailable">
      <section class="pdca__panel" aria-label="来源与更新时间" :class="{ 'pdca__panel--old': oldData }">
        <p v-if="overview.stale" class="pdca__notice">来源超过30分钟未采集，可能已有新变化。以下保留已核查的旧快照。</p>
        <p><strong>{{ oldData ? '上次成功快照' : '来源快照' }} · {{ overview.sourceRevision }}</strong> · revision {{ overview.revision }}</p>
        <dl class="pdca__metadata"><div><dt>最后采集</dt><dd>{{ time(overview.sourceObservedAt) }}</dd></div><div><dt>最后成功发布</dt><dd>{{ time(overview.lastSuccessfulPublishAt) }}</dd></div><div><dt>来源记录更新</dt><dd>{{ time(overview.updatedAt) }}</dd></div></dl>
        <p class="pdca__muted">{{ overview.coverageDefinition }}。{{ overview.coverageComplete ? '来源已声明覆盖完整，当前页面仍按分类计数。' : '覆盖尚未完整核实，无独立功能分母，不计算完成率。' }}</p>
      </section>
      <section aria-labelledby="pdca-counts"><h2 id="pdca-counts">当前登记</h2><div class="pdca__stats"><div v-for="s in statistics" :key="s.title" class="pdca__stat"><strong>{{ s.value }}</strong><span>{{ s.title }}</span></div></div><p class="pdca__muted">数量包含不同版本来源。历史关闭、平台降级、取消及等待不代表当前候选验收通过。</p></section>
      <section aria-labelledby="pdca-roles"><h2 id="pdca-roles">四角色当前任务</h2><div class="pdca__roles"><article v-for="role in overview.roles" :key="role.role" class="pdca__panel"><h3>{{ role.role }} · {{ role.responsibility }}</h3><p>{{ role.currentBatch }} · {{ label(role.state) }}</p><p><strong>下一步：</strong>{{ role.nextStep || '尚未登记' }}</p><ul v-if="role.blockers.length"><li v-for="b in role.blockers" :key="b">{{ b }}</li></ul><p class="pdca__muted">{{ sourceTitle(role.sourceRef) }} · {{ time(role.updatedAt) }}</p></article></div></section>
      <section aria-labelledby="pdca-records"><h2 id="pdca-records">逐项进度</h2>
        <form class="pdca__filters pdca__panel" @submit.prevent="applyKeyword">
          <label>记录类型<select v-model="type" @change="changeType"><option value="requirement">需求与验收检查</option><option value="bug">缺陷</option></select></label>
          <label>负责人<select v-model="owner" @change="filter"><option value="">全部</option><option v-for="r in ['P', 'D2', 'C', 'A', 'UNKNOWN']" :key="r" :value="r">{{ label(r) }}</option></select></label>
          <label>批次<select v-model="batch" @change="filter"><option value="">全部</option><option v-for="b in overview.filterOptions?.batches" :key="b" :value="b">{{ b }}</option></select></label>
          <label>状态<select v-model="state" @change="filter"><option value="">全部</option><option v-for="s in states" :key="s" :value="s">{{ label(s) }}</option></select></label>
          <label>严重度<select v-model="severity" @change="filter"><option value="">全部</option><option v-for="s in ['P0', 'P1', 'P2', 'P3', 'UNKNOWN']" :key="s" :value="s">{{ label(s) }}</option></select></label>
          <label class="pdca__search">编号或关键词<input v-model="keyword" maxlength="100" type="search" placeholder="搜索原编号、标题或下一步" /></label>
          <div class="pdca__filter-actions"><button class="pdca__button" type="submit" :disabled="busy">搜索</button><button class="pdca__button" type="button" :disabled="busy" @click="clearFilters">清除筛选</button></div>
        </form>
        <p role="status" class="pdca__muted">{{ busy ? '正在读取…' : `共 ${items?.total ?? 0} 条筛选记录` }}</p>
        <p v-if="!busy && items && !items.records.length" class="pdca__notice">{{ items.reason === 'EMPTY_VERIFIED' ? '已核查的来源清单暂无登记记录。' : '没有符合筛选条件的记录，可清除筛选后查看。' }}</p>
        <div class="pdca__records"><article v-for="row in items?.records" :key="row.key" class="pdca__panel">
          <div class="pdca__record-head"><h3>{{ row.originalId }} · {{ row.title }}</h3><span class="pdca__badge">{{ label(row.status ?? row.implementationState) }}</span></div>
          <p class="pdca__muted">{{ label(row.recordKind) }} · {{ sourceTitle(row.sourceRef) }} · {{ row.batch }}</p>
          <dl class="pdca__metadata"><div><dt>负责人</dt><dd>{{ label(row.ownerRole) }}</dd></div><div><dt>开发</dt><dd>{{ label(row.devState) }}</dd></div><div><dt>C验收</dt><dd>{{ label(row.checkState) }}</dd></div><div><dt>{{ row.recordKind === 'DEFECT' ? '严重度' : '发布' }}</dt><dd>{{ label(row.recordKind === 'DEFECT' ? row.severity : row.releaseState) }}</dd></div></dl>
          <p v-if="row.blockerSummary"><strong>阻塞：</strong>{{ row.blockerSummary }}</p><p><strong>下一步：</strong>{{ row.nextStep || '尚未登记' }}</p>
          <details v-if="row.evidence.length"><summary>查看 {{ row.evidence.length }} 份安全证据摘要</summary><div v-for="e in row.evidence" :key="e.evidenceId" class="pdca__evidence"><h4>{{ e.safeTitle }} · {{ label(e.kind) }}</h4><p>{{ e.safeSummary }}</p><p v-if="e.candidateVersion">候选版本：{{ e.candidateVersion }}</p><p v-if="e.candidateSha">候选SHA：{{ e.candidateSha }}</p><p class="pdca__muted">{{ sourceTitle(e.sourceRef) }} · {{ time(e.observedAt) }}</p></div></details>
          <p v-else class="pdca__muted">尚无登记证据，不能据此判定通过。</p><p class="pdca__muted">记录更新：{{ time(row.updatedAt) }}</p>
        </article></div>
        <nav class="pdca__pagination" aria-label="记录分页"><button class="pdca__button" :disabled="busy || page <= 1" @click="turn(-1)">上一页</button><span>第 {{ page }} 页</span><button class="pdca__button" :disabled="busy || !items || page * items.pageSize >= items.total" @click="turn(1)">下一页</button></nav>
      </section>
    </template>
  </main>
</template>

<style scoped>
.pdca { max-width: 1440px; margin: 0 auto; padding: 24px; color: var(--text-primary, #1c1c1e); min-width: 0; overflow-wrap: anywhere; }
.pdca__header, .pdca__record-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.pdca h1 { font-size: 28px; margin: 4px 0 10px; } .pdca h2 { font-size: 20px; margin: 26px 0 14px; } .pdca h3 { font-size: 16px; margin: 0 0 10px; } .pdca h4 { font-size: 14px; }
.pdca p, .pdca li { line-height: 1.6; } .pdca__eyebrow { font-size: 12px; color: #5c6470; margin: 0; } .pdca__muted { font-size: 13px; color: #5c6470; }
.pdca__panel, .pdca__stat { border: 1px solid var(--border-color, #dde1e6); border-radius: 12px; background: var(--bg-card, #fff); padding: 18px; min-width: 0; }
.pdca__panel--old { border-style: dashed; } .pdca__notice { padding: 14px; border-radius: 8px; background: #fff4d6; color: #674b00; } .pdca__notice--error { background: #fff0ef; color: #a32620; }
.pdca__stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; } .pdca__stat { display: flex; flex-direction: column; gap: 6px; } .pdca__stat strong { font-size: 26px; } .pdca__stat span { font-size: 13px; }
.pdca__roles { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; } .pdca__records { display: grid; gap: 14px; }
.pdca__metadata { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin: 16px 0; } .pdca__metadata dt { font-size: 12px; color: #5c6470; } .pdca__metadata dd { margin: 6px 0 0; font-size: 14px; }
.pdca__filters { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; } .pdca__filters label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; min-width: 0; }
.pdca select, .pdca input { font: inherit; min-height: 44px; width: 100%; max-width: 100%; box-sizing: border-box; min-width: 0; padding: 10px; border: 1px solid #b9c0c9; border-radius: 8px; background: var(--bg-card, #fff); color: inherit; }
.pdca__filter-actions { display: flex; gap: 10px; flex-wrap: wrap; align-items: end; } .pdca__button { font: inherit; min-height: 44px; padding: 10px 16px; border: 1px solid #b9c0c9; border-radius: 8px; background: #fff; color: #253041; cursor: pointer; flex-shrink: 0; }
.pdca__button--primary { background: #1963cb; border-color: #1963cb; color: #fff; } .pdca__button:disabled { cursor: default; opacity: .55; }
.pdca :is(button, input, select, summary):focus-visible { outline: 3px solid #1963cb; outline-offset: 3px; } .pdca summary { cursor: pointer; padding: 12px 0; min-height: 24px; } .pdca__evidence { border-top: 1px solid #dde1e6; padding: 6px 0; }
.pdca__badge { border-radius: 6px; padding: 6px 10px; background: #edf2f9; color: #234872; font-size: 12px; max-width: 100%; } .pdca__pagination { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 16px; margin: 20px 0; }
.pdca .pdca__sr { position: absolute; width: 1px !important; min-width: 0 !important; max-width: 1px !important; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 800px) { .pdca__stats, .pdca__filters, .pdca__metadata { grid-template-columns: repeat(2, minmax(0, 1fr)); } .pdca__roles { grid-template-columns: 1fr; } }
@media (max-width: 480px) { .pdca { padding: 14px; } .pdca__header, .pdca__record-head { flex-direction: column; } .pdca__header > button { width: 100%; } .pdca__filters, .pdca__metadata { grid-template-columns: 1fr; } .pdca__panel { padding: 14px; } .pdca__filter-actions > button { flex: 1; } }
</style>
