<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { deleteAccount, getAccountLifecycle, getAccountDeletePreflight, type AccountLocalProfile, type DeletePreflight } from '@/api/account'
import { newRequestId } from '@/api/matrix'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap'
import { showSuccess } from '@/utils'
const props = defineProps<{ modelValue: boolean; accountId?: number | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'success'): void }>()
const loading=ref(false), busy=ref(false), error=ref(''), idText=ref(''), nameText=ref(''), expectedName=ref('')
const profile=ref<AccountLocalProfile|null>(null), preflight=ref<DeletePreflight|null>(null), modal=ref<HTMLElement|null>(null)
let sequence=0, requestId=''
const close=()=>{if(!busy.value)emit('update:modelValue',false)}
useModalFocusTrap(computed(()=>props.modelValue),modal,close)
const canSubmit=computed(()=>!loading.value&&!busy.value&&profile.value?.canDelete&&preflight.value?.canDelete&&idText.value.trim()===String(props.accountId)&&nameText.value.trim()===expectedName.value&&expectedName.value.length>0)
async function load(){
 const id=props.accountId, run=++sequence
 if(!id||!props.modelValue)return
 loading.value=true;error.value='';profile.value=null;preflight.value=null;idText.value='';nameText.value='';requestId=newRequestId('account-delete')
 try{
  const p=await getAccountLifecycle(id)
  if(!p.data)throw new Error(p.msg||'账号信息加载失败')
  if(run!==sequence||!props.modelValue)return
  profile.value=p.data
  if(!p.data.canDelete)return
  const d=await getAccountDeletePreflight(id)
  if(run!==sequence||!props.modelValue)return
  if(!d.data)throw new Error(d.msg||'删除条件检查失败')
  preflight.value=d.data
  expectedName.value=d.data.confirmationLabel
 }catch(e:any){if(run===sequence)error.value=e.message||'检查失败，请重试'}
 finally{if(run===sequence)loading.value=false}
}
watch(()=>[props.modelValue,props.accountId],()=>{if(props.modelValue)void load();else ++sequence},{immediate:true})
async function confirm(){
 if(!canSubmit.value||!profile.value||!props.accountId)return
 busy.value=true;error.value=''
 try{
  const r=await deleteAccount({id:props.accountId,confirmationText:idText.value.trim(),confirmationNote:nameText.value.trim(),expectedVersion:profile.value.rowVersion,requestId})
  if(r.code!==0&&r.code!==200)throw new Error(r.msg||'删除失败')
  showSuccess('本系统空账号已删除');emit('success');emit('update:modelValue',false)
 }catch(e:any){error.value=e.message||'删除失败，确认内容已保留。账号状态已变化时，请关闭后重新检查。'}
 finally{busy.value=false}
}
</script>
<template><teleport to="body"><div v-if="modelValue" class="modal-overlay" @click.self="close"><section ref="modal" class="modal" role="dialog" aria-modal="true" aria-labelledby="account-delete-title" tabindex="-1" :aria-busy="loading||busy">
<header class="modal-header"><h2 id="account-delete-title" class="modal-title">从系统删除空账号</h2></header>
<div class="modal-body"><p>只删除本系统的空账号和登录信息，不变更闲鱼平台商品。业务历史和审计记录保留。</p>
<p v-if="loading" role="status">正在检查删除条件…</p>
<p v-else-if="profile&&!profile.canDelete">仅有删除权限的租户所有者可以删除账号。</p>
<div v-else-if="preflight&&!preflight.canDelete"><p>该账号存在业务历史或关联配置，不能删除。建议关闭此窗口并归档账号，保留历史记录。</p><ul><li v-for="dependency in preflight.dependencies" :key="dependency.dependency"><a :href="dependency.path">{{ dependency.label }}（{{ dependency.count }}）</a></li></ul></div>
<form v-else-if="preflight?.canDelete" @submit.prevent="confirm"><p>账号 ID：{{ accountId }}；确认名称：{{ expectedName }}</p><label>输入账号 ID<input v-model="idText" class="modal-input" inputmode="numeric" autocomplete="off" :disabled="busy" /></label><label>输入账号备注（无备注时输入闲鱼 ID）<input v-model="nameText" class="modal-input" autocomplete="off" :disabled="busy" /></label></form>
<p v-if="busy" role="status">正在删除，请勿重复提交…</p><p v-if="error" role="alert">{{ error }}</p><button v-if="error&&!profile" @click="load">重新检查</button></div>
<footer class="modal-footer"><button class="modal-btn modal-btn-cancel" :disabled="busy" @click="close">取消</button><div class="modal-divider"/><button class="modal-btn modal-btn-danger" :disabled="!canSubmit" @click="confirm">{{ busy?'正在删除…':'删除空账号' }}</button></footer>
</section></div></teleport></template>
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
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(30px);
  -webkit-backdrop-filter: blur(30px);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  overflow: hidden;
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
  text-align: center;
}

.delete-title {
  margin: 0 0 8px 0;
  font-size: 17px;
  font-weight: 600;
  color: #000;
  line-height: 1.2;
}

.delete-desc {
  margin: 0 0 8px 0;
  font-size: 13px;
  color: #666;
  line-height: 1.4;
}

.delete-list {
  margin: 0;
  padding: 0 0 0 16px;
  list-style: none;
  text-align: left;
  font-size: 13px;
  color: #666;
}

.delete-list li {
  margin: 4px 0;
  line-height: 1.4;
}

.delete-list li::before {
  content: "•";
  color: #ff3b30;
  margin-right: 8px;
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

.modal-btn:active:not(:disabled) {
  opacity: 0.5;
}

.modal-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.modal-btn-cancel {
  color: #666;
}

.modal-btn-danger {
  color: #ff3b30;
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
label { display: grid; gap: 8px; margin-top: 16px; }
.modal-input { width: 100%; padding: 10px; box-sizing: border-box; }
button:disabled { opacity: .5; cursor: default; }
input:focus-visible, button:focus-visible { outline: 2px solid #9c7500; outline-offset: 2px; }
</style>
