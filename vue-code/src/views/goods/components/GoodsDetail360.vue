<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { deleteItem, getGoodsDetail, type GoodsItemWithConfig } from '@/api/goods'
import { getGoodsSkuDetail, type GoodsSku, type GoodsSkuProperty } from '@/api/auto-delivery-config'
import { queryOperationLogs, type OperationLog } from '@/api/operation-log'
import { formatPrice, getGoodsStatusText, showConfirm, showError, showSuccess } from '@/utils'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconClock from '@/components/icons/IconClock.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconSend from '@/components/icons/IconSend.vue'
import IconSparkle from '@/components/icons/IconSparkle.vue'
import IconTrash from '@/components/icons/IconTrash.vue'

type DetailTab = 'basic' | 'sku' | 'marketing' | 'compass' | 'timeline'

const props = defineProps<{
  modelValue: boolean
  goodsId: string
  accountId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'refresh'): void
  (e: 'edit', item: GoodsItemWithConfig): void
  (e: 'sync', goodsId: string): void
  (e: 'configAutoRate', item: GoodsItemWithConfig): void
}>()

const router = useRouter()
const activeTab = ref<DetailTab>('basic')
const loading = ref(false)
const timelineLoading = ref(false)
const timelineLoaded = ref(false)
const goodsDetail = ref<GoodsItemWithConfig | null>(null)
const images = ref<string[]>([])
const skuList = ref<GoodsSku[]>([])
const skuPropertyList = ref<GoodsSkuProperty[]>([])
const operationLogs = ref<OperationLog[]>([])

const tabs = computed(() => [
  { key: 'basic' as const, label: '基本信息' },
  { key: 'sku' as const, label: `SKU${skuList.value.length ? ` (${skuList.value.length})` : ''}` },
  { key: 'marketing' as const, label: '营销' },
  { key: 'compass' as const, label: '数据罗盘' },
  { key: 'timeline' as const, label: `事件时间线${operationLogs.value.length ? ` (${operationLogs.value.length})` : ''}` }
])

const skuPropertyGroups = computed(() => {
  const groups = new Map<number, { propertyId: number; propertyText: string; values: GoodsSkuProperty[] }>()
  for (const prop of skuPropertyList.value) {
    if (!groups.has(prop.propertyId)) {
      groups.set(prop.propertyId, { propertyId: prop.propertyId, propertyText: prop.propertyText, values: [] })
    }
    groups.get(prop.propertyId)!.values.push(prop)
  }
  return [...groups.values()]
})

const automationCards = computed(() => {
  const goods = goodsDetail.value
  if (!goods) return []
  return [
    { label: '自动发货', enabled: goods.xianyuAutoDeliveryOn === 1, detail: goods.autoDeliveryType === 2 ? '卡密库存' : '固定内容', action: 'delivery' },
    { label: '自动回复', enabled: goods.xianyuAutoReplyOn === 1, detail: goods.xianyuKeywordReplyOn === 1 ? 'AI + 关键词' : 'AI / 上下文', action: 'reply' },
    { label: '自动评价', enabled: goods.xianyuAutoRateOn > 0, detail: goods.xianyuAutoRateOn === 2 ? '买家评价后' : '始终评价', action: 'rate' },
    { label: '自动擦亮', enabled: goods.xianyuAutoPolishOn === 1, detail: goods.lastPolishTime ? '已有最近执行记录' : '等待首次执行', action: '' }
  ]
})

const parseImages = (value?: string) => {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    if (!Array.isArray(parsed)) return []
    return parsed.map(item => typeof item === 'string' ? item : item?.url).filter(Boolean)
  } catch {
    return []
  }
}

const logMatchesGoods = (log: OperationLog) => {
  if (log.targetId === props.goodsId) return true
  return [log.operationDesc, log.requestParams, log.responseResult]
    .some(value => typeof value === 'string' && value.includes(props.goodsId))
}

const loadTimeline = async () => {
  if (!props.accountId || timelineLoading.value || timelineLoaded.value) return
  timelineLoading.value = true
  try {
    const response = await queryOperationLogs({ accountId: props.accountId, page: 1, pageSize: 100 })
    if (response.code === 0 || response.code === 200) {
      operationLogs.value = (response.data?.logs || []).filter(logMatchesGoods)
      timelineLoaded.value = true
    }
  } catch (error) {
    console.error('加载商品事件时间线失败', error)
  } finally {
    timelineLoading.value = false
  }
}

const loadDetail = async () => {
  if (!props.goodsId) return
  loading.value = true
  timelineLoaded.value = false
  operationLogs.value = []
  activeTab.value = 'basic'
  try {
    const detailResponse = await getGoodsDetail(props.goodsId)
    if (detailResponse.code !== 0 && detailResponse.code !== 200) throw new Error(detailResponse.msg || '获取商品详情失败')
    goodsDetail.value = detailResponse.data?.itemWithConfig || null
    if (detailResponse.data?.refreshStatus !== 'CACHE' && !detailResponse.data?.refreshed) {
      showError(detailResponse.data?.refreshMessage || '平台详情暂不可用，当前展示已保存快照')
    }
    images.value = parseImages(goodsDetail.value?.item.infoPic)
    if (!images.value.length && goodsDetail.value?.item.coverPic) images.value = [goodsDetail.value.item.coverPic]

    if (props.accountId) {
      const skuResponse = await getGoodsSkuDetail(props.accountId, props.goodsId)
      if (skuResponse.code === 0 || skuResponse.code === 200) {
        skuList.value = skuResponse.data?.skuList || []
        skuPropertyList.value = skuResponse.data?.propertyList || []
      }
    }
  } catch (error: any) {
    showError(error.message || '加载商品详情失败')
  } finally {
    loading.value = false
  }
}

const setTab = (tab: DetailTab) => {
  activeTab.value = tab
  if (tab === 'timeline') loadTimeline()
}

const close = () => {
  emit('update:modelValue', false)
  goodsDetail.value = null
  images.value = []
  skuList.value = []
  skuPropertyList.value = []
  operationLogs.value = []
}

const configure = (action: string) => {
  if (!goodsDetail.value || !props.accountId) return
  if (action === 'rate') {
    emit('configAutoRate', goodsDetail.value)
    close()
    return
  }
  router.push({
    path: action === 'delivery' ? '/auto-delivery' : '/auto-reply',
    query: { accountId: String(props.accountId), goodsId: goodsDetail.value.item.xyGoodId }
  })
  close()
}

const edit = () => {
  if (!goodsDetail.value) return
  emit('edit', goodsDetail.value)
  close()
}

const sync = () => {
  if (!goodsDetail.value) return
  emit('sync', goodsDetail.value.item.xyGoodId)
}

const remove = async () => {
  if (!props.accountId || !goodsDetail.value) return
  try {
    await showConfirm(`确定要删除商品“${goodsDetail.value.item.title}”吗？此操作不可恢复。`, '删除确认')
    const response = await deleteItem({ xianyuAccountId: props.accountId, xyGoodsId: goodsDetail.value.item.xyGoodId })
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '删除失败')
    showSuccess('商品删除成功')
    close()
    emit('refresh')
  } catch (error: any) {
    if (error === 'cancel') return
    showError(error.message || '删除失败')
  }
}

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && props.modelValue) close()
}

watch(() => props.modelValue, visible => {
  if (visible) loadDetail()
})

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Transition name="product-drawer">
    <div v-if="modelValue" class="product-drawer__backdrop" @click.self="close">
      <aside class="product-drawer" role="dialog" aria-modal="true" aria-labelledby="product-detail-title">
        <template v-if="!loading && goodsDetail">
          <header class="product-drawer__header">
            <div class="product-drawer__identity">
              <img v-if="goodsDetail.item.coverPic" :src="goodsDetail.item.coverPic" alt="" />
              <div v-else class="product-drawer__thumb"><IconImage /></div>
              <div>
                <div class="product-drawer__title-row">
                  <h2 id="product-detail-title">{{ goodsDetail.item.title }}</h2>
                  <span class="product-drawer__status">{{ getGoodsStatusText(goodsDetail.item.status).text }}</span>
                </div>
                <p>商品 ID {{ goodsDetail.item.xyGoodId }} · 本地数据</p>
              </div>
            </div>
            <button class="product-drawer__close" aria-label="关闭商品详情" @click="close">×</button>
          </header>

          <nav class="product-drawer__tabs" aria-label="商品详情栏目">
            <button
              v-for="tab in tabs"
              :key="tab.key"
              :class="{ active: activeTab === tab.key }"
              :aria-current="activeTab === tab.key ? 'page' : undefined"
              @click="setTab(tab.key)"
            >{{ tab.label }}</button>
          </nav>

          <main class="product-drawer__body">
            <section v-if="activeTab === 'basic'" class="product-section">
              <div class="product-gallery" :class="{ empty: !images.length }">
                <img v-if="images.length" :src="images[0]" :alt="goodsDetail.item.title" />
                <div v-else><IconImage /><span>暂无商品图片</span></div>
                <div v-if="images.length > 1" class="product-gallery__strip">
                  <img v-for="(image, index) in images.slice(1, 6)" :key="index" :src="image" alt="" />
                </div>
              </div>

              <div class="product-facts">
                <div><span>售价</span><strong class="price">{{ formatPrice(goodsDetail.item.soldPrice) }}</strong></div>
                <div><span>SKU 数</span><strong>{{ skuList.length || goodsDetail.item.skuCount || 0 }}</strong></div>
                <div><span>平台状态</span><strong>{{ getGoodsStatusText(goodsDetail.item.status).text }}</strong></div>
                <div><span>数据来源</span><strong>本地同步</strong></div>
              </div>

              <article v-if="goodsDetail.item.detailInfo" class="product-card">
                <h3>商品描述</h3>
                <p class="product-description">{{ goodsDetail.item.detailInfo }}</p>
              </article>

              <article class="product-card product-meta">
                <h3>同步信息</h3>
                <div><span>创建时间</span><strong>{{ goodsDetail.item.createdTime || '—' }}</strong></div>
                <div><span>更新时间</span><strong>{{ goodsDetail.item.updatedTime || '—' }}</strong></div>
                <p>平台状态、SKU、库存、图集和分类仍以最近一次平台同步为准。</p>
              </article>
            </section>

            <section v-else-if="activeTab === 'sku'" class="product-section">
              <div v-if="skuPropertyGroups.length" class="sku-dimensions">
                <div v-for="group in skuPropertyGroups" :key="group.propertyId">
                  <strong>{{ group.propertyText }}</strong>
                  <span v-for="value in group.values" :key="value.valueId">{{ value.valueText }}</span>
                </div>
              </div>
              <div v-if="skuList.length" class="sku-table" role="table" aria-label="商品规格">
                <div class="sku-table__row sku-table__head" role="row"><span>规格</span><span>价格</span><span>库存</span></div>
                <div v-for="sku in skuList" :key="sku.skuId || sku.id" class="sku-table__row" role="row">
                  <strong>{{ sku.propertyText || sku.valueText || `规格 ${sku.skuId}` }}</strong>
                  <span>¥{{ (sku.price / 100).toFixed(2) }}</span>
                  <span>{{ sku.quantity }}</span>
                </div>
              </div>
              <div v-else class="product-empty"><IconSparkle /><h3>单品出售</h3><p>该商品没有维护 SKU 子项。</p></div>
            </section>

            <section v-else-if="activeTab === 'marketing'" class="product-section">
              <article class="product-card">
                <div class="product-card__heading"><div><h3>本地运营自动化</h3><p>这些能力已接入真实配置与执行链路。</p></div><span class="product-badge ready">已接入</span></div>
                <div class="automation-grid">
                  <div v-for="card in automationCards" :key="card.label" class="automation-card">
                    <span :class="['automation-card__state', { on: card.enabled }]">{{ card.enabled ? '已开启' : '已关闭' }}</span>
                    <strong>{{ card.label }}</strong>
                    <p>{{ card.detail }}</p>
                    <button v-if="card.action" @click="configure(card.action)">配置</button>
                  </div>
                </div>
              </article>
              <article class="product-card">
                <div class="product-card__heading"><div><h3>平台营销</h3><p>先展示能力边界，接口接通后再开放写操作。</p></div><span class="product-badge pending">待平台能力</span></div>
                <div class="capability-list">
                  <div><strong>粉丝价</strong><span>全部粉丝 / 老粉 / 已购粉</span><em>未接通</em></div>
                  <div><strong>小刀活动</strong><span>活动价与份数</span><em>未接通</em></div>
                  <div><strong>闲鱼币抵扣</strong><span>协议与余额前置检查</span><em>未接通</em></div>
                </div>
              </article>
            </section>

            <section v-else-if="activeTab === 'compass'" class="product-section">
              <article class="product-card product-card--warning">
                <div class="product-card__heading"><div><h3>商品经营罗盘</h3><p>曝光、详情访客、咨询、支付与退款尚未获得平台数据源。</p></div><span class="product-badge pending">未同步</span></div>
                <p>这里不会用本地订单或消息数量冒充平台经营指标。接入后将按 1 / 7 / 30 天展示同口径漏斗，并标记每个窗口的同步时间。</p>
              </article>
              <div class="metric-grid" aria-label="待接入经营指标">
                <div><span>曝光人数</span><strong>—</strong><small>UV</small></div>
                <div><span>详情访客</span><strong>—</strong><small>UV</small></div>
                <div><span>咨询买家</span><strong>—</strong><small>UV</small></div>
                <div><span>支付买家</span><strong>—</strong><small>UV</small></div>
                <div><span>退款订单</span><strong>—</strong><small>订单</small></div>
                <div><span>详情→支付</span><strong>—</strong><small>转化率</small></div>
              </div>
            </section>

            <section v-else class="product-section">
              <div v-if="timelineLoading" class="product-empty"><IconClock /><h3>正在加载事件</h3></div>
              <ol v-else-if="operationLogs.length" class="product-timeline">
                <li v-for="log in operationLogs" :key="log.id">
                  <span class="product-timeline__dot" :class="{ failed: log.operationStatus !== 1 }"></span>
                  <div>
                    <div class="product-timeline__heading"><strong>{{ log.operationDesc || log.operationType }}</strong><time>{{ log.createTime }}</time></div>
                    <p>{{ log.operationModule }} · {{ log.operatorUsername || '系统' }} · {{ log.operationStatus === 1 ? '成功' : '失败' }}</p>
                    <details v-if="log.requestParams || log.responseResult || log.errorMessage">
                      <summary>查看原始记录</summary>
                      <pre v-if="log.requestParams">请求：{{ log.requestParams }}</pre>
                      <pre v-if="log.responseResult">响应：{{ log.responseResult }}</pre>
                      <pre v-if="log.errorMessage">错误：{{ log.errorMessage }}</pre>
                    </details>
                  </div>
                </li>
              </ol>
              <div v-else class="product-empty"><IconClock /><h3>暂无商品事件</h3><p>新的同步、编辑和自动化操作会继续写入全局操作日志。</p></div>
            </section>
          </main>

          <footer class="product-drawer__footer">
            <div class="product-drawer__timestamps"><IconClock /> 更新于 {{ goodsDetail.item.updatedTime || '未知' }}</div>
            <div>
              <button @click="sync">同步</button>
              <button @click="edit">编辑本地资料</button>
              <button class="danger" @click="remove"><IconTrash /> 删除</button>
            </div>
          </footer>
        </template>

        <div v-else class="product-drawer__loading"><IconSparkle /><span>正在加载商品档案…</span></div>
      </aside>
    </div>
  </Transition>
</template>

<style scoped>
.product-drawer__backdrop { position: fixed; inset: 0; z-index: 1000; display: flex; justify-content: flex-end; background: rgba(15,23,42,.34); backdrop-filter: blur(4px); }
.product-drawer { width: min(760px, 96vw); height: 100%; display: flex; flex-direction: column; overflow: hidden; background: #f8fafc; box-shadow: -18px 0 50px rgba(15,23,42,.18); }
.product-drawer__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 20px 24px 16px; background: #fff; border-bottom: 1px solid #e7ebf0; }
.product-drawer__identity { display: flex; min-width: 0; gap: 14px; align-items: center; }
.product-drawer__identity > img, .product-drawer__thumb { width: 54px; height: 54px; flex: 0 0 54px; border-radius: 12px; object-fit: cover; background: #f1f5f9; }
.product-drawer__thumb { display: grid; place-items: center; color: #94a3b8; }
.product-drawer__title-row { display: flex; align-items: center; gap: 10px; }
.product-drawer__title-row h2 { max-width: 510px; overflow: hidden; margin: 0; color: #172033; font-size: 18px; text-overflow: ellipsis; white-space: nowrap; }
.product-drawer__identity p { margin: 6px 0 0; color: #718096; font-size: 12px; }
.product-drawer__status { padding: 4px 8px; border-radius: 999px; color: #087443; background: #dcfce7; font-size: 12px; white-space: nowrap; }
.product-drawer__close { width: 36px; height: 36px; border: 0; border-radius: 10px; color: #64748b; background: transparent; font-size: 26px; cursor: pointer; }
.product-drawer__close:hover { background: #f1f5f9; }
.product-drawer__tabs { display: flex; overflow-x: auto; padding: 0 18px; background: #fff; border-bottom: 1px solid #e7ebf0; }
.product-drawer__tabs button { position: relative; flex: 0 0 auto; padding: 15px 14px 13px; border: 0; color: #64748b; background: transparent; font-size: 14px; cursor: pointer; }
.product-drawer__tabs button.active { color: #9a6200; font-weight: 700; }
.product-drawer__tabs button.active::after { position: absolute; right: 12px; bottom: 0; left: 12px; height: 3px; border-radius: 3px 3px 0 0; background: #f3c316; content: ''; }
.product-drawer__body { flex: 1; overflow-y: auto; padding: 22px 24px 110px; }
.product-section { display: grid; gap: 18px; }
.product-gallery { overflow: hidden; padding: 14px; border: 1px solid #e2e8f0; border-radius: 16px; background: #fff; }
.product-gallery > img { display: block; width: 100%; max-height: 320px; border-radius: 10px; object-fit: contain; background: #f8fafc; }
.product-gallery.empty > div { min-height: 180px; display: grid; place-items: center; align-content: center; gap: 8px; color: #94a3b8; }
.product-gallery__strip { display: flex; gap: 8px; margin-top: 10px; }
.product-gallery__strip img { width: 60px; height: 60px; border-radius: 8px; object-fit: cover; }
.product-facts, .metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.product-facts > div, .metric-grid > div { min-width: 0; padding: 16px; border: 1px solid #e2e8f0; border-radius: 14px; background: #fff; }
.product-facts span, .metric-grid span { display: block; color: #64748b; font-size: 12px; }
.product-facts strong, .metric-grid strong { display: block; margin-top: 8px; color: #172033; font-size: 18px; }
.product-facts .price { color: #e11d48; }
.metric-grid { grid-template-columns: repeat(3, 1fr); }
.metric-grid small { color: #94a3b8; }
.product-card { padding: 18px; border: 1px solid #e2e8f0; border-radius: 16px; background: #fff; }
.product-card--warning { border-color: #f7d66b; background: #fffdf4; }
.product-card h3 { margin: 0 0 12px; color: #263349; font-size: 15px; }
.product-card p { margin: 6px 0 0; color: #64748b; font-size: 13px; line-height: 1.65; }
.product-description { white-space: pre-wrap; word-break: break-word; }
.product-meta > div { display: grid; grid-template-columns: 90px 1fr; gap: 12px; padding: 9px 0; border-bottom: 1px solid #f1f5f9; }
.product-meta > div span { color: #64748b; }
.product-card__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.product-card__heading h3 { margin-bottom: 0; }
.product-badge { flex: 0 0 auto; padding: 4px 8px; border-radius: 999px; font-size: 11px; font-style: normal; }
.product-badge.ready { color: #087443; background: #dcfce7; }
.product-badge.pending { color: #9a6200; background: #fff3bf; }
.sku-dimensions { display: grid; gap: 12px; padding: 18px; border-radius: 16px; background: #fff; }
.sku-dimensions > div { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.sku-dimensions strong { width: 80px; color: #475569; }
.sku-dimensions span { padding: 5px 9px; border-radius: 8px; color: #475569; background: #f1f5f9; }
.sku-table { overflow: hidden; border: 1px solid #e2e8f0; border-radius: 16px; background: #fff; }
.sku-table__row { display: grid; grid-template-columns: 1fr 120px 100px; gap: 12px; padding: 13px 16px; border-top: 1px solid #f1f5f9; }
.sku-table__head { border-top: 0; color: #64748b; background: #f8fafc; font-size: 12px; }
.automation-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin-top: 16px; }
.automation-card { position: relative; padding: 15px; border: 1px solid #e2e8f0; border-radius: 12px; }
.automation-card strong { display: block; color: #253147; }
.automation-card p { margin-right: 70px; }
.automation-card button { margin-top: 10px; padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 8px; color: #334155; background: #fff; cursor: pointer; }
.automation-card__state { position: absolute; top: 13px; right: 13px; color: #64748b; font-size: 11px; }
.automation-card__state.on { color: #087443; }
.capability-list { display: grid; gap: 0; margin-top: 14px; }
.capability-list > div { display: grid; grid-template-columns: 110px 1fr auto; align-items: center; gap: 12px; padding: 13px 0; border-top: 1px solid #f1f5f9; }
.capability-list span { color: #64748b; font-size: 12px; }
.capability-list em { color: #9a6200; font-size: 11px; font-style: normal; }
.product-empty { min-height: 260px; display: grid; place-items: center; align-content: center; gap: 8px; color: #94a3b8; text-align: center; }
.product-empty h3, .product-empty p { margin: 0; }
.product-timeline { margin: 0; padding: 6px 0 6px 22px; list-style: none; }
.product-timeline li { position: relative; display: grid; grid-template-columns: 1fr; padding: 0 0 24px 22px; border-left: 2px solid #e2e8f0; }
.product-timeline__dot { position: absolute; top: 2px; left: -6px; width: 10px; height: 10px; border: 2px solid #fff; border-radius: 50%; background: #22c55e; box-shadow: 0 0 0 1px #22c55e; }
.product-timeline__dot.failed { background: #ef4444; box-shadow: 0 0 0 1px #ef4444; }
.product-timeline__heading { display: flex; justify-content: space-between; gap: 16px; }
.product-timeline time, .product-timeline p { color: #64748b; font-size: 12px; }
.product-timeline p { margin: 5px 0; }
.product-timeline details { margin-top: 8px; }
.product-timeline summary { color: #9a6200; cursor: pointer; font-size: 12px; }
.product-timeline pre { overflow-x: auto; max-height: 220px; padding: 10px; border-radius: 8px; color: #475569; background: #f1f5f9; white-space: pre-wrap; word-break: break-all; }
.product-drawer__footer { position: absolute; right: 0; bottom: 0; width: min(760px, 96vw); display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 24px; border-top: 1px solid #e2e8f0; background: rgba(255,255,255,.96); backdrop-filter: blur(18px); }
.product-drawer__timestamps { display: flex; align-items: center; gap: 6px; color: #64748b; font-size: 12px; }
.product-drawer__footer > div:last-child { display: flex; gap: 8px; }
.product-drawer__footer button { display: inline-flex; align-items: center; gap: 5px; padding: 9px 13px; border: 1px solid #cbd5e1; border-radius: 9px; color: #334155; background: #fff; cursor: pointer; }
.product-drawer__footer button.danger { color: #be123c; border-color: #fecdd3; }
.product-drawer__loading { flex: 1; display: grid; place-items: center; align-content: center; gap: 12px; color: #64748b; }
.product-drawer-enter-active, .product-drawer-leave-active { transition: opacity .2s ease; }
.product-drawer-enter-active .product-drawer, .product-drawer-leave-active .product-drawer { transition: transform .25s ease; }
.product-drawer-enter-from, .product-drawer-leave-to { opacity: 0; }
.product-drawer-enter-from .product-drawer, .product-drawer-leave-to .product-drawer { transform: translateX(100%); }
@media (max-width: 720px) {
  .product-drawer { width: 100vw; }
  .product-drawer__header { padding: 14px 16px; }
  .product-drawer__title-row h2 { max-width: 58vw; font-size: 16px; }
  .product-drawer__body { padding: 16px 16px 132px; }
  .product-facts, .metric-grid, .automation-grid { grid-template-columns: repeat(2, 1fr); }
  .product-drawer__footer { width: 100vw; align-items: stretch; flex-direction: column; padding: 10px 16px max(10px, env(safe-area-inset-bottom)); }
  .product-drawer__footer > div:last-child { display: grid; grid-template-columns: repeat(3, 1fr); }
  .product-drawer__footer button { justify-content: center; padding-inline: 8px; }
  .sku-table__row { grid-template-columns: 1fr 80px 64px; }
}
</style>
