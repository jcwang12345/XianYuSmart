<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { getAccountLifecycle, changeAccountLifecycle, type AccountLocalProfile } from '@/api/account'
import { newRequestId } from '@/api/matrix'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap'
const props=defineProps<{modelValue:boolean;accountId?:number}>()
const emit=defineEmits<{(e:'update:modelValue',value:boolean):void;(e:'success'):void}>()
const profile=ref<AccountLocalProfile|null>(null),loading=ref(false),busy=ref(false),error=ref(''),outcome=ref(''),modal=ref<HTMLElement|null>(null)
let sequence=0,requestId='',requestedAction=''
const names={ACTIVE:'启用',DISABLED:'停用',ARCHIVED:'归档'}
const outcomeLabel:Record<string,string>={CONNECTED:'连接已恢复',DISCONNECTED:'本地状态已恢复，当前未连接；请在连接管理检查凭据',VERIFICATION_REQUIRED:'本地状态已恢复，官方验证仍需在连接管理完成',STOPPED:'连接已停止',STOP_PENDING:'本地门禁已生效，连接停止待核对',STOP_FAILED:'本地门禁已生效，连接停止失败，请在连接管理核对',CONNECTION_CHECK_PENDING:'本地状态已恢复，连接待核对',NO_CHANGE:'账号状态没有变化'}
const close=()=>{if(!busy.value)emit('update:modelValue',false)}
useModalFocusTrap(computed(()=>props.modelValue),modal,close)
watch(()=>[props.modelValue,props.accountId],async()=>{const run=++sequence;if(!props.modelValue||!props.accountId)return;loading.value=true;profile.value=null;error.value='';outcome.value='';requestId='';requestedAction='';try{const r=await getAccountLifecycle(props.accountId);if(run!==sequence)return;if(!r.data)throw new Error(r.msg||'加载失败');profile.value=r.data}catch(e:any){if(run===sequence)error.value=e.message||'加载失败'}finally{if(run===sequence)loading.value=false}},{immediate:true})
async function apply(action:'DISABLE'|'ENABLE'|'ARCHIVE'|'RESTORE'){
 const p=profile.value;if(!p?.canManageLifecycle||busy.value)return
 if(action!==requestedAction){requestId=newRequestId('account-state');requestedAction=action}
 busy.value=true;error.value='';outcome.value=''
 try{const r=await changeAccountLifecycle({accountId:p.accountId,expectedVersion:p.rowVersion,requestId,action});if(!r.data||r.code!==0&&r.code!==200)throw new Error(r.msg||'修改失败');profile.value=r.data.profile||p;outcome.value=outcomeLabel[r.data.connectionOutcome||'']||'本地状态已保存，连接结果待核对';requestId='';requestedAction='';emit('success')}
 catch(e:any){error.value=e.message||'状态修改失败，可重试；版本变化时请关闭后重新打开'}finally{busy.value=false}
}
</script>
<template><teleport to="body"><div v-if="modelValue" class="lifecycle-overlay" @click.self="close"><section ref="modal" class="lifecycle-dialog" role="dialog" aria-modal="true" aria-labelledby="lifecycle-title" tabindex="-1" :aria-busy="busy||loading"><header><h2 id="lifecycle-title">账号本地状态</h2><button :disabled="busy" aria-label="关闭" @click="close">×</button></header><p v-if="loading" role="status">正在读取账号状态…</p><template v-if="profile"><p>账号 {{ profile.accountId }} · {{ names[profile.lifecycleState] }}</p><p>停用和归档会暂停连接、凭据刷新、自动回复与自动发货；业务历史和配置保留。归档后恢复到归档前的状态。官方验证状态不会因本地启用而改变。</p><p v-if="!profile.canManageLifecycle">当前账号为只读，不能修改状态。</p><footer><button v-if="profile.lifecycleState==='ACTIVE'" :disabled="busy||!profile.canManageLifecycle" @click="apply('DISABLE')">停用</button><button v-if="profile.lifecycleState==='DISABLED'" :disabled="busy||!profile.canManageLifecycle" @click="apply('ENABLE')">启用</button><button v-if="profile.lifecycleState!=='ARCHIVED'" :disabled="busy||!profile.canManageLifecycle" @click="apply('ARCHIVE')">归档并保留历史</button><button v-else :disabled="busy||!profile.canManageLifecycle" @click="apply('RESTORE')">恢复归档</button></footer></template><p v-if="busy" role="status">正在保存并核对连接…</p><p v-if="outcome" role="status">{{ outcome }}</p><p v-if="error" role="alert">{{ error }}</p></section></div></teleport></template>
<style scoped>
.lifecycle-overlay{position:fixed;inset:0;z-index:2000;background:#0006;display:flex;align-items:center;justify-content:center;padding:16px}.lifecycle-dialog{width:min(520px,100%);max-height:calc(100dvh - 32px);overflow:auto;background:white;border-radius:16px;padding:24px;box-sizing:border-box;color:#292929}.lifecycle-dialog header{display:flex;align-items:center;justify-content:space-between;gap:16px}.lifecycle-dialog h2{font-size:20px;margin:0}.lifecycle-dialog p{line-height:1.6}.lifecycle-dialog footer{display:flex;flex-wrap:wrap;gap:12px}.lifecycle-dialog button{padding:10px 14px;border:1px solid #ddd;border-radius:8px;background:#fff;cursor:pointer}.lifecycle-dialog button:disabled{opacity:.5;cursor:default}.lifecycle-dialog button:focus-visible{outline:2px solid #9c7500;outline-offset:2px}[role=alert]{color:#b42318}
</style>
