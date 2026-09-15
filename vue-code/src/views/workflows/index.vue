<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap'
import { getAccountList } from '@/api/account'
import {
  activateGrowthWorkflowVersion,
  cancelGrowthWorkflowRun,
  compensateGrowthWorkflowRun,
  createGrowthWorkflowRun,
  getGrowthWorkflow,
  getGrowthWorkflowRun,
  getGrowthWorkflowRuns,
  getGrowthWorkflows,
  preflightGrowthWorkflow,
  resolveUnknownGrowthWorkflowNode,
  retryGrowthWorkflowRun,
  saveGrowthWorkflowVersion,
  type GrowthWorkflow,
  type WorkflowEdge,
  type WorkflowNode,
  type WorkflowRunDetail,
  type WorkflowRunStatus,
  type WorkflowRunSummary
} from '@/api/growth-workspace'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'

type NodeType = WorkflowNode['type']
interface DragState { id: string; offsetX: number; offsetY: number; moved: boolean }

const nodeOptions: Array<{ type: NodeType; label: string }> = [
  { type: 'SEARCH', label: '商机搜索' },
  { type: 'FILTER', label: '机会筛选' },
  { type: 'COLLECT', label: '写入货源' },
  { type: 'MATERIAL', label: '生成素材' },
  { type: 'PUBLISH', label: '发布预检' }
]
const statusOptions: Array<{ value: '' | WorkflowRunStatus; label: string }> = [
  { value: '', label: '全部状态' }, { value: 'QUEUED', label: '排队中' }, { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCEEDED', label: '成功' }, { value: 'PARTIAL', label: '部分成功' }, { value: 'FAILED', label: '失败' },
  { value: 'UNKNOWN', label: '结果未知' }, { value: 'CANCELLED', label: '已取消' }
]

const workflows = ref<GrowthWorkflow[]>([])
const workflowDetail = ref<GrowthWorkflow>()
const accounts = ref<Account[]>([])
const selectedId = ref<number>()
const accountId = ref<number>()
const selectedNodeId = ref('')
const name = ref('商机采集与发布预检')
const changeSummary = ref('')
const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])
const loading = ref(false)
const loaded = ref(false)
const saving = ref(false)
const canvas = ref<HTMLElement>()
const connectingFrom = ref('')
const dragState = ref<DragState>()
const preflight = ref<Record<string, any>>()
const runs = ref<WorkflowRunSummary[]>([])
const runTotal = ref(0)
const runPage = ref(1)
const runStatus = ref<'' | WorkflowRunStatus>('')
const runDetail = ref<WorkflowRunDetail>()
const runDialogOpen = ref(false)
const runDialog = ref<HTMLElement | null>(null)
const actioning = ref(false)
const unknownEvidence = ref('')

const selectedNode = computed(() => nodes.value.find(node => node.id === selectedNodeId.value))
const selectedWorkflow = computed(() => workflows.value.find(item => item.id === selectedId.value))
const activeVersion = computed(() => workflowDetail.value?.versions?.find(version => version.lifecycleState === 'ACTIVE'))
const draftVersion = computed(() => workflowDetail.value?.versions?.find(version => version.lifecycleState === 'DRAFT'))
const pageCount = computed(() => Math.max(1, Math.ceil(runTotal.value / 25)))
const metrics = computed(() => ({
  total: runTotal.value,
  succeeded: runs.value.filter(run => run.status === 'SUCCEEDED').length,
  attention: runs.value.filter(run => ['FAILED', 'PARTIAL', 'UNKNOWN'].includes(run.status)).length,
  running: runs.value.filter(run => ['QUEUED', 'RUNNING'].includes(run.status)).length
}))
const accountName = (id?: number) => accounts.value.find(item => item.id === id)?.accountNote
  || accounts.value.find(item => item.id === id)?.unb || `账号 ${id || '-'}`
const statusLabel = (status?: string) => ({
  QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', PARTIAL: '部分成功',
  FAILED: '失败', CANCELLED: '已取消', UNKNOWN: '结果未知',
  PENDING: '待执行'
} as Record<string, string>)[status || ''] || status || '未知'
const statusClass = (status?: string) => ({
  'workbench__tag--good': status === 'SUCCEEDED',
  'workbench__tag--warn': ['FAILED', 'PARTIAL', 'UNKNOWN'].includes(status || '')
})
const time = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未记录'
const requestId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`.slice(0, 64)
const closeRunDialog = () => { runDialogOpen.value = false }

useModalFocusTrap(runDialogOpen, runDialog, closeRunDialog)

const defaultDefinition = () => {
  nodes.value = [
    { id: 'trigger', type: 'TRIGGER', name: '手动触发', x: 40, y: 220, config: {} },
    { id: 'search', type: 'SEARCH', name: '商机搜索', x: 220, y: 100, config: { keyword: '', limit: 20 } },
    { id: 'filter', type: 'FILTER', name: '机会筛选', x: 410, y: 100, config: { minScore: 60, limit: 10 } },
    { id: 'collect', type: 'COLLECT', name: '写入本地货源', x: 410, y: 300, config: {} },
    { id: 'material', type: 'MATERIAL', name: '生成本地素材', x: 600, y: 300, config: {} },
    { id: 'publish', type: 'PUBLISH', name: '发布结构预检', x: 790, y: 220, config: { dryRun: true } }
  ]
  edges.value = [
    { source: 'trigger', target: 'search' }, { source: 'search', target: 'filter' },
    { source: 'filter', target: 'collect' }, { source: 'collect', target: 'material' },
    { source: 'material', target: 'publish' }
  ]
  selectedNodeId.value = 'trigger'
  connectingFrom.value = ''
}

const loadRuns = async () => {
  const response = await getGrowthWorkflowRuns({
    workflowId: selectedId.value, status: runStatus.value || undefined, pageNumber: runPage.value, pageSize: 25
  })
  runs.value = response.data?.items || []
  runTotal.value = response.data?.total || 0
}

const load = async () => {
  loading.value = true
  try {
    const [workflowResult, accountResult] = await Promise.all([getGrowthWorkflows(), getAccountList()])
    workflows.value = workflowResult.data || []
    accounts.value = accountResult.data?.accounts || []
    accountId.value ||= accounts.value[0]?.id
    if (!selectedId.value && workflows.value.length) await selectWorkflow(workflows.value[0]!)
    if (!nodes.value.length) defaultDefinition()
    await loadRuns()
    loaded.value = true
  } finally {
    loading.value = false
  }
}

const selectWorkflow = async (workflow: GrowthWorkflow) => {
  selectedId.value = workflow.id
  const response = await getGrowthWorkflow(workflow.id)
  workflowDetail.value = response.data
  name.value = response.data?.name || workflow.name
  accountId.value = response.data?.accountId || workflow.accountId
  const version = response.data?.versions?.find(item => item.lifecycleState === 'ACTIVE') || response.data?.versions?.[0]
  nodes.value = (version?.definition?.nodes || []).map((node, index) => ({
    ...node, x: node.x ?? 40 + (index % 4) * 190, y: node.y ?? 80 + Math.floor(index / 4) * 130
  }))
  edges.value = version?.definition?.edges ? [...version.definition.edges] : []
  selectedNodeId.value = nodes.value[0]?.id || ''
  preflight.value = undefined
  runPage.value = 1
  await loadRuns()
}

const newWorkflow = () => {
  selectedId.value = undefined
  workflowDetail.value = undefined
  name.value = '新工作流'
  changeSummary.value = ''
  accountId.value = accounts.value[0]?.id
  preflight.value = undefined
  defaultDefinition()
}

const saveVersion = async () => {
  if (!name.value.trim()) return toast.error('请输入工作流名称')
  if (!accountId.value) return toast.error('请选择执行账号')
  saving.value = true
  try {
    const response = await saveGrowthWorkflowVersion({
      workflowId: selectedId.value,
      name: name.value.trim(),
      accountId: accountId.value,
      status: 1,
      definition: { nodes: nodes.value, edges: edges.value },
      changeSummary: changeSummary.value || '工作流定义更新',
      requestId: requestId('WF-VERSION')
    })
    if (response.data) {
      selectedId.value = response.data.id
      workflowDetail.value = response.data
    }
    toast.success('不可变草稿版本已保存')
    await refreshWorkflowList()
  } finally {
    saving.value = false
  }
}

const refreshWorkflowList = async () => {
  workflows.value = (await getGrowthWorkflows()).data || []
}

const activateVersion = async (version: number) => {
  if (!selectedId.value) return
  const response = await activateGrowthWorkflowVersion(selectedId.value, version, requestId('WF-ACTIVATE'))
  workflowDetail.value = response.data
  preflight.value = undefined
  toast.success(`v${version} 已启用`)
  await refreshWorkflowList()
}

const runPreflight = async () => {
  if (!selectedId.value || !accountId.value) return toast.error('请先保存并选择工作流')
  const version = activeVersion.value?.version
  if (!version) return toast.error('请先启用一个工作流版本')
  const response = await preflightGrowthWorkflow(selectedId.value, {
    version, accountId: accountId.value, executionMode: 'DRY_RUN'
  })
  preflight.value = response.data
  if (response.data?.valid) toast.success('预检通过：不会执行平台写入')
}

const createRun = async () => {
  if (!selectedId.value || !accountId.value || !activeVersion.value) return toast.error('请先启用工作流版本')
  if (!preflight.value?.valid) await runPreflight()
  if (!preflight.value?.valid) return
  loading.value = true
  try {
    const response = await createGrowthWorkflowRun({
      workflowId: selectedId.value, version: activeVersion.value.version, accountId: accountId.value,
      executionMode: 'DRY_RUN', input: { source: 'WORKFLOW_UI' }, requestId: requestId('WF-RUN')
    })
    toast.success('运行已进入持久化队列')
    await loadRuns()
    if (response.data) await openRun(response.data.id)
  } finally {
    loading.value = false
  }
}

const openRun = async (runId: number) => {
  runDialogOpen.value = true
  runDetail.value = undefined
  runDetail.value = (await getGrowthWorkflowRun(runId)).data
}

const performAction = async (action: 'cancel' | 'retry' | 'compensate') => {
  if (!runDetail.value || actioning.value) return
  actioning.value = true
  try {
    const id = runDetail.value.id
    const response = action === 'cancel'
      ? await cancelGrowthWorkflowRun(id, requestId('WF-CANCEL'))
      : action === 'retry'
        ? await retryGrowthWorkflowRun(id, requestId('WF-RETRY'), runDetail.value.nodes.filter(node => node.status === 'FAILED').map(node => node.nodeId))
        : await compensateGrowthWorkflowRun(id, requestId('WF-COMPENSATE'))
    runDetail.value = response.data
    toast.success(action === 'cancel' ? '已在节点边界安全取消' : action === 'retry' ? '失败节点已重新排队' : '本地派生资源已安全停用')
    await loadRuns()
  } finally {
    actioning.value = false
  }
}

const resolveUnknown = async (nodeId: string, resolution: 'SUCCEEDED' | 'FAILED') => {
  if (!runDetail.value || !unknownEvidence.value.trim()) return toast.error('请先填写平台回执或人工核对证据')
  actioning.value = true
  try {
    const response = await resolveUnknownGrowthWorkflowNode(runDetail.value.id, nodeId, {
      resolution, evidence: { note: unknownEvidence.value.trim(), checkedAt: new Date().toISOString() },
      requestId: requestId('WF-RESOLVE')
    })
    runDetail.value = response.data
    unknownEvidence.value = ''
    toast.success('结果未知节点已人工核对')
    await loadRuns()
  } finally {
    actioning.value = false
  }
}

const addNode = (type: NodeType) => {
  if (nodes.value.some(node => node.type === type)) return toast.error('当前单路径模型中，同一种业务节点只能添加一次')
  const option = nodeOptions.find(item => item.type === type)
  const id = `${type.toLowerCase()}-${Date.now()}`
  nodes.value.push({
    id, type, name: option?.label || type,
    x: 230 + (nodes.value.length % 3) * 190, y: 80 + Math.floor(nodes.value.length / 3) * 130,
    config: type === 'SEARCH' ? { keyword: '', limit: 20 }
      : type === 'FILTER' ? { minScore: 60, limit: 10 }
        : type === 'PUBLISH' ? { dryRun: true } : {}
  })
  selectedNodeId.value = id
}

const removeSelectedNode = () => {
  const node = selectedNode.value
  if (!node || node.type === 'TRIGGER') return
  nodes.value = nodes.value.filter(item => item.id !== node.id)
  edges.value = edges.value.filter(edge => edge.source !== node.id && edge.target !== node.id)
  selectedNodeId.value = 'trigger'
}
const beginConnect = () => { if (selectedNode.value) connectingFrom.value = selectedNode.value.id }
const selectOrConnect = (node: WorkflowNode) => {
  if (dragState.value?.moved) return
  if (connectingFrom.value) {
    const source = connectingFrom.value
    connectingFrom.value = ''
    if (source !== node.id && !edges.value.some(edge => edge.source === source && edge.target === node.id)) edges.value.push({ source, target: node.id })
  }
  selectedNodeId.value = node.id
}
const removeEdge = (edge: WorkflowEdge) => { edges.value = edges.value.filter(item => item !== edge) }
const beginDrag = (event: PointerEvent, node: WorkflowNode) => {
  if (!canvas.value || event.button !== 0) return
  const rect = canvas.value.getBoundingClientRect()
  selectedNodeId.value = node.id
  dragState.value = {
    id: node.id, moved: false,
    offsetX: event.clientX - rect.left + canvas.value.scrollLeft - (node.x || 0),
    offsetY: event.clientY - rect.top + canvas.value.scrollTop - (node.y || 0)
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}
const drag = (event: PointerEvent) => {
  if (!canvas.value || !dragState.value) return
  const node = nodes.value.find(item => item.id === dragState.value?.id)
  if (!node) return
  const rect = canvas.value.getBoundingClientRect()
  node.x = Math.max(8, Math.min(1040, event.clientX - rect.left + canvas.value.scrollLeft - dragState.value.offsetX))
  node.y = Math.max(8, Math.min(540, event.clientY - rect.top + canvas.value.scrollTop - dragState.value.offsetY))
  dragState.value.moved = true
}
const endDrag = () => { setTimeout(() => { dragState.value = undefined }, 0) }
const line = (edge: WorkflowEdge) => {
  const source = nodes.value.find(node => node.id === edge.source)
  const target = nodes.value.find(node => node.id === edge.target)
  return source && target ? { x1: (source.x || 0) + 145, y1: (source.y || 0) + 40, x2: target.x || 0, y2: (target.y || 0) + 40 } : undefined
}

onMounted(load)
</script>

<template>
  <section class="workbench workflow-page">
    <header class="workbench__header">
      <div><h1>自动化工作流</h1><p>定义版本、预检、逐节点状态、失败重试、安全取消、人工核对与补偿都保留证据。</p></div>
      <div class="workbench__actions"><button class="workbench__btn" :disabled="saving" @click="saveVersion">{{ saving ? '保存中…' : '保存草稿版本' }}</button><button class="workbench__btn" :disabled="!draftVersion" @click="draftVersion && activateVersion(draftVersion.version)">启用最新草稿</button><button class="workbench__btn workbench__btn--primary" :disabled="loading || !activeVersion" @click="createRun">运行仅校验</button></div>
    </header>
    <div class="workflow-boundary"><strong>平台写入：关闭</strong><span>当前运行仅支持 DRY_RUN / 隔离 QA。发布节点只做结构预检，不会新增、上下架、改价或删除商品。</span></div>

    <div class="workbench__grid workflow-metrics" :aria-busy="loading && !loaded">
      <article class="workbench__card workbench__metric"><span>工作流</span><strong>{{ loaded ? workflows.length : '—' }}</strong></article>
      <article class="workbench__card workbench__metric"><span>筛选结果</span><strong>{{ loaded ? metrics.total : '—' }}</strong></article>
      <article class="workbench__card workbench__metric"><span>当前页成功</span><strong>{{ loaded ? metrics.succeeded : '—' }}</strong></article>
      <article class="workbench__card workbench__metric"><span>当前页待处理</span><strong>{{ loaded ? metrics.attention + metrics.running : '—' }}</strong></article>
    </div>

    <div class="workflow-layout workbench__section">
      <aside class="workbench__card workflow-list">
        <div class="workflow-list__editor"><input v-model="name" class="workbench__input" aria-label="工作流名称" placeholder="工作流名称"><select v-model="accountId" class="workbench__select" aria-label="执行账号"><option :value="undefined">选择执行账号</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option></select><input v-model="changeSummary" class="workbench__input" aria-label="版本变更说明" placeholder="本版变更说明"></div>
        <div class="workflow-list__saved"><button v-for="workflow in workflows" :key="workflow.id" :class="{ active: selectedId === workflow.id }" @click="selectWorkflow(workflow)"><strong>{{ workflow.name }}</strong><span>{{ accountName(workflow.accountId) }}</span><small>{{ workflow.currentVersion ? `v${workflow.currentVersion.version} · ${workflow.currentVersion.lifecycleState}` : '未建立版本' }} · {{ workflow.runCount ?? 0 }} 次运行</small></button></div>
        <button class="workbench__btn workbench__btn--primary" @click="newWorkflow">新建工作流</button>
      </aside>

      <main class="workflow-main">
        <div class="workbench__card workflow-palette"><span>节点</span><button v-for="option in nodeOptions" :key="option.type" class="workbench__btn" @click="addNode(option.type)">+ {{ option.label }}</button><em v-if="connectingFrom">请选择下一个节点</em></div>
        <div ref="canvas" class="workbench__card workflow-canvas" @pointermove="drag" @pointerup="endDrag" @pointercancel="endDrag">
          <div class="workflow-surface"><svg><defs><marker id="growth-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" /></marker></defs><line v-for="edge in edges" :key="`${edge.source}-${edge.target}`" v-bind="line(edge)" marker-end="url(#growth-arrow)" /></svg>
            <button v-for="node in nodes" :key="node.id" class="workflow-node" :class="{ active: selectedNodeId === node.id, source: connectingFrom === node.id }" :style="{ left: `${node.x}px`, top: `${node.y}px` }" @pointerdown="beginDrag($event,node)" @click="selectOrConnect(node)"><span>{{ node.type }}</span><strong>{{ node.name }}</strong><small>{{ node.type === 'PUBLISH' ? '仅结构预检' : node.type === 'SEARCH' ? '只读 / 本地夹具' : '仅本地写入' }}</small></button>
          </div>
        </div>
      </main>

      <aside class="workbench__card workflow-config">
        <template v-if="selectedNode"><div class="workflow-config__title"><h2>节点配置</h2><button v-if="selectedNode.type !== 'TRIGGER'" @click="removeSelectedNode">删除</button></div><label class="workbench__field">节点名称<input v-model="selectedNode.name" class="workbench__input"></label>
          <template v-if="selectedNode.type === 'SEARCH'"><label class="workbench__field">搜索关键词<input v-model="selectedNode.config.keyword" class="workbench__input"></label><label class="workbench__field">样本上限<input v-model.number="selectedNode.config.limit" class="workbench__input" type="number" min="1" max="50"></label></template>
          <template v-if="selectedNode.type === 'FILTER'"><label class="workbench__field">最低机会分<input v-model.number="selectedNode.config.minScore" class="workbench__input" type="number" min="0" max="100"></label><label class="workbench__field">保留上限<input v-model.number="selectedNode.config.limit" class="workbench__input" type="number" min="1" max="50"></label></template>
          <label v-if="selectedNode.type === 'PUBLISH'" class="workflow-switch"><span><strong>仅结构预检</strong><small>后端强制开启，不能执行真实发布</small></span><input v-model="selectedNode.config.dryRun" type="checkbox" disabled></label>
          <button class="workbench__btn workbench__btn--primary" @click="beginConnect">连接下一个节点</button>
          <div class="workflow-edges"><strong>相关连线</strong><div v-for="edge in edges.filter(item => item.source === selectedNode?.id || item.target === selectedNode?.id)" :key="`${edge.source}-${edge.target}`"><span>{{ edge.source }} → {{ edge.target }}</span><button @click="removeEdge(edge)">移除</button></div></div>
        </template>
      </aside>
    </div>

    <section v-if="workflowDetail" class="workbench__card workflow-versions">
      <header><div><h2>定义版本</h2><p>已启用版本用于新运行；历史运行始终引用创建时的不可变版本。</p></div><button class="workbench__btn" @click="runPreflight">重新预检</button></header>
      <div><article v-for="version in workflowDetail.versions" :key="version.id"><strong>v{{ version.version }}</strong><span class="workbench__tag">{{ version.lifecycleState }}</span><p>{{ version.changeSummary || '未填写变更说明' }}</p><small>{{ time(version.createdTime) }} · {{ version.operatorUsername || 'system' }} · {{ version.fingerprint.slice(0,12) }}…</small><button v-if="version.lifecycleState !== 'ACTIVE'" class="workbench__btn" @click="activateVersion(version.version)">启用</button></article></div>
      <div v-if="preflight" class="workflow-preflight" :class="{ invalid: !preflight.valid }"><strong>{{ preflight.valid ? '预检通过' : '预检未通过' }}</strong><span>{{ preflight.nodeCount }} 个节点 · {{ preflight.executionMode }} · 平台写入 {{ preflight.platformWrite }}</span><ul v-if="preflight.blockers?.length"><li v-for="blocker in preflight.blockers" :key="blocker">{{ blocker }}</li></ul></div>
    </section>

    <section class="workbench__card workflow-runs">
      <header><div><h2>任务中心</h2><p>列表只读取摘要；打开详情时才加载逐节点与事件，支持 1000 条以上记录分页。</p></div><div><select v-model="runStatus" class="workbench__select" aria-label="运行状态筛选" @change="runPage=1; loadRuns()"><option v-for="option in statusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><button class="workbench__btn" @click="loadRuns">刷新</button></div></header>
      <div class="run-table"><button v-for="run in runs" :key="run.id" @click="openRun(run.id)"><span>#{{ run.id }}</span><div><strong>{{ run.workflowName }}</strong><small>{{ accountName(run.accountId) }} · v{{ run.version }} · {{ run.requestId }}</small></div><span class="workbench__tag" :class="statusClass(run.status)">{{ statusLabel(run.status) }}</span><span>{{ run.succeededCount }}/{{ run.nodeCount }} 节点</span><small>{{ time(run.updatedTime) }}</small></button><div v-if="!runs.length" class="workbench__empty">当前筛选没有工作流运行记录</div></div>
      <footer><span>共 {{ runTotal }} 条，第 {{ runPage }}/{{ pageCount }} 页</span><div><button class="workbench__btn" :disabled="runPage<=1" @click="runPage--; loadRuns()">上一页</button><button class="workbench__btn" :disabled="runPage>=pageCount" @click="runPage++; loadRuns()">下一页</button></div></footer>
    </section>

    <div v-if="runDialogOpen" class="run-dialog" @click.self="closeRunDialog">
      <article ref="runDialog" role="dialog" aria-modal="true" aria-labelledby="workflow-run-dialog-title" tabindex="-1"><header><div><small>工作流运行 360 档案 · #{{ runDetail?.id || '-' }}</small><h2 id="workflow-run-dialog-title">{{ runDetail?.workflowName || '读取中…' }}</h2></div><button aria-label="关闭工作流运行档案" @click="closeRunDialog">×</button></header>
        <main><template v-if="runDetail"><section class="run-summary"><div><span>状态</span><strong>{{ statusLabel(runDetail.status) }}</strong></div><div><span>版本</span><strong>v{{ runDetail.version }}</strong></div><div><span>执行模式</span><strong>{{ runDetail.executionMode }}</strong></div><div><span>平台写入</span><strong>{{ runDetail.platformWrite }}</strong></div></section>
          <section><h3>节点执行</h3><div class="run-nodes"><article v-for="node in runDetail.nodes" :key="node.id"><span>{{ node.sequence }}</span><div><strong>{{ node.nodeName }}</strong><small>{{ node.nodeType }} · 尝试 {{ node.attemptCount }} 次</small><em v-if="node.error">{{ node.error }}</em></div><span class="workbench__tag" :class="statusClass(node.status)">{{ statusLabel(node.status) }}</span><small>补偿 {{ statusLabel(node.compensationStatus) }}</small><div v-if="node.status==='UNKNOWN'" class="run-node__resolve"><input v-model="unknownEvidence" class="workbench__input" placeholder="填写平台回执或人工核对证据"><button class="workbench__btn" @click="resolveUnknown(node.nodeId,'SUCCEEDED')">确认成功</button><button class="workbench__btn" @click="resolveUnknown(node.nodeId,'FAILED')">确认失败</button></div></article></div></section>
          <section><h3>事件时间线</h3><div class="run-events"><article v-for="event in runDetail.events" :key="event.id"><i></i><div><strong>{{ event.summary }}</strong><span>{{ event.eventType }} · {{ event.fromState || '—' }} → {{ event.toState }}</span><small>{{ time(event.createdTime) }} · {{ event.operatorUsername || 'system' }} · {{ event.requestId || '无请求 ID' }}</small></div></article></div></section>
        </template><div v-else class="workbench__empty">正在读取节点和事件证据…</div></main>
        <footer><span>{{ runDetail?.error || '所有动作均以独立请求 ID 记录审计。' }}</span><div><button v-if="runDetail && ['QUEUED','RUNNING'].includes(runDetail.status)" class="workbench__btn" :disabled="actioning" @click="performAction('cancel')">安全取消</button><button v-if="runDetail?.nodes.some(node=>node.status==='FAILED')" class="workbench__btn" :disabled="actioning" @click="performAction('retry')">仅重试失败节点</button><button v-if="runDetail && ['SUCCEEDED','PARTIAL','FAILED','CANCELLED'].includes(runDetail.status)" class="workbench__btn" :disabled="actioning" @click="performAction('compensate')">安全补偿</button><button class="workbench__btn workbench__btn--primary" @click="closeRunDialog">关闭</button></div></footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.workflow-boundary { display:flex; gap:12px; margin-bottom:12px; padding:10px 14px; border:1px solid #f5d565; border-radius:10px; color:#694b00; background:#fff9df; font-size:13px; }
.workflow-layout { display:grid; grid-template-columns:220px minmax(0,1fr) 270px; gap:12px; height:clamp(590px,66vh,760px); min-height:0; }
.workflow-list,.workflow-config { display:flex; min-height:0; flex-direction:column; gap:10px; overflow:hidden; }
.workflow-list__editor { display:flex; flex-direction:column; gap:8px; }.workflow-list__saved { display:flex; min-height:0; overflow:auto; overscroll-behavior:contain; flex:1; flex-direction:column; gap:7px; }
.workflow-list__saved button { display:flex; flex-direction:column; gap:3px; padding:10px; border:1px solid #eaecf0; border-radius:8px; background:#fff; text-align:left; cursor:pointer; }.workflow-list__saved button.active { border-color:#e7bd31; background:#fff9df; }.workflow-list__saved span,.workflow-list__saved small { color:#667085; font-size:11px; }
.workflow-main { display:grid; min-width:0; min-height:0; grid-template-rows:auto minmax(0,1fr); gap:10px; overflow:hidden; }.workflow-palette { display:flex; align-items:center; flex-wrap:wrap; gap:7px; padding:9px; }.workflow-palette em { color:#9a6200; font-size:12px; font-style:normal; }
.workflow-canvas { position:relative; min-height:0; overflow:auto; overscroll-behavior:contain; padding:0; touch-action:pan-x pan-y; background-color:#fbfcfe; background-image:linear-gradient(#eaecf0 1px,transparent 1px),linear-gradient(90deg,#eaecf0 1px,transparent 1px); background-size:20px 20px; }
.workflow-surface { position:relative; width:1200px; height:620px; }.workflow-surface svg { position:absolute; width:1200px; height:620px; pointer-events:none; }.workflow-surface line { stroke:#dfaa00; stroke-width:2; }.workflow-surface marker path { fill:#dfaa00; }
.workflow-node { position:absolute; display:flex; width:145px; min-height:80px; flex-direction:column; justify-content:center; gap:4px; padding:10px 12px; border:1px solid #efd77f; border-left:4px solid #9a6200; border-radius:9px; color:#344054; background:#fff; box-shadow:0 4px 12px rgba(16,24,40,.08); text-align:left; cursor:grab; touch-action:none; user-select:none; }.workflow-node span,.workflow-node small { color:#667085; font-size:10px; }.workflow-node.active { outline:3px solid rgba(214,155,0,.14); }.workflow-node.source { border-color:#079455; border-left-color:#079455; }
.workflow-config { overflow:auto; overscroll-behavior:contain; }.workflow-config__title { display:flex; align-items:center; justify-content:space-between; }.workflow-config h2 { margin:0; font-size:16px; }.workflow-config__title button,.workflow-edges button { border:0; color:#d92d20; background:transparent; cursor:pointer; }.workflow-switch { display:flex; justify-content:space-between; gap:10px; padding:10px; border:1px solid #eaecf0; border-radius:8px; }.workflow-switch span { display:flex; flex-direction:column; }.workflow-switch small { color:#667085; }.workflow-edges { display:flex; flex-direction:column; gap:7px; padding-top:8px; border-top:1px solid #eaecf0; font-size:11px; }.workflow-edges div { display:flex; justify-content:space-between; gap:6px; }
.workflow-versions,.workflow-runs { margin-top:12px; }.workflow-versions > header,.workflow-runs > header,.workflow-runs > footer { display:flex; align-items:center; justify-content:space-between; gap:12px; }.workflow-versions h2,.workflow-runs h2 { margin:0; font-size:17px; }.workflow-versions p,.workflow-runs p { margin:4px 0 0; color:#667085; font-size:12px; }.workflow-versions > div { display:flex; gap:8px; overflow:auto; padding-top:12px; }.workflow-versions article { display:grid; min-width:280px; grid-template-columns:auto auto 1fr auto; align-items:center; gap:8px; padding:10px; border:1px solid #eaecf0; border-radius:9px; }.workflow-versions article p,.workflow-versions article small { grid-column:1/-1; }.workflow-preflight { display:block!important; margin-top:12px; padding:10px 12px!important; border-radius:8px; color:#067647; background:#ecfdf3; }.workflow-preflight.invalid { color:#b42318; background:#fef3f2; }.workflow-preflight span { margin-left:10px; font-size:12px; }
.workflow-runs > header > div:last-child,.workflow-runs > footer > div { display:flex; gap:8px; }.run-table { margin-top:12px; border-top:1px solid #eaecf0; }.run-table > button { display:grid; width:100%; grid-template-columns:60px minmax(220px,1fr) 90px 100px 150px; align-items:center; gap:10px; padding:11px 4px; border:0; border-bottom:1px solid #eaecf0; background:#fff; text-align:left; cursor:pointer; }.run-table > button:hover { background:#fffcf0; }.run-table > button div { display:flex; min-width:0; flex-direction:column; }.run-table small { overflow:hidden; color:#667085; font-size:11px; text-overflow:ellipsis; white-space:nowrap; }.workflow-runs > footer { padding-top:12px; color:#667085; font-size:12px; }
.run-dialog { position:fixed; inset:0; z-index:1350; display:grid; place-items:center; padding:18px; background:rgba(16,24,40,.58); }.run-dialog > article { display:grid; width:min(1100px,100%); max-height:calc(100dvh - 36px); grid-template-rows:auto minmax(0,1fr) auto; overflow:hidden; border-radius:16px; background:#fff; box-shadow:0 24px 80px rgba(16,24,40,.24); }.run-dialog > article > header,.run-dialog > article > footer { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:15px 20px; border-bottom:1px solid #eaecf0; }.run-dialog > article > footer { border-top:1px solid #eaecf0; border-bottom:0; background:#fcfcfd; color:#667085; font-size:12px; }.run-dialog > article > header h2 { margin:3px 0 0; }.run-dialog > article > header small { color:#9a6200; }.run-dialog > article > header button { min-width:44px; min-height:44px; border:0; background:transparent; font-size:25px; cursor:pointer; }.run-dialog > article > main { min-height:0; overflow:auto; overscroll-behavior:contain; padding:4px 20px 24px; }.run-dialog main section { padding:18px 0; border-bottom:1px solid #eaecf0; }.run-dialog h3 { margin:0 0 12px; font-size:15px; }.run-dialog footer > div { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:7px; }
.run-summary { display:grid; grid-template-columns:repeat(4,1fr); gap:10px; border:0!important; }.run-summary div { padding:12px; border:1px solid #eaecf0; border-radius:9px; background:#fcfcfd; }.run-summary span,.run-summary strong { display:block; }.run-summary span { color:#667085; font-size:11px; }.run-summary strong { margin-top:5px; }
.run-nodes,.run-events { display:flex; flex-direction:column; gap:8px; }.run-nodes > article { display:grid; grid-template-columns:34px minmax(180px,1fr) 90px 130px; align-items:center; gap:10px; padding:10px; border:1px solid #eaecf0; border-radius:9px; }.run-nodes article > div { display:flex; min-width:0; flex-direction:column; }.run-nodes small,.run-nodes em { color:#667085; font-size:11px; font-style:normal; }.run-nodes em { color:#b42318; }.run-node__resolve { grid-column:2/-1; display:grid!important; grid-template-columns:1fr auto auto; gap:7px; }
.run-events article { display:grid; grid-template-columns:16px 1fr; gap:8px; }.run-events i { width:9px; height:9px; margin-top:4px; border:2px solid #fff; border-radius:50%; background:#dfaa00; box-shadow:0 0 0 2px #f5d565; }.run-events article div { display:flex; flex-direction:column; gap:3px; }.run-events span,.run-events small { color:#667085; font-size:11px; }
@media(max-width:1280px){.workflow-layout{grid-template-columns:200px minmax(0,1fr)}.workflow-config{grid-column:1/-1; max-height:300px}.workflow-layout{height:auto}.workflow-main{height:620px}}
@media(max-width:767px){.workflow-boundary,.workbench__header,.workflow-versions>header,.workflow-runs>header,.workflow-runs>footer{align-items:flex-start; flex-direction:column}.workflow-layout{display:flex; height:auto; flex-direction:column}.workflow-list{max-height:360px}.workflow-main{height:520px}.workflow-palette .workbench__btn{flex:1 0 42%}.workflow-canvas{height:430px}.workflow-config{max-height:none}.run-table>button{grid-template-columns:48px 1fr auto}.run-table>button>span:nth-of-type(3),.run-table>button>small{grid-column:2/-1}.run-dialog{padding:0}.run-dialog>article{width:100%; height:100dvh; max-height:none; border-radius:0}.run-dialog>article>footer{align-items:flex-start; flex-direction:column}.run-dialog footer>div{width:100%}.run-summary{grid-template-columns:1fr 1fr}.run-nodes>article{grid-template-columns:30px 1fr auto}.run-nodes>article>small,.run-node__resolve{grid-column:2/-1}.run-node__resolve{grid-template-columns:1fr}.workflow-metrics{grid-template-columns:1fr 1fr!important}}
</style>
