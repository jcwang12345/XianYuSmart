<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  createAccountBatch, getAccountBatch, newRequestId, previewAccountBatch,
  type AccountBatchOperation, type AccountBatchPreview, type AccountBatchRequest,
  type AccountBatchResult, type AccountBatchSelectionMode,
} from '@/api/matrix'
import { showError, showSuccess } from '@/utils'

const props=defineProps<{
  modelValue:boolean
  selectionMode:AccountBatchSelectionMode
  accountIds:number[]
  excludedAccountIds:number[]
  filter:{search?:string;connectionStatus?:string;riskSeverity?:string;groupId?:number}
}>()
const emit=defineEmits<{(event:'update:modelValue',value:boolean):void;(event:'completed'):void}>()
const operation=ref<AccountBatchOperation>('SYNC')
const preview=ref<AccountBatchPreview|null>(null)
const batch=ref<AccountBatchResult|null>(null)
const loading=ref(false),creating=ref(false),confirmed=ref(false)
const requestId=ref('')
let pollTimer:number|undefined
const labels:Record<AccountBatchOperation,string>={ENABLE:'批量启用',DISABLE:'批量停用',SYNC:'同步运行状态',RENEW:'批量准备续期'}
const terminal=new Set(['SUCCEEDED','FAILED','PARTIAL_SUCCESS','CANCELLED'])
const canCreate=computed(()=>!!preview.value&&preview.value.executableCount>0&&confirmed.value&&!creating.value&&!batch.value)
const statusText=(status:number)=>({0:'排队中',1:'执行中',2:'成功',3:'已取消',4:'结果未知','-1':'失败'}[String(status)]||'未知')
const close=()=>{if(!creating.value)emit('update:modelValue',false)}
const payload=():AccountBatchRequest=>({requestId:requestId.value,operationType:operation.value,selectionMode:props.selectionMode,accountIds:props.accountIds,excludedAccountIds:props.excludedAccountIds,filter:props.filter})
const runPreview=async()=>{loading.value=true;preview.value=null;batch.value=null;confirmed.value=false;requestId.value=newRequestId('account-batch');try{const result=await previewAccountBatch(payload());preview.value=result.data||null}catch(error:any){showError(error.message||'账号范围预检失败')}finally{loading.value=false}}
const poll=async()=>{if(!batch.value||terminal.has(batch.value.status))return;try{const result=await getAccountBatch(batch.value.batchId);if(result.data)batch.value=result.data;if(batch.value&&terminal.has(batch.value.status)){emit('completed');return}}catch(error:any){showError(error.message||'任务状态读取失败');return}pollTimer=window.setTimeout(poll,1200)}
const create=async()=>{if(!preview.value||!canCreate.value)return;creating.value=true;try{const result=await createAccountBatch({...payload(),confirmationText:preview.value.confirmationSummary,previewToken:preview.value.previewToken});batch.value=result.data||null;showSuccess(result.data?.idempotentReplay?'已返回同一批任务':'账号批量任务已创建');void poll()}catch(error:any){showError(error.message||'账号批量任务创建失败')}finally{creating.value=false}}
watch(()=>props.modelValue,value=>{if(value)void runPreview();else{if(pollTimer)window.clearTimeout(pollTimer);pollTimer=undefined}},{immediate:true})
watch(operation,()=>{if(props.modelValue)void runPreview()})
onBeforeUnmount(()=>{if(pollTimer)window.clearTimeout(pollTimer)})
</script>

<template>
  <div v-if="modelValue" class="account-batch-backdrop" @click.self="close">
    <section class="account-batch-dialog" role="dialog" aria-modal="true" aria-labelledby="account-batch-title">
      <header><div><p>多账号批量运营</p><h2 id="account-batch-title">{{ labels[operation] }}</h2><small>先预检真实范围，再按账号串行执行并保留逐项结果。</small></div><button aria-label="关闭" @click="close">×</button></header>
      <div class="account-batch-dialog__body">
        <section class="account-batch-options"><label>执行动作<select v-model="operation" :disabled="creating||!!batch"><option value="SYNC">同步运行状态</option><option value="ENABLE">启用账号</option><option value="DISABLE">停用账号</option><option value="RENEW">准备扫码续期</option></select></label><div><span>选择范围</span><strong>{{ selectionMode==='FILTER_SNAPSHOT'?'当前筛选快照':'手动勾选账号' }}</strong><small>{{ selectionMode==='FILTER_SNAPSHOT'?`排除 ${excludedAccountIds.length} 个账号`:`已勾选 ${accountIds.length} 个账号` }}</small></div></section>
        <div v-if="loading" class="account-batch-state">正在预检账号状态与权限…</div>
        <template v-else-if="preview">
          <section class="account-batch-summary"><div><span>选中</span><strong>{{ preview.selectedCount }}</strong></div><div><span>可执行</span><strong>{{ preview.executableCount }}</strong></div><div><span>冲突</span><strong>{{ preview.conflictCount }}</strong></div><div><span>通道</span><strong>{{ preview.executionChannel==='QA_MOCK'?'隔离 QA':'本机运行时' }}</strong></div></section>
          <p class="account-batch-confirmation">{{ preview.confirmationSummary }}</p>
          <p v-if="preview.executionNotice" class="account-batch-notice">{{ preview.executionNotice }}</p>
          <div class="account-batch-items"><article v-for="item in preview.items" :key="item.accountId" :class="{conflict:!item.executable}"><div><strong>{{ item.accountName }}</strong><small>ID {{ item.accountId }} · 连接 {{ item.connectionStatus||'未知' }} · 授权 {{ item.authorizationStatus||'未知' }}</small></div><span>{{ item.executable?'可执行':item.conflictMessage }}</span></article></div>
          <label v-if="!batch" class="account-batch-consent"><input v-model="confirmed" type="checkbox"><span>我已核对账号范围和动作。停用会暂停该账号的消息监听、自动回复与自动发货；续期仍需对应账号扫码。</span></label>
        </template>
        <section v-if="batch" class="account-batch-result"><header><div><span>批次 {{ batch.batchId }}</span><strong>{{ batch.status }}</strong></div><small>成功 {{ batch.succeededCount }} · 失败 {{ batch.failedCount }} · 排队/执行 {{ batch.queuedCount+batch.runningCount }}</small></header><div><article v-for="item in batch.items" :key="item.id"><span>账号 {{ item.xianyuAccountId }}</span><strong>{{ statusText(item.status) }}</strong><small>{{ item.errorMessage||'' }}</small></article></div></section>
      </div>
      <footer><button class="workbench__btn" @click="close">{{ batch&&terminal.has(batch.status)?'完成':'取消' }}</button><button v-if="!batch" class="workbench__btn workbench__btn--primary" :disabled="!canCreate" @click="create">{{ creating?'创建中…':'创建批量任务' }}</button></footer>
    </section>
  </div>
</template>
<style scoped src="./AccountBatchDialog.css"></style>
