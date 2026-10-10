<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { getAccountLifecycle, normalizeAccountId, updateAccount, type AccountLocalProfile } from '@/api/account'
import { getAccountGroups, newRequestId, type AccountGroup } from '@/api/matrix'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap'
import { showSuccess } from '@/utils'
import type { Account } from '@/types'
const props = defineProps<{ modelValue: boolean; account?: Account | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'success'): void }>()
const modal = ref<HTMLElement | null>(null), loading = ref(false), busy = ref(false), error = ref('')
const profile = ref<AccountLocalProfile | null>(null), groups = ref<AccountGroup[]>([])
const form = ref({ accountNote: '', groupIds: [] as number[], tags: '', autoReplyAllowed: true, autoDeliveryAllowed: true })
let loadSequence = 0, requestId = '', submittedPayload = ''
const close = () => { if (!busy.value) emit('update:modelValue', false) }
useModalFocusTrap(computed(() => props.modelValue), modal, close)
async function load() {
 const id = props.account?.id, sequence = ++loadSequence
 if (!id || !props.modelValue) return
 loading.value = true; error.value = ''; profile.value = null; requestId = ''; submittedPayload = ''
 try {
  const [p, g] = await Promise.all([getAccountLifecycle(id), getAccountGroups()])
  if (sequence !== loadSequence || !props.modelValue) return
  if (!p.data) throw new Error(p.msg || '账号信息加载失败')
  profile.value = p.data; groups.value = (g.data || []).map(group => ({ ...group, id: normalizeAccountId(group.id) }))
  form.value = { accountNote: p.data.accountNote || '', groupIds: [...p.data.groupIds], tags: p.data.operatingTags.join('，'), autoReplyAllowed: p.data.autoReplyAllowed, autoDeliveryAllowed: p.data.autoDeliveryAllowed }
 } catch (e: any) { if (sequence === loadSequence) error.value = e.message || '加载失败，请重试' }
 finally { if (sequence === loadSequence) loading.value = false }
}
watch(() => [props.modelValue, props.account?.id], () => { if (props.modelValue) void load(); else ++loadSequence }, { immediate: true })
async function submit() {
 const p = profile.value
 if (!p?.canEdit || busy.value || loading.value) return
 const payload = { accountId: p.accountId, expectedVersion: p.rowVersion, accountNote: form.value.accountNote.trim(), groupIds: [...form.value.groupIds].sort((a,b) => a-b), operatingTags: [...new Set(form.value.tags.split(/[,，]/).map(s => s.trim()).filter(Boolean))].sort(), autoReplyAllowed: form.value.autoReplyAllowed, autoDeliveryAllowed: form.value.autoDeliveryAllowed }
 const serialized = JSON.stringify(payload)
 if (serialized !== submittedPayload) { requestId = newRequestId('account-edit'); submittedPayload = serialized }
 busy.value = true; error.value = ''
 try {
  const response = await updateAccount({ ...payload, requestId })
  if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '保存失败')
  showSuccess('账号本地设置已保存'); emit('success'); emit('update:modelValue', false)
 } catch (e: any) { error.value = e.message || '保存失败；输入已保留，可重试。若账号已被其他人修改，请取消后重新打开。' }
 finally { busy.value = false }
}
</script>
<template>
 <teleport to="body"><div v-if="modelValue" class="modal-overlay" @click.self="close">
  <section ref="modal" class="modal" role="dialog" aria-modal="true" aria-labelledby="account-edit-title" tabindex="-1" :aria-busy="loading || busy">
   <header class="modal-header"><h2 id="account-edit-title" class="modal-title">编辑账号本地设置</h2></header>
   <form @submit.prevent="submit">
    <div class="modal-body">
     <p v-if="loading" role="status">正在加载账号设置…</p>
     <p v-if="error" role="alert" class="form-error">{{ error }}</p>
     <button v-if="!profile && !loading" type="button" @click="load">重新加载</button>
     <fieldset v-if="profile" :disabled="busy || !profile.canEdit">
      <p v-if="!profile.canEdit">当前账号为只读，不能修改设置。</p>
      <label>账号备注<input v-model="form.accountNote" maxlength="100" class="modal-input" placeholder="可留空" /></label>
      <label>运营标签<input v-model="form.tags" class="modal-input" placeholder="用逗号分隔，最多20个" /></label>
      <div><span>分组</span><label v-for="group in groups" :key="group.id" class="check-row"><input v-model="form.groupIds" type="checkbox" :value="group.id" />{{ group.groupName }}</label><p v-if="!groups.length">暂无分组</p></div>
      <label class="check-row"><input v-model="form.autoReplyAllowed" type="checkbox" />允许自动回复</label>
      <label class="check-row"><input v-model="form.autoDeliveryAllowed" type="checkbox" />允许自动发货</label>
      <p class="form-help">这些设置保存在本系统。账号停用或归档时，自动化仍保持暂停。</p>
     </fieldset>
    </div>
    <footer class="modal-footer"><button type="button" class="modal-btn modal-btn-cancel" :disabled="busy" @click="close">取消</button><div class="modal-divider" /><button class="modal-btn modal-btn-primary" :disabled="loading || busy || !profile?.canEdit">{{ busy ? '正在保存…' : '保存' }}</button></footer>
   </form>
  </section>
 </div></teleport>
</template>
<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.25);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  animation: fadeIn 0.2s ease;
}

.modal {
  width: min(480px, calc(100vw - 32px));
  max-height: calc(100dvh - 32px);
  overflow-y: auto;
  border-radius: 20px;
  background: #fff;
  backdrop-filter: blur(30px);
  -webkit-backdrop-filter: blur(30px);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  overflow-y: auto;
  animation: scaleIn 0.2s ease;
}

.modal-header {
  padding: 16px;
  text-align: center;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.1);
}

.modal-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: #000;
  line-height: 1.2;
}

.modal-body {
  padding: 20px 16px;
}

.modal-input {
  width: 100%;
  height: 42px;
  border-radius: 12px;
  border: none;
  padding: 0 12px;
  font-size: 15px;
  background: rgba(0, 0, 0, 0.05);
  color: #000;
  outline: none;
  transition: all 0.2s ease;
  box-sizing: border-box;
}

.modal-input:hover {
  background: rgba(0, 0, 0, 0.08);
}

.modal-input:focus {
  background: rgba(0, 0, 0, 0.08);
}

.modal-input::placeholder {
  color: #999;
}

.modal-footer {
  display: flex;
  height: 48px;
  border-top: 0.5px solid rgba(0, 0, 0, 0.1);
}

.modal-btn {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s;
  -webkit-tap-highlight-color: transparent;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-btn:active {
  opacity: 0.5;
}

.modal-btn-cancel {
  color: #666;
}

.modal-btn-primary {
  color: #007aff;
  font-weight: 500;
}

.modal-divider {
  width: 0.5px;
  background: rgba(0, 0, 0, 0.1);
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes scaleIn {
  from {
    transform: scale(0.9);
    opacity: 0;
  }
  to {
    transform: scale(1);
    opacity: 1;
  }
}
fieldset { border: 0; padding: 0; margin: 0; display: grid; gap: 16px; }
label { display: grid; gap: 6px; }
.check-row { display: flex; gap: 8px; align-items: center; margin-top: 8px; }
.form-help { font-size: 13px; color: #666; line-height: 1.5; }
.form-error { color: #b42318; }
button:disabled { opacity: .5; cursor: default; }
input:focus-visible, button:focus-visible { outline: 2px solid #9c7500; outline-offset: 2px; }
</style>
