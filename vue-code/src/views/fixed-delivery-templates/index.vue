<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getAccountList } from '@/api/account'
import {
  deleteFixedDeliveryTemplate,
  getFixedDeliveryTemplates,
  getFixedTemplateReferences,
  getFixedTemplateVersions,
  previewFixedDeliveryTemplate,
  saveFixedDeliveryTemplate,
  type FixedDeliveryTemplate,
  type FixedTemplateReference,
  type FixedTemplateVersion
} from '@/api/fixed-delivery-template'
import type { Account } from '@/types'
import { showConfirm, showError, showSuccess } from '@/utils'

const route = useRoute()
const accounts = ref<Account[]>([])
const selectedAccountId = ref<number | null>(null)
const templates = ref<FixedDeliveryTemplate[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const keyword = ref('')
const serverPreview = ref('')
const previewLoading = ref(false)
const previewError = ref('')
const inspecting = ref<FixedDeliveryTemplate | null>(null)
const references = ref<FixedTemplateReference[]>([])
const versions = ref<FixedTemplateVersion[]>([])
const evidenceLoading = ref(false)
let previewTimer: ReturnType<typeof setTimeout> | undefined

const newRequestId = (prefix: string) => `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
const form = ref({
  id: undefined as number | undefined,
  xianyuAccountIds: [] as number[],
  templateName: '',
  deliveryContent: '',
  messageTemplate: '您好，{buyerName}，订单 {orderId} 已发货：\n{deliveryContent}'
})

const localPreview = computed(() => form.value.messageTemplate
  .split('{buyerName}').join('示例会员')
  .split('{orderId}').join('202607240001')
  .split('{deliveryContent}').join(form.value.deliveryContent || '这里显示固定发货内容'))

const loadTemplates = async () => {
  if (!selectedAccountId.value) return
  loading.value = true
  try {
    const response = await getFixedDeliveryTemplates(selectedAccountId.value, keyword.value)
    templates.value = response.code === 200 ? (response.data || []) : []
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  form.value = {
    id: undefined,
    xianyuAccountIds: selectedAccountId.value ? [selectedAccountId.value] : [],
    templateName: '',
    deliveryContent: '',
    messageTemplate: '您好，{buyerName}，订单 {orderId} 已发货：\n{deliveryContent}'
  }
  dialogVisible.value = true
  schedulePreview()
}

const openEdit = (template: FixedDeliveryTemplate) => {
  form.value = {
    id: template.id,
    xianyuAccountIds: template.xianyuAccountIds?.length ? [...template.xianyuAccountIds] : [template.xianyuAccountId],
    templateName: template.templateName,
    deliveryContent: template.deliveryContent,
    messageTemplate: template.messageTemplate
  }
  dialogVisible.value = true
  schedulePreview()
}

const appendVariable = (variable: string) => {
  form.value.messageTemplate += variable
}

const refreshServerPreview = async () => {
  if (!selectedAccountId.value || !form.value.deliveryContent.trim()) {
    serverPreview.value = ''
    previewError.value = ''
    return
  }
  previewLoading.value = true
  try {
    const response = await previewFixedDeliveryTemplate({
      xianyuAccountId: selectedAccountId.value,
      deliveryContent: form.value.deliveryContent,
      messageTemplate: form.value.messageTemplate,
      buyerName: '示例会员',
      orderId: '202607240001'
    })
    if (response.code !== 200) throw new Error(response.msg || '预览失败')
    serverPreview.value = response.data?.renderedContent || ''
    previewError.value = ''
  } catch (error: any) {
    serverPreview.value = ''
    previewError.value = error.message || '预览失败'
  } finally {
    previewLoading.value = false
  }
}

const schedulePreview = () => {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(refreshServerPreview, 280)
}

const openEvidence = async (template: FixedDeliveryTemplate) => {
  if (!selectedAccountId.value) return
  inspecting.value = template
  references.value = []
  versions.value = []
  evidenceLoading.value = true
  try {
    const [referenceResponse, versionResponse] = await Promise.all([
      getFixedTemplateReferences(selectedAccountId.value, template.id),
      getFixedTemplateVersions(selectedAccountId.value, template.id)
    ])
    if (referenceResponse.code !== 200) throw new Error(referenceResponse.msg || '引用查询失败')
    if (versionResponse.code !== 200) throw new Error(versionResponse.msg || '版本查询失败')
    references.value = referenceResponse.data || []
    versions.value = versionResponse.data || []
  } catch (error: any) {
    showError(error.message || '模板证据加载失败')
  } finally {
    evidenceLoading.value = false
  }
}

const submit = async () => {
  if (!selectedAccountId.value) return
  if (!form.value.xianyuAccountIds.length) {
    showError('请至少选择一个适用账号')
    return
  }
  if (!form.value.templateName.trim() || !form.value.deliveryContent.trim()) {
    showError('请填写模板名称和全部发货内容')
    return
  }
  if (!form.value.messageTemplate.includes('{deliveryContent}')) {
    showError('发送模板必须包含“全部发货内容”变量')
    return
  }
  await refreshServerPreview()
  if (previewError.value) {
    showError(previewError.value)
    return
  }
  if (localPreview.value.length > 200) {
    showError('发送预览不能超过200个字符，请缩短模板或发货内容')
    return
  }
  saving.value = true
  try {
    const response = await saveFixedDeliveryTemplate({
      ...form.value,
      xianyuAccountId: form.value.xianyuAccountIds[0]!,
      requestId: newRequestId('fixed-template-save')
    })
    if (response.code !== 200) {
      throw new Error(response.msg || '保存失败')
    }
    showSuccess('固定内容模板已保存')
    dialogVisible.value = false
    await loadTemplates()
  } catch (error: any) {
    showError(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const removeTemplate = async (template: FixedDeliveryTemplate) => {
  if (!selectedAccountId.value) return
  try {
    await showConfirm(`确定删除模板“${template.templateName}”？`, '删除模板')
    const response = await deleteFixedDeliveryTemplate(selectedAccountId.value, template.id,
      newRequestId('fixed-template-delete'))
    if (response.code !== 200) {
      throw new Error(response.msg || '删除失败')
    }
    showSuccess('模板已删除')
    await loadTemplates()
  } catch (error: any) {
    if (error !== 'cancel') showError(error.message || '删除失败')
  }
}

watch(selectedAccountId, loadTemplates)
watch(() => [form.value.deliveryContent, form.value.messageTemplate], schedulePreview)

onMounted(async () => {
  const response = await getAccountList()
  accounts.value = response.data?.accounts || []
  const queryAccountId = Number(route.query.accountId)
  selectedAccountId.value = accounts.value.some(account => account.id === queryAccountId)
    ? queryAccountId
    : (accounts.value[0]?.id || null)
})

onBeforeUnmount(() => {
  if (previewTimer) clearTimeout(previewTimer)
})
</script>

<template>
  <div class="fixed-template-page">
    <header class="fixed-template-header">
      <div>
        <h1>固定内容模板</h1>
        <p>统一管理网盘链接、下载说明等固定资源，商品只需选择模板。</p>
      </div>
      <div class="fixed-template-actions">
        <div class="template-search">
          <input v-model.trim="keyword" placeholder="搜索模板名称或内容" @keyup.enter="loadTemplates" />
          <button @click="loadTemplates">搜索</button>
        </div>
        <select v-model="selectedAccountId">
          <option v-for="account in accounts" :key="account.id" :value="account.id">
            {{ account.accountNote || account.unb }}
          </option>
        </select>
        <button class="primary-btn" @click="openCreate">新建模板</button>
      </div>
    </header>

    <section class="fixed-template-panel">
      <div v-if="loading" class="empty-state">正在加载...</div>
      <div v-else-if="templates.length === 0" class="empty-state">
        暂无固定内容模板，创建后即可在商品自动发货中复用。
      </div>
      <div v-else class="template-list">
        <article v-for="template in templates" :key="template.id" class="template-card">
          <div class="template-card-header">
            <div class="template-card-title">
              <strong>{{ template.templateName }}</strong>
              <span>v{{ template.templateVersion || 1 }} · {{ template.referenceCount || 0 }} 个引用</span>
            </div>
            <div>
              <button @click="openEvidence(template)">引用与版本</button>
              <button @click="openEdit(template)">编辑</button>
              <button class="danger-text" @click="removeTemplate(template)">删除</button>
            </div>
          </div>
          <dl>
            <dt>适用账号</dt>
            <dd>{{ template.xianyuAccountIds.map(id => accounts.find(account => account.id === id)?.accountNote || accounts.find(account => account.id === id)?.unb || id).join('、') }}</dd>
            <dt>全部发货内容</dt>
            <dd>{{ template.deliveryContent }}</dd>
            <dt>最终发送模板</dt>
            <dd>{{ template.messageTemplate }}</dd>
          </dl>
        </article>
      </div>
    </section>

    <div v-if="dialogVisible" class="template-dialog-mask" @click.self="dialogVisible = false">
      <form class="template-dialog" @submit.prevent="submit">
        <header>
          <div>
            <h2>{{ form.id ? '编辑模板' : '新建模板' }}</h2>
            <p>模板可被多个商品复用，不消耗卡密库存。</p>
          </div>
          <button type="button" class="close-btn" @click="dialogVisible = false">×</button>
        </header>
        <label>
          <span>适用账号（可多选）</span>
          <select v-model="form.xianyuAccountIds" multiple :size="Math.min(accounts.length, 5)">
            <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
          </select>
          <small class="template-field-count">共享模板只保存一份，所选账号均可在自动发货中引用</small>
        </label>
        <label>
          <span>模板名称</span>
          <input v-model="form.templateName" maxlength="100" placeholder="例如：百度网盘资源" />
          <small class="template-field-count">{{ form.templateName.length }} / 100</small>
        </label>
        <label>
          <span>全部发货内容</span>
          <textarea v-model="form.deliveryContent" maxlength="200" rows="5" placeholder="填写网盘链接、提取码、使用说明等固定内容"></textarea>
          <small class="template-field-count">{{ form.deliveryContent.length }} / 200</small>
        </label>
        <label>
          <span class="template-label-row">
            最终发送模板
            <span>
              <button type="button" @click="appendVariable('{buyerName}')">会员名称</button>
              <button type="button" @click="appendVariable('{orderId}')">订单号</button>
              <button type="button" @click="appendVariable('{deliveryContent}')">全部发货内容</button>
            </span>
          </span>
          <textarea v-model="form.messageTemplate" maxlength="200" rows="5"></textarea>
          <small class="template-field-count">{{ form.messageTemplate.length }} / 200</small>
        </label>
        <div class="template-preview" :class="{ 'template-preview--invalid': localPreview.length > 200 || !!previewError }">
          <strong>服务端发送预览 <span v-if="previewLoading">校验中…</span></strong>
          <p>{{ serverPreview || localPreview }}</p>
          <small v-if="previewError">{{ previewError }}</small>
          <small v-else>{{ (serverPreview || localPreview).length }} / 200 · 保存前由后端使用生产变量规则校验</small>
        </div>
        <footer>
          <button type="button" @click="dialogVisible = false">取消</button>
          <button type="submit" class="primary-btn" :disabled="saving || previewLoading || !!previewError || localPreview.length > 200">{{ saving ? '保存中...' : '保存模板' }}</button>
        </footer>
      </form>
    </div>

    <div v-if="inspecting" class="template-dialog-mask" @click.self="inspecting = null">
      <section class="template-evidence-dialog" role="dialog" aria-modal="true" aria-labelledby="template-evidence-title">
        <header>
          <div>
            <span class="evidence-kicker">模板证据</span>
            <h2 id="template-evidence-title">{{ inspecting.templateName }}</h2>
            <p>当前 v{{ inspecting.templateVersion || 1 }} · 引用与版本均来自本地持久化事实。</p>
          </div>
          <button class="close-btn" aria-label="关闭" @click="inspecting = null">×</button>
        </header>
        <div v-if="evidenceLoading" class="empty-state">正在加载引用和版本…</div>
        <div v-else class="template-evidence-grid">
          <section>
            <header><strong>商品引用</strong><span>{{ references.length }}</span></header>
            <div v-if="references.length === 0" class="evidence-empty">暂无商品引用，可以安全删除。</div>
            <article v-for="item in references" :key="item.configId" class="evidence-row">
              <strong>商品 {{ item.goodsId }}</strong>
              <span>账号 {{ item.accountId }} · {{ item.skuName || item.skuId || '整品' }}</span>
            </article>
          </section>
          <section>
            <header><strong>版本记录</strong><span>{{ versions.length }}</span></header>
            <div v-if="versions.length === 0" class="evidence-empty">暂无版本记录。</div>
            <article v-for="item in versions" :key="item.templateVersion" class="evidence-row">
              <strong>v{{ item.templateVersion }}</strong>
              <span>{{ item.createdTime }} · {{ item.operatorUsername || '系统' }}</span>
              <small>内容 {{ item.deliveryContentLength }} 字 · 文案 {{ item.messageTemplateLength }} 字</small>
              <code>{{ item.requestId }}</code>
            </article>
          </section>
        </div>
        <footer><button class="primary-btn" @click="inspecting = null">完成</button></footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.fixed-template-page { min-height: 100%; padding: 24px; background: #f6f7f9; color: #101828; }
.fixed-template-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.fixed-template-header h1 { margin: 0; font-size: 22px; }
.fixed-template-header p, .template-dialog header p { margin: 6px 0 0; color: #667085; font-size: 13px; }
.fixed-template-actions { display: flex; gap: 10px; }
.template-search { display: flex; min-width: 300px; }
.template-search input { border-radius: 8px 0 0 8px; }
.template-search button { height: 38px; border-left: 0; border-radius: 0 8px 8px 0; }
select, input, textarea { box-sizing: border-box; width: 100%; border: 1px solid #d0d5dd; border-radius: 7px; background: #fff; color: #101828; font: inherit; }
select, input { height: 38px; padding: 0 11px; }
textarea { padding: 10px 11px; line-height: 1.55; resize: vertical; }
.fixed-template-actions select { width: 180px; }
button { border: 1px solid #d0d5dd; border-radius: 6px; background: #fff; color: #344054; cursor: pointer; height: 34px; padding: 0 12px; }
.primary-btn { border-color: #9a6200; background: #9a6200; color: #fff; }
.fixed-template-panel { min-height: 240px; padding: 18px; border: 1px solid #e4e7ec; border-radius: 10px; background: #fff; }
.empty-state { padding: 72px 20px; color: #98a2b3; text-align: center; }
.template-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 12px; }
.template-card { min-width: 0; padding: 15px; border: 1px solid #e4e7ec; border-radius: 8px; }
.template-card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.template-card-header > div { display: flex; gap: 6px; }
.template-card-header .template-card-title { display: grid; gap: 4px; }
.template-card-title span { color: #8a6200; font-size: 11px; font-weight: 650; }
.danger-text { color: #d92d20; }
dl { margin: 14px 0 0; }
dt { margin-top: 10px; color: #667085; font-size: 12px; }
dd { margin: 5px 0 0; color: #344054; font-size: 13px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.template-dialog-mask { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 20px; background: rgba(16, 24, 40, .45); }
.template-dialog { width: min(680px, 100%); max-height: calc(100vh - 40px); overflow: auto; padding: 22px; border-radius: 10px; background: #fff; box-shadow: 0 24px 60px rgba(16, 24, 40, .22); }
.template-dialog header { display: flex; justify-content: space-between; margin-bottom: 18px; }
.template-dialog h2 { margin: 0; font-size: 19px; }
.close-btn { border: 0; font-size: 22px; }
.template-dialog label { display: grid; gap: 7px; margin-top: 14px; color: #344054; font-size: 13px; font-weight: 600; }
.template-label-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.template-label-row > span { display: flex; flex-wrap: wrap; gap: 5px; }
.template-label-row button { height: 27px; padding: 0 8px; color: #9a6200; font-size: 12px; }
.template-field-count { color: #667085; font-size: 12px; font-weight: 400; text-align: right; }
.template-preview { margin-top: 14px; padding: 12px; border: 1px solid #e4e7ec; border-radius: 7px; background: #f9fafb; }
.template-preview strong { font-size: 12px; }
.template-preview p { margin: 7px 0 0; color: #344054; font-size: 13px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.template-preview small { display: block; margin-top: 8px; color: #667085; text-align: right; }
.template-preview--invalid { border-color: #f04438; background: #fff5f4; }
.template-preview--invalid small { color: #d92d20; }
.template-dialog footer { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.template-evidence-dialog { width: min(920px, 100%); max-height: calc(100dvh - 40px); overflow: auto; padding: 22px; border-radius: 14px; background: #fff; box-shadow: 0 24px 60px rgba(16,24,40,.22); }
.template-evidence-dialog > header { display: flex; justify-content: space-between; gap: 18px; }
.template-evidence-dialog h2 { margin: 4px 0 0; font-size: 20px; }
.template-evidence-dialog header p { margin: 6px 0 0; color: #667085; font-size: 12px; }
.evidence-kicker { color: #9a6200; font-size: 11px; font-weight: 800; letter-spacing: .08em; }
.template-evidence-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 18px; }
.template-evidence-grid > section { min-width: 0; padding: 14px; border: 1px solid #e4e7ec; border-radius: 10px; background: #fafaf8; }
.template-evidence-grid > section > header { display: flex; justify-content: space-between; margin-bottom: 10px; }
.template-evidence-grid > section > header span { color: #9a6200; font-weight: 750; }
.evidence-empty { padding: 28px 10px; color: #98a2b3; text-align: center; font-size: 12px; }
.evidence-row { display: grid; gap: 4px; padding: 10px 0; border-top: 1px solid #eceef1; }
.evidence-row:first-of-type { border-top: 0; }
.evidence-row span, .evidence-row small { color: #667085; font-size: 11px; }
.evidence-row code { overflow: hidden; color: #8a6200; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.template-evidence-dialog > footer { display: flex; justify-content: flex-end; margin-top: 18px; }
@media (max-width: 680px) {
  .fixed-template-page { padding: 14px; }
  .fixed-template-header { flex-direction: column; }
  .fixed-template-actions { width: 100%; flex-wrap: wrap; }
  .fixed-template-actions select, .template-search { width: 100%; min-width: 0; }
  .template-list { grid-template-columns: 1fr; }
  .template-label-row { align-items: flex-start; flex-direction: column; }
  .template-dialog-mask { align-items: end; padding: 0; }
  .template-dialog, .template-evidence-dialog { width: 100%; max-height: 92dvh; border-radius: 18px 18px 0 0; padding: 18px 16px max(18px, env(safe-area-inset-bottom)); }
  .template-dialog > header {
    position: sticky;
    top: -18px;
    z-index: 2;
    margin: -18px -16px 18px;
    padding: 18px 16px 12px;
    border-bottom: 1px solid #eef0eb;
    background: #fff;
  }
  .template-dialog > footer {
    position: sticky;
    bottom: calc(-1 * max(18px, env(safe-area-inset-bottom)));
    z-index: 2;
    margin: 18px -16px calc(-1 * max(18px, env(safe-area-inset-bottom)));
    padding: 12px 16px max(18px, env(safe-area-inset-bottom));
    border-top: 1px solid #eef0eb;
    background: rgba(255, 255, 255, .96);
    box-shadow: 0 -8px 20px rgba(16, 24, 40, .06);
    backdrop-filter: blur(8px);
  }
  .template-dialog > footer button { min-height: 44px; flex: 1; }
  .template-evidence-grid { grid-template-columns: 1fr; }
}
</style>
