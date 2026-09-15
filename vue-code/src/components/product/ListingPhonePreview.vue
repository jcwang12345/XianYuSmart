<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{ form: Record<string, any>; accountName?: string; channelName?: string; categoryName?: string }>()
const price = computed(() => Number(props.form.amount || 0).toFixed(2))
const originalPrice = computed(() => Number(props.form.originalPrice || 0))
const firstImage = computed(() => Array.isArray(props.form.images) ? props.form.images[0] : '')
const imageLoadFailed = ref(false)
watch(firstImage, () => { imageLoadFailed.value = false })
const shipping = computed(() => ({
  FREE_SHIPPING: '包邮', FREIGHT_TEMPLATE: '按运费模板', SELF_PICKUP: '当面交易',
  ONLINE_DELIVERY: '线上交付', FACE_TO_FACE: '当面交易', REMOTE_SERVICE: '远程服务',
  ON_SITE_SERVICE: '上门服务', STORE_SERVICE: '到店服务'
}[String(props.form.shippingMode)] || '交付方式待选择'))
const condition = computed(() => ({ NEW: '全新', LIKE_NEW: '几乎全新', GOOD: '成色良好', FAIR: '明显使用痕迹', DIGITAL: '数字交付', SERVICE: '预约服务' }[String(props.form.conditionCode)] || '交付性质待选择'))
const productType = computed(() => ({ PHYSICAL: '实物', VIRTUAL: '虚拟', SERVICE: '服务' }[String(props.form.productType)] || '类型待选择'))
const support = computed(() => props.form.productType === 'PHYSICAL' ? props.form.afterSalesPolicy : props.form.supportPolicy)
</script>

<template>
  <aside class="listing-preview" aria-label="买家视角实时预览">
    <div class="listing-preview__head"><div><strong>买家视角预览</strong><span>本地草稿</span></div><small>平台预检后再核对最终结果</small></div>
    <div class="listing-preview__device">
      <div class="listing-preview__status"><span>9:41</span><span>••• ᯤ 🔋</span></div>
      <div class="listing-preview__media" :class="{ 'listing-preview__media--empty': !firstImage || imageLoadFailed }">
        <img v-if="firstImage && !imageLoadFailed" :src="firstImage" alt="商品封面预览" @error="imageLoadFailed = true">
        <div v-else role="status"><span>{{ imageLoadFailed ? '图片无法加载' : '图片预览' }}</span><small>{{ imageLoadFailed ? '请检查地址或重新上传' : '上传首图后显示' }}</small></div>
        <em v-if="form.images?.length">1 / {{ form.images.length }}</em>
      </div>
      <div class="listing-preview__body">
        <div class="listing-preview__price"><small>¥</small><strong>{{ price }}</strong><del v-if="originalPrice > Number(form.amount || 0)">¥{{ originalPrice.toFixed(2) }}</del></div>
        <h3>{{ form.name || '填写标题后在这里预览买家看到的商品名称' }}</h3>
        <div class="listing-preview__tags"><span>{{ productType }}</span><span>{{ condition }}</span><span>{{ shipping }}</span><span v-if="categoryName">{{ categoryName }}</span></div>
        <p>{{ form.description || '商品详情、交付说明和售后说明将在这里展示。' }}</p>
        <div v-if="form.skus?.length" class="listing-preview__sku"><strong>选择规格</strong><span v-for="sku in form.skus.slice(0, 3)" :key="sku.key">{{ sku.key }}</span><small v-if="form.skus.length > 3">+{{ form.skus.length - 3 }}</small></div>
        <div v-if="form.serviceProtocols?.length" class="listing-preview__services"><strong>服务保障</strong><span v-for="service in form.serviceProtocols" :key="service">✓ {{ service }}</span></div>
        <div v-if="support" class="listing-preview__services"><strong>交付与售后</strong><span>{{ support }}</span><span v-if="form.productType === 'VIRTUAL'">有效期 {{ form.validityDays || '—' }} 天</span><span v-if="form.productType === 'SERVICE'">服务 {{ form.serviceDurationMinutes || '—' }} 分钟 · 提前 {{ form.appointmentLeadHours ?? '—' }} 小时预约</span></div>
      </div>
      <div class="listing-preview__seller"><div class="listing-preview__avatar">闲</div><div><strong>{{ accountName || '发布账号' }}</strong><small>{{ channelName || '通道待选择' }}</small></div><button type="button">想要</button></div>
      <div class="listing-preview__bar"><span>聊一聊</span><strong>我想要</strong></div>
    </div>
    <p class="listing-preview__note">此预览由当前结构化草稿实时生成；平台可能按类目规则调整标题、标签、运费或属性，发布前预检会单独展示差异。</p>
  </aside>
</template>

<style scoped>
.listing-preview{position:sticky;top:18px;display:grid;gap:12px;align-self:start}.listing-preview__head{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}.listing-preview__head>div{display:flex;align-items:center;gap:8px}.listing-preview__head span{padding:3px 7px;border-radius:999px;color:#8a5a00;background:#fff1b8;font-size:10px}.listing-preview__head small{color:#8a867d;font-size:10px;text-align:right}.listing-preview__device{overflow:hidden;max-width:360px;margin:auto;border:8px solid #222;border-radius:28px;background:#f7f6f2;box-shadow:0 18px 45px rgba(36,31,22,.18)}.listing-preview__status{display:flex;justify-content:space-between;padding:10px 16px 8px;background:#fff;font-size:10px;font-weight:750}.listing-preview__media{position:relative;display:grid;aspect-ratio:1;place-items:center;background:#eeeae2}.listing-preview__media img{width:100%;height:100%;object-fit:cover}.listing-preview__media--empty div{display:grid;gap:3px;color:#928d84;text-align:center}.listing-preview__media--empty small{font-size:10px}.listing-preview__media em{position:absolute;right:10px;bottom:10px;padding:4px 7px;border-radius:999px;color:#fff;background:rgba(0,0,0,.55);font-size:10px;font-style:normal}.listing-preview__body{display:grid;gap:10px;padding:14px;background:#fff}.listing-preview__price{display:flex;align-items:baseline;gap:4px;color:#ef6c00}.listing-preview__price strong{font-size:26px}.listing-preview__price del{margin-left:6px;color:#aaa49a;font-size:11px}.listing-preview h3{margin:0;color:#26231f;font-size:15px;line-height:1.45}.listing-preview__tags{display:flex;flex-wrap:wrap;gap:5px}.listing-preview__tags span{padding:3px 6px;border-radius:4px;color:#725200;background:#fff4c9;font-size:10px}.listing-preview__body>p{display:-webkit-box;overflow:hidden;margin:0;color:#6f6a62;font-size:11px;line-height:1.6;-webkit-line-clamp:4;-webkit-box-orient:vertical;white-space:pre-wrap}.listing-preview__sku,.listing-preview__services{display:flex;flex-wrap:wrap;align-items:center;gap:6px;padding-top:9px;border-top:1px solid #efede8;font-size:10px}.listing-preview__sku strong,.listing-preview__services strong{width:100%;font-size:11px}.listing-preview__sku span{padding:4px 7px;border:1px solid #dedad0;border-radius:5px}.listing-preview__services span{color:#5f5a52}.listing-preview__seller{display:flex;align-items:center;gap:9px;margin-top:8px;padding:12px 14px;background:#fff}.listing-preview__avatar{display:grid;width:34px;height:34px;place-items:center;border-radius:50%;color:#222;background:#ffd733;font-weight:800}.listing-preview__seller>div:nth-child(2){display:grid;flex:1}.listing-preview__seller small{color:#908b82;font-size:9px}.listing-preview__seller button{border:0;border-radius:999px;padding:6px 10px;background:#f1f0ec}.listing-preview__bar{display:grid;grid-template-columns:1fr 1.3fr;gap:7px;padding:10px;background:#fff}.listing-preview__bar span,.listing-preview__bar strong{padding:10px;border-radius:999px;text-align:center;font-size:12px}.listing-preview__bar span{background:#f1f0ec}.listing-preview__bar strong{background:#ffd733}.listing-preview__note{margin:0;color:#77736b;font-size:11px;line-height:1.55}@media(max-width:1100px){.listing-preview{position:static}.listing-preview__device{max-width:330px}}
</style>
