<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { getAccountList } from '@/api/account'
import { getResources, type MerchantResource } from '@/api/merchant'
import { executePublish, getPublishingCapabilities, preflightPublish } from '@/api/matrix'
import PublishAddressFields from '@/components/PublishAddressFields.vue'
import MediaUploader from '@/components/MediaUploader.vue'
import type { PublishAddress } from '@/data/publish-address'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
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
  imagesText: ''
})

const images = computed<string[]>({
  get: () => form.imagesText.split('\n').map(value => value.trim()).filter(Boolean),
  set: value => { form.imagesText = value.join('\n') }
})
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
  if (form.xianyuAccountId) await loadCapabilities()
}

const loadCapabilities = async () => {
  capabilities.value = null
  if (!form.xianyuAccountId) return
  try {
    capabilities.value = (await getPublishingCapabilities(form.xianyuAccountId)).data || null
  } catch {
    capabilities.value = null
  }
}

watch(() => form.xianyuAccountId, () => void loadCapabilities())

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
  if (step.value === 3 && (!form.xianyuAccountId || !form.divisionId || !form.gps)) return toast.error('请选择发布账号和完整发布位置')
  step.value = Math.min(4, step.value + 1)
  maxStep.value = Math.max(maxStep.value, step.value)
}

const goStep = (target: number) => {
  if (target <= maxStep.value) step.value = target
}

const currentPublishRequestId = () => {
  const fingerprint = JSON.stringify({ ...form, images: images.value })
  if (!publishRequestId.value || publishFingerprint.value !== fingerprint) {
    publishRequestId.value = typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
    publishFingerprint.value = fingerprint
  }
  return publishRequestId.value
}

const submit = async (dryRun: boolean) => {
  loading.value = true
  try {
    const command = {
      ...form,
      images: images.value,
      requestId: currentPublishRequestId()
    }
    const response = dryRun ? await preflightPublish(command) : await executePublish(command)
    if (response.data?.valid === false) {
      return toast.error(String(response.data.error || '商品发布失败'))
    }
    const category = response.data?.platform?.category?.catName
    const itemId = response.data?.platform?.itemId
    if (!dryRun && response.data?.platform?.localSynced === false) {
      return toast.warning(`平台已发布${itemId ? `，商品 ID：${itemId}` : ''}，本地同步待恢复，请勿重复发布`)
    }
    toast.success(dryRun
      ? `平台校验通过${category ? `，识别类目：${category}` : ''}`
      : `平台已确认发布成功${itemId ? `，商品 ID：${itemId}` : ''}`)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="workbench publish">
    <header class="workbench__header">
      <div><h1>商品发布</h1><p>本地行政区划、素材复用和发布前校验均已内置，无需额外 API Key。</p></div>
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
          <PublishAddressFields v-model="publishAddress" />
        </div>
        <div class="publish__capability" :class="{ 'publish__capability--unknown': !capabilities }">
          <strong>账号发布能力</strong>
          <span v-if="capabilities">通道 {{ capabilities.channel || capabilities.publishChannel || '待平台适配器确认' }} · 状态 {{ capabilities.status || capabilities.outcomeState || '已读取' }}</span>
          <span v-else>尚未取得可核验发布能力，发布前校验会阻止不满足条件的提交。</span>
        </div>
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
        <div class="publish__notice">提交后将立即调用闲鱼发布接口；只有平台返回真实商品 ID 才会显示成功。遇到平台验证时会停止执行并提示人工处理。</div>
      </template>
      <footer class="workbench__actions publish__footer">
        <button v-if="step > 1" class="workbench__btn" @click="step--">上一步</button>
        <button v-if="step < 4" class="workbench__btn workbench__btn--primary" @click="next">下一步</button>
        <template v-else>
          <button class="workbench__btn" :disabled="loading" @click="submit(true)">发布前校验</button>
          <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="submit(false)">提交发布</button>
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
.publish__capability { display: flex; justify-content: space-between; gap: 16px; margin-top: 12px; padding: 12px 14px; border: 1px solid #a7e0bd; border-radius: 10px; color: #146c43; background: #f0fbf4; font-size: 12px; }
.publish__capability--unknown { border-color: #efd175; color: #704700; background: var(--xy-yellow-soft); }
@media (max-width: 767px) {
  .publish__panel { min-height: 0; padding: 16px; }
  .publish__section-head { align-items: flex-start; flex-direction: column; margin-bottom: 18px; }
  .publish__footer { bottom: -16px; margin: 22px -16px -16px; padding: 12px 16px max(12px, env(safe-area-inset-bottom)); }
  .publish__footer .workbench__btn { flex: 1; }
  .publish__images { grid-template-columns: repeat(3, 1fr); }
  .publish__summary { grid-template-columns: 1fr; }
  .publish__summary img { width: 100%; height: auto; aspect-ratio: 1; }
}
</style>
