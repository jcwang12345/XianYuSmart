<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { getAccountList } from '@/api/account'
import {
  activateGrowthResourceVersion,
  getGrowthResource,
  getGrowthResources,
  saveGrowthResourceVersion,
  type GoodsMapping,
  type GrowthResource
} from '@/api/growth-workspace'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'

const loading = ref(false)
const saving = ref(false)
const supplies = ref<GrowthResource[]>([])
const accounts = ref<Account[]>([])
const keyword = ref('')
const editorOpen = ref(false)
const detailOpen = ref(false)
const detail = ref<GrowthResource>()
const page = ref(1)
const pageSize = 24

const emptyForm = () => ({
  resourceId: undefined as number | undefined,
  name: '',
  accountIds: [] as number[],
  amount: 0,
  stock: 1,
  supplierName: '',
  sourceType: 'SUPPLIER_IMPORT',
  sourceUrl: '',
  sourceItemId: '',
  sourceCapturedTime: '',
  authorizationStatus: 'DECLARED',
  validFrom: '',
  validUntil: '',
  returnPolicy: '',
  fulfillmentLeadTime: '',
  description: '',
  mappings: [] as GoodsMapping[]
})
const form = reactive(emptyForm())

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return value
    ? supplies.value.filter(item => `${item.name} ${item.version?.supplierName || ''} ${item.version?.source?.itemId || ''}`.toLowerCase().includes(value))
    : supplies.value
})
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const pagedSupplies = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const pageRange = computed(() => filtered.value.length
  ? `${(page.value - 1) * pageSize + 1}–${Math.min(page.value * pageSize, filtered.value.length)}`
  : '0')
const accountName = (id?: number) => accounts.value.find(item => item.id === id)?.accountNote
  || accounts.value.find(item => item.id === id)?.unb || `账号 ${id || '-'}`
const display = (value: unknown, fallback = '未同步') => value === null || value === undefined || value === '' ? fallback : String(value)
const time = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未记录'
const requestId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`.slice(0, 64)
const readiness = (item: GrowthResource) => item.version?.readiness
const stateLabel = (value?: string) => ({ ACTIVE: '有效', NO_EXPIRY: '长期有效', FUTURE: '待生效', EXPIRED: '已过期' } as Record<string, string>)[value || ''] || '未知'

const load = async () => {
  loading.value = true
  try {
    const [resources, accountResult] = await Promise.all([getGrowthResources('SUPPLY'), getAccountList()])
    supplies.value = resources.data || []
    accounts.value = accountResult.data?.accounts || []
    page.value = 1
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  Object.assign(form, emptyForm(), { accountIds: accounts.value[0]?.id ? [accounts.value[0].id] : [] })
  editorOpen.value = true
}

const openEdit = async (item: GrowthResource) => {
  const response = await getGrowthResource(item.id)
  const current = response.data || item
  Object.assign(form, emptyForm(), {
    resourceId: current.id,
    name: current.name,
    accountIds: [...(current.accountIds || [])],
    amount: Number(current.amount || 0),
    stock: current.stock || 0,
    supplierName: current.version?.supplierName || '',
    sourceType: current.version?.source?.type || 'SUPPLIER_IMPORT',
    sourceUrl: current.version?.source?.url || '',
    sourceItemId: current.version?.source?.itemId || '',
    authorizationStatus: current.version?.source?.authorizationStatus || 'UNKNOWN',
    validFrom: current.version?.validFrom?.slice(0, 16) || '',
    validUntil: current.version?.validUntil?.slice(0, 16) || '',
    returnPolicy: current.payload?.returnPolicy || '',
    fulfillmentLeadTime: current.payload?.fulfillmentLeadTime || '',
    description: current.payload?.description || '',
    mappings: (current.goodsMappings || []).map(mapping => ({ ...mapping }))
  })
  editorOpen.value = true
}

const openDetail = async (item: GrowthResource) => {
  detailOpen.value = true
  detail.value = undefined
  const response = await getGrowthResource(item.id)
  detail.value = response.data
}

const addMapping = () => {
  form.mappings.push({ accountId: form.accountIds[0] || 0, goodsId: '', skuId: '', status: 'ACTIVE' })
}

const save = async () => {
  if (!form.name.trim()) return toast.error('请输入货源名称')
  if (!form.accountIds.length) return toast.error('至少选择一个适用账号')
  if (!form.supplierName.trim()) return toast.error('请输入供应商或来源主体')
  if (!form.returnPolicy.trim() || !form.fulfillmentLeadTime.trim()) return toast.error('请完整填写退换政策和履约时效')
  if (form.sourceType !== 'MANUAL' && form.sourceType !== 'LOCAL_DRAFT' && !form.sourceUrl.startsWith('https://')) {
    return toast.error('非手工来源必须填写 HTTPS 来源链接')
  }
  if (form.mappings.some(item => !item.accountId || !item.goodsId.trim())) return toast.error('请完整填写商品映射')
  saving.value = true
  try {
    const response = await saveGrowthResourceVersion({
      resourceId: form.resourceId,
      resourceType: 'SUPPLY',
      name: form.name.trim(),
      accountIds: form.accountIds,
      status: 1,
      sourceType: form.sourceType,
      sourceUrl: form.sourceUrl || undefined,
      sourceItemId: form.sourceItemId || undefined,
      sourceCapturedTime: form.sourceCapturedTime || undefined,
      authorizationStatus: form.authorizationStatus,
      supplierName: form.supplierName.trim(),
      validFrom: form.validFrom || undefined,
      validUntil: form.validUntil || undefined,
      requestId: requestId('SUPPLY-VERSION'),
      payload: {
        amount: Number(form.amount), stock: Number(form.stock), description: form.description,
        returnPolicy: form.returnPolicy, fulfillmentLeadTime: form.fulfillmentLeadTime,
        goodsMappings: form.mappings.map(item => ({ ...item, skuId: item.skuId || '' }))
      }
    })
    editorOpen.value = false
    toast.success(`版本 v${response.data?.versions?.[0]?.version || ''} 已保存为草稿`)
    await load()
    if (response.data) await openDetail(response.data)
  } finally {
    saving.value = false
  }
}

const activate = async (version: number) => {
  if (!detail.value) return
  const response = await activateGrowthResourceVersion(detail.value.id, version, requestId('SUPPLY-ACTIVATE'))
  detail.value = response.data
  toast.success(`版本 v${version} 已启用`)
  await load()
}

onMounted(load)
watch(keyword, () => { page.value = 1 })
</script>

<template>
  <section class="workbench supply-page">
    <header class="workbench__header">
      <div><h1>货源库</h1><p>记录供应商、成本、库存、履约、退换、有效期和商品映射；每次修改都会形成不可变版本。</p></div>
      <button class="workbench__btn workbench__btn--primary" @click="openCreate">新增货源</button>
    </header>

    <div class="supply-page__notice">
      <strong>安全边界</strong><span>此处只维护本地货源证据，不会自动采购、发布、改价或删除闲鱼商品。</span>
    </div>

    <div class="workbench__card workbench__toolbar">
      <input v-model="keyword" class="workbench__input" aria-label="搜索货源" placeholder="搜索货源、供应商或来源商品 ID">
      <span class="workbench__muted">{{ loading ? '读取中…' : `共 ${filtered.length} 条` }}</span>
      <button class="workbench__btn" :disabled="loading" @click="load">刷新</button>
    </div>

    <div class="supply-grid workbench__section">
      <article v-for="item in pagedSupplies" :key="item.id" class="workbench__card supply-card" @click="openDetail(item)">
        <header><div><small>#{{ item.id }} · {{ item.version ? `v${item.version.version}` : '无版本' }}</small><h2>{{ item.name }}</h2></div>
          <span class="workbench__tag" :class="{ 'workbench__tag--good': readiness(item)?.ready, 'workbench__tag--warn': !readiness(item)?.ready }">{{ readiness(item)?.ready ? '可用于决策' : '资料待补' }}</span>
        </header>
        <dl>
          <div><dt>供应商</dt><dd>{{ display(item.version?.supplierName) }}</dd></div>
          <div><dt>成本 / 库存</dt><dd>¥ {{ display(item.amount, '--') }} / {{ display(item.stock, '--') }}</dd></div>
          <div><dt>有效状态</dt><dd>{{ stateLabel(item.version?.validityState) }}</dd></div>
          <div><dt>商品映射</dt><dd>{{ item.version?.mappingCount ?? '未同步' }}</dd></div>
        </dl>
        <div class="supply-card__accounts"><span v-for="id in item.accountIds" :key="id">{{ accountName(id) }}</span></div>
        <footer><span>{{ display(item.version?.source?.type, '来源未知') }}</span><button class="supply-card__link" @click.stop="openEdit(item)">创建新版本</button></footer>
      </article>
      <div v-if="!loading && !filtered.length" class="workbench__card workbench__empty">暂无货源。新增后先完善来源、履约和商品映射，再启用版本。</div>
    </div>
    <footer v-if="filtered.length" class="workbench__card supply-pager" aria-label="货源列表分页">
      <span>显示 {{ pageRange }}，共 {{ filtered.length }} 条</span>
      <div><button class="workbench__btn" :disabled="page <= 1" @click="page--">上一页</button><span>第 {{ page }}/{{ pageCount }} 页</span><button class="workbench__btn" :disabled="page >= pageCount" @click="page++">下一页</button></div>
    </footer>

    <div v-if="editorOpen" class="supply-dialog" @click.self="editorOpen = false">
      <form class="supply-dialog__panel" @submit.prevent="save">
        <header class="supply-dialog__header"><div><small>{{ form.resourceId ? `货源 #${form.resourceId}` : '新货源' }}</small><h2>{{ form.resourceId ? '创建货源新版本' : '新增货源' }}</h2></div><button type="button" aria-label="关闭" @click="editorOpen = false">×</button></header>
        <div class="supply-dialog__body">
          <section><h3>基本与适用范围</h3><div class="workbench__grid workbench__grid--two">
            <label class="workbench__field">货源名称<input v-model="form.name" class="workbench__input" maxlength="512"></label>
            <label class="workbench__field">供应商 / 来源主体<input v-model="form.supplierName" class="workbench__input"></label>
            <label class="workbench__field supply-dialog__wide">适用账号<select v-model="form.accountIds" class="workbench__select" multiple><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select><small>按住 Command/Ctrl 可多选；共享资源的修改要求具备全部账号权限。</small></label>
            <label class="workbench__field">成本 / 参考价<input v-model.number="form.amount" class="workbench__input" type="number" min="0" step="0.01"></label>
            <label class="workbench__field">可用库存<input v-model.number="form.stock" class="workbench__input" type="number" min="0" step="1"></label>
          </div></section>
          <section><h3>来源与授权证据</h3><div class="workbench__grid workbench__grid--two">
            <label class="workbench__field">来源类型<select v-model="form.sourceType" class="workbench__select"><option value="SUPPLIER_IMPORT">供应商导入</option><option value="PLATFORM_SEARCH">平台搜索</option><option value="PLATFORM_SHOP">平台店铺</option><option value="MANUAL">人工录入</option><option value="LOCAL_DRAFT">本地草稿</option></select></label>
            <label class="workbench__field">授权状态<select v-model="form.authorizationStatus" class="workbench__select"><option value="VERIFIED">已核验证明</option><option value="DECLARED">人工声明</option><option value="PUBLIC_READ">公开只读</option><option value="UNKNOWN">未知</option><option value="NOT_APPLICABLE">不适用</option></select></label>
            <label class="workbench__field supply-dialog__wide">来源链接<input v-model="form.sourceUrl" class="workbench__input" placeholder="https://"></label>
            <label class="workbench__field">来源商品 ID<input v-model="form.sourceItemId" class="workbench__input"></label>
            <label class="workbench__field">采集时间<input v-model="form.sourceCapturedTime" class="workbench__input" type="datetime-local"></label>
          </div></section>
          <section><h3>履约与有效期</h3><div class="workbench__grid workbench__grid--two">
            <label class="workbench__field">履约时效<input v-model="form.fulfillmentLeadTime" class="workbench__input" placeholder="例如：付款后 24 小时内"></label>
            <label class="workbench__field">退换政策<input v-model="form.returnPolicy" class="workbench__input" placeholder="例如：未使用可在 7 天内申请"></label>
            <label class="workbench__field">生效时间<input v-model="form.validFrom" class="workbench__input" type="datetime-local"></label>
            <label class="workbench__field">失效时间<input v-model="form.validUntil" class="workbench__input" type="datetime-local"></label>
            <label class="workbench__field supply-dialog__wide">说明<textarea v-model="form.description" class="workbench__textarea" rows="3"></textarea></label>
          </div></section>
          <section><div class="supply-dialog__section-title"><h3>商品 / SKU 映射</h3><button type="button" class="workbench__btn" @click="addMapping">添加映射</button></div>
            <div v-if="form.mappings.length" class="mapping-list"><div v-for="(mapping, index) in form.mappings" :key="index" class="mapping-row">
              <select v-model="mapping.accountId" class="workbench__select"><option v-for="id in form.accountIds" :key="id" :value="id">{{ accountName(id) }}</option></select>
              <input v-model="mapping.goodsId" class="workbench__input" placeholder="商品 ID">
              <input v-model="mapping.skuId" class="workbench__input" placeholder="SKU ID（可空）">
              <select v-model="mapping.status" class="workbench__select"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select>
              <button type="button" class="mapping-row__remove" aria-label="移除映射" @click="form.mappings.splice(index, 1)">×</button>
            </div></div><p v-else class="workbench__muted">尚未映射商品。版本可以保存，但不会标记为“可用于决策”。</p>
          </section>
        </div>
        <footer class="supply-dialog__footer"><span>保存只生成草稿版本，不会自动启用。</span><div><button type="button" class="workbench__btn" @click="editorOpen = false">取消</button><button class="workbench__btn workbench__btn--primary" :disabled="saving">{{ saving ? '保存中…' : '保存新版本' }}</button></div></footer>
      </form>
    </div>

    <div v-if="detailOpen" class="supply-dialog" @click.self="detailOpen = false">
      <article class="supply-dialog__panel supply-detail">
        <header class="supply-dialog__header"><div><small>货源 360 档案 · #{{ detail?.id || '-' }}</small><h2>{{ detail?.name || '读取中…' }}</h2></div><button aria-label="关闭" @click="detailOpen = false">×</button></header>
        <div class="supply-dialog__body">
          <template v-if="detail">
            <section class="evidence-grid"><div><span>当前版本</span><strong>{{ detail.version ? `v${detail.version.version}` : '未建立' }}</strong></div><div><span>来源授权</span><strong>{{ display(detail.version?.source?.authorizationStatus) }}</strong></div><div><span>有效状态</span><strong>{{ stateLabel(detail.version?.validityState) }}</strong></div><div><span>引用 / 映射</span><strong>{{ detail.version?.referenceCount ?? '未同步' }} / {{ detail.version?.mappingCount ?? '未同步' }}</strong></div></section>
            <section><h3>当前证据</h3><dl class="detail-list"><div><dt>供应商</dt><dd>{{ display(detail.version?.supplierName) }}</dd></div><div><dt>来源</dt><dd>{{ display(detail.version?.source?.type) }} · {{ display(detail.version?.source?.url) }}</dd></div><div><dt>采集时间</dt><dd>{{ time(detail.version?.source?.capturedAt) }}</dd></div><div><dt>履约 / 退换</dt><dd>{{ display(detail.payload?.fulfillmentLeadTime) }} · {{ display(detail.payload?.returnPolicy) }}</dd></div></dl></section>
            <section><h3>商品映射</h3><div v-if="detail.goodsMappings?.length" class="detail-mappings"><div v-for="mapping in detail.goodsMappings" :key="mapping.id"><strong>{{ accountName(mapping.accountId) }}</strong><span>商品 {{ mapping.goodsId }} · SKU {{ mapping.skuId || '全部' }}</span><em>{{ stateLabel(mapping.validityState) }}</em></div></div><div v-else class="workbench__empty">没有已保存的商品 / SKU 映射</div></section>
            <section><h3>不可变版本记录</h3><div class="version-list"><article v-for="version in detail.versions" :key="version.id"><div><strong>v{{ version.version }}</strong><span>{{ version.lifecycleState }} · {{ time(version.createdTime) }}</span><small>指纹 {{ version.payloadFingerprint?.slice(0, 12) }}… · {{ version.operatorUsername || 'system' }}</small></div><div><span v-if="version.readiness?.blockers?.length" class="version-list__blocker">{{ version.readiness.blockers.join('；') }}</span><button v-if="version.lifecycleState !== 'ACTIVE'" class="workbench__btn" @click="activate(version.version)">启用此版本</button></div></article></div></section>
          </template>
          <div v-else class="workbench__empty">正在读取完整档案…</div>
        </div>
        <footer class="supply-dialog__footer"><span>详情保留当前上下文；不会跳离货源列表。</span><div><button class="workbench__btn" @click="detailOpen = false">关闭</button><button v-if="detail" class="workbench__btn workbench__btn--primary" @click="openEdit(detail)">创建新版本</button></div></footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.supply-page__notice { display:flex; gap:10px; margin-bottom:12px; padding:10px 14px; border:1px solid #f5d565; border-radius:10px; color:#694b00; background:#fff9df; font-size:13px; }
.supply-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; }
.supply-pager { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:12px; color:#667085; font-size:12px; }.supply-pager>div { display:flex; align-items:center; gap:9px; }
.supply-card { display:flex; min-width:0; flex-direction:column; gap:14px; cursor:pointer; transition:.18s ease; }
.supply-card:hover { border-color:#e7bd31; box-shadow:0 10px 28px rgba(121,84,0,.1); transform:translateY(-1px); }
.supply-card header,.supply-card footer,.supply-dialog__header,.supply-dialog__footer,.supply-dialog__section-title { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.supply-card h2 { margin:4px 0 0; overflow:hidden; font-size:16px; text-overflow:ellipsis; white-space:nowrap; }
.supply-card small,.supply-card footer { color:#667085; }
.supply-card dl { display:grid; grid-template-columns:1fr 1fr; gap:12px; margin:0; }
.supply-card dl div { min-width:0; }
.supply-card dt { color:#98a2b3; font-size:11px; }
.supply-card dd { margin:4px 0 0; overflow:hidden; color:#344054; font-size:13px; font-weight:650; text-overflow:ellipsis; white-space:nowrap; }
.supply-card__accounts { display:flex; flex-wrap:wrap; gap:6px; }
.supply-card__accounts span { padding:3px 7px; border-radius:999px; color:#694b00; background:#fff4c2; font-size:11px; }
.supply-card__link,.mapping-row__remove,.supply-dialog__header button { min-width:36px; min-height:36px; border:0; color:#9a6200; background:transparent; cursor:pointer; }
.supply-dialog { position:fixed; inset:0; z-index:1300; display:grid; place-items:center; padding:18px; background:rgba(16,24,40,.55); }
.supply-dialog__panel { display:grid; width:min(1040px,100%); max-height:min(900px,calc(100dvh - 36px)); grid-template-rows:auto minmax(0,1fr) auto; overflow:hidden; border:1px solid #e8e3d4; border-radius:16px; background:#fff; box-shadow:0 24px 80px rgba(16,24,40,.2); }
.supply-dialog__header { padding:16px 20px; border-bottom:1px solid #eaecf0; }
.supply-dialog__header h2 { margin:3px 0 0; font-size:20px; }.supply-dialog__header small { color:#9a6200; }
.supply-dialog__header button { font-size:25px; }
.supply-dialog__body { min-height:0; overflow:auto; overscroll-behavior:contain; padding:4px 20px 24px; }
.supply-dialog__body section { padding:18px 0; border-bottom:1px solid #f0f1f3; }.supply-dialog__body section:last-child { border:0; }
.supply-dialog__body h3 { margin:0 0 13px; color:#344054; font-size:15px; }.supply-dialog__wide { grid-column:1/-1; }
.supply-dialog__footer { padding:13px 20px; border-top:1px solid #eaecf0; background:#fcfcfd; color:#667085; font-size:12px; }
.supply-dialog__footer > div { display:flex; gap:8px; }
.mapping-list,.version-list,.detail-mappings { display:flex; flex-direction:column; gap:8px; }
.mapping-row { display:grid; grid-template-columns:180px 1fr 1fr 110px 36px; gap:8px; }.mapping-row__remove { color:#d92d20; }
.evidence-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:10px; border:0!important; }.evidence-grid div { padding:13px; border:1px solid #eaecf0; border-radius:10px; background:#fcfcfd; }.evidence-grid span,.evidence-grid strong { display:block; }.evidence-grid span { color:#667085; font-size:11px; }.evidence-grid strong { margin-top:6px; }
.detail-list { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin:0; }.detail-list div { padding:10px 12px; border-radius:8px; background:#f8f9fb; }.detail-list dt { color:#667085; font-size:11px; }.detail-list dd { margin:5px 0 0; word-break:break-all; }
.detail-mappings > div,.version-list article { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:11px 12px; border:1px solid #eaecf0; border-radius:9px; }.detail-mappings span { flex:1; color:#667085; }.detail-mappings em { color:#067647; font-style:normal; }
.version-list article > div { display:flex; min-width:0; flex-direction:column; gap:3px; }.version-list article span,.version-list article small { color:#667085; font-size:11px; }.version-list__blocker { color:#b54708!important; }
@media (max-width:1100px) { .supply-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media (max-width:767px) { .supply-page__notice,.supply-dialog__footer,.supply-pager { align-items:flex-start; flex-direction:column; }.supply-grid { grid-template-columns:1fr; }.supply-pager>div { width:100%; justify-content:space-between; }.supply-dialog { padding:0; }.supply-dialog__panel { width:100%; height:100dvh; max-height:none; border:0; border-radius:0; }.supply-dialog__wide { grid-column:auto; }.mapping-row { grid-template-columns:1fr 1fr; }.mapping-row__remove { grid-column:2; justify-self:end; }.evidence-grid,.detail-list { grid-template-columns:1fr 1fr; }.detail-mappings > div,.version-list article { align-items:flex-start; flex-direction:column; }.supply-dialog__footer > div { width:100%; }.supply-dialog__footer .workbench__btn { flex:1; } }
</style>
