<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { getAccountList } from '@/api/account'
import { getResources, type MerchantResource } from '@/api/merchant'
import { executePublish, getPublishingCapabilities, getPublishingRequestStatus, preflightPublish } from '@/api/matrix'
import PublishAddressFields from '@/components/PublishAddressFields.vue'
import MediaUploader from '@/components/MediaUploader.vue'
import type { PublishAddress } from '@/data/publish-address'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import { DELIVERY_METHOD_OPTIONS, MATERIAL_CATEGORY_OPTIONS, PRODUCT_TYPE_OPTIONS } from '@/constants/productOptions'
import '@/styles/merchant-workbench.css'

const step = ref(1)
const maxStep = ref(1)
const loading = ref(false)
const publishRequestId = ref('')
const publishFingerprint = ref('')
const accounts = ref<Account[]>([])
const materials = ref<MerchantResource[]>([])
const capabilities = ref<Record<string, any> | null>(null)
const preflightResult = ref<Record<string, any> | null>(null)
const publishResult = ref<Record<string, any> | null>(null)
const preflightRequestId = ref('')
const submitError = ref('')
const publishSteps = [
  { title: '商品内容', description: '标题与卖点' },
  { title: '价格图片', description: '售价与素材' },
  { title: '账号位置', description: '发布范围' },
  { title: '确认发布', description: '校验并提交' }
]
const form = reactive({
  xianyuAccountId: 0,
  name: '',
  description: '',
  amount: 0,
  stock: 1,
  category: '虚拟商品',
  province: '北京市',
  city: '北京市',
  district: '',
  divisionId: '',
  gps: '',
  poiId: '',
  poiName: '',
  deliveryMethod: '线上交付',
  productType: 'VIRTUAL' as 'VIRTUAL' | 'PHYSICAL',
  freeShipping: true,
  freightTemplateId: '',
  imagesText: '',
  publishChannel: ''
})

const channels = computed<Record<string, any>[]>(() => capabilities.value?.channels || [])
const availableChannels = computed(() => channels.value.filter(channel => channel.available === true))
const selectedChannel = computed(() => channels.value.find(channel => channel.channelCode === form.publishChannel) || null)
const selectedAccount = computed(() => accounts.value.find(account => account.id === form.xianyuAccountId) || null)

const images = computed<string[]>({
  get: () => form.imagesText.split('\n').map(value => value.trim()).filter(Boolean),
  set: value => { form.imagesText = value.join('\n') }
})
const formFingerprint = computed(() => JSON.stringify({ ...form, images: images.value }))
const canExecute = computed(() => Boolean(preflightRequestId.value)
  && preflightRequestId.value === publishRequestId.value
  && publishFingerprint.value === formFingerprint.value)
const publishAddress = computed<PublishAddress>({
  get: () => ({
    province: form.province,
    city: form.city,
    district: form.district,
    divisionId: form.divisionId,
    gps: form.gps,
    poiId: form.poiId,
    poiName: form.poiName
  }),
  set: value => Object.assign(form, value)
})
const deliveryOptions = computed(() => DELIVERY_METHOD_OPTIONS[form.productType])
const currentStep = computed(() => publishSteps[step.value - 1]!)

watch(() => form.productType, value => {
  const options = DELIVERY_METHOD_OPTIONS[value]
  if (!options.some(option => option.value === form.deliveryMethod)) {
    form.deliveryMethod = options[0].value
  }
  if (value === 'PHYSICAL' && form.category === '虚拟商品') form.category = '实物商品'
  if (value === 'VIRTUAL' && form.category === '实物商品') form.category = '虚拟商品'
})

const load = async () => {
  const [accountResult, materialResult] = await Promise.all([getAccountList(), getResources('MATERIAL', 1)])
  accounts.value = accountResult.data?.accounts || []
  materials.value = materialResult.data || []
  form.xianyuAccountId ||= accounts.value[0]?.id || 0
}

const loadCapabilities = async () => {
  capabilities.value = null
  form.publishChannel = ''
  if (!form.xianyuAccountId) return
  try {
    capabilities.value = (await getPublishingCapabilities(form.xianyuAccountId)).data || null
    form.publishChannel = String(availableChannels.value[0]?.channelCode || '')
  } catch {
    capabilities.value = null
  }
}

watch(() => form.xianyuAccountId, () => {
  preflightResult.value = null
  publishResult.value = null
  preflightRequestId.value = ''
  void loadCapabilities()
})

const useMaterial = (event: Event) => {
  const id = Number((event.target as HTMLSelectElement).value)
  const material = materials.value.find(item => item.id === id)
  if (!material) return
  form.name = String(material.data?.title || material.name)
  form.description = String(material.data?.description || '')
  form.amount = Number(material.amount || 0)
  form.stock = material.stock || 1
  form.imagesText = Array.isArray(material.data?.images) ? material.data.images.join('\n') : ''
}

const next = () => {
  if (step.value === 1 && (!form.name.trim() || !form.description.trim())) return toast.error('请完善标题和详情')
  if (step.value === 2 && (!form.amount || !images.value.length)) return toast.error('请完善价格并添加图片')
  if (step.value === 3 && !form.publishChannel) return toast.error('当前账号没有已验证可用的发布通道')
  if (step.value === 3 && (!form.xianyuAccountId || !form.divisionId || !form.gps)) return toast.error('请选择发布账号和完整发布位置')
  step.value = Math.min(4, step.value + 1)
  maxStep.value = Math.max(maxStep.value, step.value)
}

const goStep = (target: number) => {
  if (target <= maxStep.value) step.value = target
}

const currentPublishRequestId = () => {
  const fingerprint = formFingerprint.value
  if (!publishRequestId.value || publishFingerprint.value !== fingerprint) {
    publishRequestId.value = typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
    publishFingerprint.value = fingerprint
  }
  return publishRequestId.value
}

const submit = async (dryRun: boolean) => {
  submitError.value = ''
  if (!form.publishChannel) return toast.error('当前账号没有已验证可用的发布通道')
  const requestId = currentPublishRequestId()
  if (!dryRun && preflightRequestId.value !== requestId) {
    return toast.error('发布内容尚未完成当前版本的发布前校验')
  }
  if (!dryRun) {
    try {
      await showConfirm([
        `账号：${selectedAccount.value?.accountNote || selectedAccount.value?.unb || form.xianyuAccountId}`,
        `通道：${selectedChannel.value?.channelName || form.publishChannel}`,
        `商品：1 件 · 库存 ${form.stock} · 售价 ¥${Number(form.amount).toFixed(2)}`,
        form.publishChannel === 'QA_LOCAL' ? '隔离验收不会调用闲鱼平台。' : '确认后将向闲鱼提交真实发布请求。'
      ].join('\n'), form.publishChannel === 'QA_LOCAL' ? '确认执行隔离发布' : '确认发布商品')
    } catch { return }
  }
  loading.value = true
  try {
    const command = {
      ...form,
      images: images.value,
      requestId
    }
    const response = dryRun ? await preflightPublish(command) : await executePublish(command)
    const qaMock = response.data?.platform?.executionChannel === 'QA_MOCK'
      || response.data?.executionChannel === 'QA_MOCK'
    if (dryRun) {
      preflightResult.value = response.data || null
      publishResult.value = null
      preflightRequestId.value = requestId
    } else {
      publishResult.value = response.data || null
    }
    if (response.data?.valid === false) {
      if (String(response.data.outcomeState) === 'UNKNOWN') {
        return toast.warning(String(response.data.recoveryHint || response.data.error || '发布结果未知，请按请求 ID 查询'))
      }
      return toast.error(String(response.data.error || '商品发布失败'))
    }
    const category = response.data?.platform?.category?.catName || response.data?.platform?.category?.categoryName
    const itemId = response.data?.platform?.itemId
    if (!dryRun && response.data?.platform?.localSynced === false) {
      return toast.warning(qaMock
        ? `隔离任务已执行${itemId ? `，夹具商品 ID：${itemId}` : ''}，本地夹具待恢复；未调用闲鱼平台`
        : `平台已发布${itemId ? `，商品 ID：${itemId}` : ''}，本地同步待恢复，请勿重复发布`)
    }
    toast.success(qaMock
      ? dryRun ? '隔离发布预检通过；未调用闲鱼平台' : `隔离任务执行成功${itemId ? `，夹具商品 ID：${itemId}` : ''}`
      : dryRun ? `平台校验通过${category ? `，识别类目：${category}` : ''}`
        : `平台已确认发布成功${itemId ? `，商品 ID：${itemId}` : ''}`)
  } catch (error: any) {
    submitError.value = error?.message || '发布请求失败'
    toast.error(submitError.value)
  } finally {
    loading.value = false
  }
}

const refreshRequestStatus = async () => {
  if (!publishRequestId.value) return
  loading.value = true
  try {
    publishResult.value = (await getPublishingRequestStatus(publishRequestId.value)).data || null
  } catch (error: any) {
    submitError.value = error?.message || '发布状态查询失败'
  } finally {
    loading.value = false
  }
}

const featureLabel = (value: unknown) => ({
  READY: '可用', SUPPORTED: '支持', PARTIAL: '部分能力', MOCK_ONLY: '仅隔离模拟',
  NOT_VERIFIED: '未验证', UNKNOWN: '未知', UNAVAILABLE: '不可用',
  REQUIRES_PLATFORM_PERMISSION: '需平台权限', NOT_IMPLEMENTED: '未实现', NOT_APPLICABLE: '无需授权'
}[String(value)] || String(value || '未同步'))
const formatCheckedTime = (value: unknown) => {
  if (!value) return '未同步'
  const date = new Date(String(value))
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}
const featureName = (key: string) => ({
  publishing: '发布', category: '类目', edit: '编辑', sku: '多规格',
  bargain: '小刀/营销', sync: '商品同步', authorization: '授权'
}[key] || key)

onMounted(load)
</script>

<template>
  <section class="workbench publish">
    <header class="workbench__header">
      <div><h1>商品发布</h1><p>先核对账号通道与能力证据，再用同一份业务内容完成预检和提交。</p></div>
    </header>
    <div class="workbench__steps publish__steps" aria-label="商品发布进度">
      <button v-for="(item, index) in publishSteps" :key="item.title" class="workbench__step publish__step" :class="{ 'workbench__step--active': step === index + 1, 'publish__step--done': index + 1 < step }" :disabled="index + 1 > maxStep" :aria-current="step === index + 1 ? 'step' : undefined" @click="goStep(index + 1)">
        <span class="publish__step-index">{{ index + 1 < step ? '✓' : index + 1 }}</span>
        <span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span>
      </button>
    </div>

    <form class="workbench__card publish__panel workbench__section" @submit.prevent>
      <div class="publish__section-head">
        <div><span>步骤 {{ step }} / {{ publishSteps.length }}</span><h2>{{ currentStep.title }}</h2></div>
        <p>{{ currentStep.description }}</p>
      </div>
      <template v-if="step === 1">
        <label class="workbench__field">复用已有素材<select class="workbench__select" @change="useMaterial"><option value="">从素材库选择（可选）</option><option v-for="item in materials" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label class="workbench__field">商品标题<input v-model="form.name" class="workbench__input" maxlength="120"><small>{{ form.name.length }} / 120</small></label>
        <label class="workbench__field">商品详情<textarea v-model="form.description" class="workbench__textarea" maxlength="3000"></textarea><small>{{ form.description.length }} / 3000</small></label>
      </template>
      <template v-else-if="step === 2">
        <div class="publish__limits"><strong>当前平台适配边界</strong><span>叶子类目、原价、成色、outerId、完整类目属性、视频与多 SKU 尚未取得稳定适配证据，暂不提交这些字段，避免静默丢失。</span></div>
        <div class="workbench__grid workbench__grid--two">
          <label class="workbench__field">商品类型<select v-model="form.productType" class="workbench__select"><option v-for="item in PRODUCT_TYPE_OPTIONS" :key="item.value" :value="item.value">{{ item.label }} · {{ item.description }}</option></select></label>
          <label class="workbench__field">交付方式<select v-model="form.deliveryMethod" class="workbench__select"><option v-for="item in deliveryOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
          <label class="workbench__field">售价<input v-model.number="form.amount" class="workbench__input" type="number" min="0.01" step="0.01"></label>
          <label class="workbench__field">库存<input v-model.number="form.stock" class="workbench__input" type="number" min="1"></label>
          <label class="workbench__field">素材分类<select v-model="form.category" class="workbench__select"><option v-for="item in MATERIAL_CATEGORY_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option></select><small>用于站内整理；闲鱼真实类目仍由标题、详情和图片识别。</small></label>
          <label v-if="form.deliveryMethod === '快递发货'" class="workbench__field">运费方式<select v-model="form.freeShipping" class="workbench__select"><option :value="true">卖家包邮</option><option :value="false">使用运费模板</option></select></label>
          <label v-if="form.deliveryMethod === '快递发货' && !form.freeShipping" class="workbench__field">运费模板 ID<input v-model="form.freightTemplateId" class="workbench__input" placeholder="平台运费模板 ID"><small>提交后会回读平台结果；无权限时不会显示假成功。</small></label>
        </div>
        <label class="workbench__field">商品图片（最多 9 张）<MediaUploader v-model="images" :account-id="form.xianyuAccountId" :max="9" label="上传商品图" /><small>优先上传到闲鱼图片服务；失败会保存到本机，实际发布时自动同步。</small></label>
        <label class="workbench__field">或粘贴图片地址（每行一张）<textarea v-model="form.imagesText" class="workbench__textarea" maxlength="5000" placeholder="支持 HTTPS 图片地址，也可使用上方上传"></textarea><small>{{ form.imagesText.length }} / 5000</small></label>
      </template>
      <template v-else-if="step === 3">
        <div class="publish__notice">所选省、市、区会直接用于平台发布校验，不再依赖账号是否保存过常用位置。</div>
        <div class="workbench__grid workbench__grid--two">
          <label class="workbench__field">发布账号<select v-model="form.xianyuAccountId" class="workbench__select"><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select></label>
          <label class="workbench__field">发布通道<select v-model="form.publishChannel" class="workbench__select" :disabled="!availableChannels.length"><option v-if="!availableChannels.length" value="">没有已验证可用通道</option><option v-for="channel in availableChannels" :key="channel.channelCode" :value="channel.channelCode">{{ channel.channelName }} · {{ channel.channelCode }}</option></select><small>未授权、未连接或未适配的通道不会出现在可选项中。</small></label>
          <PublishAddressFields v-model="publishAddress" />
        </div>
        <div class="publish__capability" :class="{ 'publish__capability--unknown': !selectedChannel }">
          <div><strong>账号发布能力</strong><span v-if="selectedChannel">{{ selectedChannel.channelName }} · {{ selectedChannel.coverageStatus || '覆盖未知' }} · 检查于 {{ formatCheckedTime(selectedChannel.lastCheckedTime) }}</span><span v-else>尚未取得可核验发布能力，发布前校验会阻止提交。</span></div>
          <div v-if="selectedChannel" class="publish__feature-grid"><span v-for="(value, key) in selectedChannel.features" :key="key"><small>{{ featureName(String(key)) }}</small>{{ featureLabel(value) }}</span></div>
          <p v-if="selectedChannel?.reason">{{ selectedChannel.reason }}</p>
        </div>
        <div v-if="form.publishChannel === 'QA_LOCAL'" class="publish__qa-notice" role="status"><strong>隔离验收通道</strong><span>只生成 QA 夹具和持久化任务，不调用闲鱼网络，也不会发布真实商品。</span></div>
      </template>
      <template v-else>
        <div class="publish__summary">
          <img :src="images[0]" alt="">
          <div>
            <h2>{{ form.name }}</h2>
            <p>{{ form.description }}</p>
            <strong>¥ {{ form.amount }} · 库存 {{ form.stock }}</strong>
            <small>{{ form.category }} · {{ form.deliveryMethod }} · {{ form.province }} {{ form.city }} {{ form.district }}</small>
          </div>
        </div>
        <div class="publish__notice">{{ form.publishChannel === 'QA_LOCAL' ? '当前为隔离验收通道：预检与任务状态可完整验证，但不会调用闲鱼平台。' : '先使用与真实提交相同的最终请求构造器完成校验。内容变化后必须重新校验；只有平台返回真实商品 ID 才显示成功。' }}</div>
        <section v-if="preflightResult" class="publish__evidence" aria-live="polite"><header><strong>{{ preflightResult.platform?.executionChannel === 'QA_MOCK' ? '隔离发布预检已通过' : '发布前校验已通过' }}</strong><span>{{ preflightResult.outcomeState }}</span></header><dl><div><dt>请求 ID</dt><dd>{{ preflightResult.requestId }}</dd></div><div><dt>通道</dt><dd>{{ preflightResult.platform?.publishChannel }}</dd></div><div><dt>{{ preflightResult.platform?.executionChannel === 'QA_MOCK' ? '夹具类目' : '平台类目' }}</dt><dd>{{ preflightResult.platform?.category?.catName || preflightResult.platform?.category?.categoryName || '未返回名称' }}</dd></div><div><dt>图片</dt><dd>{{ preflightResult.platform?.imageCount }} 张</dd></div></dl><small>{{ preflightResult.platform?.executionChannel === 'QA_MOCK' ? '隔离预检不使用生产构造器，平台网络调用：0' : `预览与提交共用生产请求构造器：${preflightResult.platform?.previewUsesProductionBuilder ? '是' : '未确认'}` }}</small></section>
        <section v-if="publishResult" class="publish__evidence"><header><strong>发布结果</strong><span>{{ publishResult.outcomeState || publishResult.status }}</span></header><p>{{ publishResult.recoveryHint || publishResult.error || '平台结果已记录' }}</p><button v-if="['UNKNOWN','PENDING'].includes(String(publishResult.outcomeState || publishResult.verificationStatus))" class="workbench__btn" type="button" @click="refreshRequestStatus">按请求 ID 查询结果</button></section>
        <div v-if="submitError" class="publish__error" role="alert">{{ submitError }}</div>
      </template>
      <footer class="workbench__actions publish__footer">
        <button v-if="step > 1" class="workbench__btn" @click="step--">上一步</button>
        <button v-if="step < 4" class="workbench__btn workbench__btn--primary" :disabled="step === 3 && !form.publishChannel" @click="next">下一步</button>
        <template v-else>
          <button class="workbench__btn" :disabled="loading" @click="submit(true)">发布前校验</button>
          <button class="workbench__btn workbench__btn--primary" :disabled="loading || !canExecute" @click="submit(false)">提交发布</button>
        </template>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.publish__steps { max-width: 1120px; margin-inline: auto; }
.publish__step { display: flex; align-items: center; justify-content: flex-start; gap: 10px; text-align: left; }
.publish__step > span:last-child { display: flex; min-width: 0; flex-direction: column; }
.publish__step strong { color: inherit; font-size: 13px; font-weight: 700; }
.publish__step small { margin-top: 2px; color: #858178; font-size: 11px; }
.publish__step-index { display: grid; width: 28px; height: 28px; flex: 0 0 28px; place-items: center; border-radius: 50%; color: #68645d; background: #efeee9; font-size: 12px; font-weight: 750; }
.workbench__step--active .publish__step-index { color: #171717; background: var(--xy-yellow); }
.publish__step--done .publish__step-index { color: #157347; background: #e9f8ef; }
.publish__panel { max-width: 1120px; min-height: 470px; margin-right: auto; margin-left: auto; padding: 24px; }
.publish__section-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin: -4px 0 22px; padding-bottom: 16px; border-bottom: 1px solid var(--glass-border); }
.publish__section-head span { color: #9a6200; font-size: 11px; font-weight: 750; letter-spacing: .06em; }
.publish__section-head h2 { margin: 3px 0 0; font-size: 19px; }
.publish__section-head p { margin: 0; color: #77736b; font-size: 13px; }
.publish__panel > .workbench__field { margin-bottom: 14px; }
.publish__footer { position: sticky; bottom: -24px; z-index: 3; justify-content: flex-end; margin: 24px -24px -24px; padding: 14px 24px; border-top: 1px solid var(--glass-border); border-radius: 0 0 var(--surface-radius) var(--surface-radius); background: rgba(255,255,255,.96); }
.publish__images { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; margin-top: 12px; }
.publish__images button { position: relative; overflow: hidden; padding: 0; border: 0; border-radius: 7px; background: #f2f4f7; cursor: pointer; }
.publish__images img { display: block; width: 100%; aspect-ratio: 1; object-fit: cover; }
.publish__images span { position: absolute; right: 4px; bottom: 4px; padding: 3px 6px; border-radius: 4px; color: #fff; background: rgba(16, 24, 40, .72); font-size: 10px; }
.publish__summary { display: grid; grid-template-columns: 200px 1fr; gap: 18px; }
.publish__summary img { width: 200px; height: 200px; border-radius: 8px; object-fit: cover; background: #f2f4f7; }
.publish__summary p { color: #667085; white-space: pre-wrap; }
.publish__summary strong, .publish__summary small { display: block; margin-top: 10px; }
.publish__notice { margin-top: 16px; padding: 12px 14px; border: 1px solid #efd175; border-radius: 10px; color: #704700; background: var(--xy-yellow-soft); font-size: 12px; }
.publish__limits { display: flex; gap: 10px; margin-bottom: 16px; padding: 12px 14px; border: 1px solid #d6d9df; border-radius: 10px; color: #344054; background: #f8fafc; font-size: 12px; line-height: 1.55; }
.publish__limits span { color: #667085; }
.publish__capability { display: grid; gap: 12px; margin-top: 12px; padding: 14px; border: 1px solid #a7e0bd; border-radius: 10px; color: #146c43; background: #f0fbf4; font-size: 12px; }
.publish__capability > div:first-child { display: flex; justify-content: space-between; gap: 16px; }
.publish__capability p { margin: 0; }
.publish__feature-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(120px,1fr)); gap: 8px; }
.publish__feature-grid span { padding: 8px 10px; border-radius: 7px; background: rgba(255,255,255,.78); font-weight: 700; }
.publish__feature-grid small { display: block; margin-bottom: 3px; color: #667085; font-weight: 500; }
.publish__capability--unknown { border-color: #efd175; color: #704700; background: var(--xy-yellow-soft); }
.publish__qa-notice { display: flex; gap: 10px; margin-top: 12px; padding: 12px 14px; border: 1px dashed #7c6de0; border-radius: 10px; color: #40358b; background: #f7f5ff; font-size: 12px; }
.publish__qa-notice span { color: #5e5793; }
.publish__evidence { margin-top: 14px; padding: 14px; border: 1px solid #cfd8e3; border-radius: 10px; background: #f8fafc; }
.publish__evidence header { display: flex; justify-content: space-between; gap: 12px; }
.publish__evidence dl { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; }
.publish__evidence dl div { min-width: 0; }
.publish__evidence dt { color: #667085; font-size: 11px; }
.publish__evidence dd { overflow-wrap: anywhere; margin: 2px 0 0; font-size: 12px; font-weight: 700; }
.publish__error { margin-top: 14px; padding: 12px 14px; border: 1px solid #f2b8b5; border-radius: 10px; color: #9b1c1c; background: #fff4f3; }
@media (max-width: 767px) {
  .publish__panel { min-height: 0; padding: 16px; }
  .publish__section-head { align-items: flex-start; flex-direction: column; margin-bottom: 18px; }
  .publish__limits { flex-direction: column; }
  .publish__footer { bottom: -16px; margin: 22px -16px -16px; padding: 12px 16px max(12px, env(safe-area-inset-bottom)); }
  .publish__footer .workbench__btn { flex: 1; }
  .publish__images { grid-template-columns: repeat(3, 1fr); }
  .publish__summary { grid-template-columns: 1fr; }
  .publish__summary img { width: 100%; height: auto; aspect-ratio: 1; }
  .publish__capability > div:first-child { flex-direction: column; }
  .publish__qa-notice { flex-direction: column; }
  .publish__evidence dl { grid-template-columns: 1fr; }
}
</style>
