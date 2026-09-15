import { request } from '@/utils/request'

export type GrowthResourceType = 'MATERIAL' | 'SUPPLY'
export type WorkflowRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED' | 'UNKNOWN'

export interface ResourceEvidence {
  type?: string
  url?: string
  itemId?: string
  capturedAt?: string
  authorizationStatus?: string
}

export interface ResourceVersion {
  id: number
  version: number
  lifecycleState: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  requestId?: string
  requestFingerprint?: string
  payloadFingerprint: string
  payload?: Record<string, any>
  source: ResourceEvidence
  license?: { type?: string; note?: string }
  supplierName?: string
  validFrom?: string
  validUntil?: string
  validityState: string
  operatorUsername?: string
  createdTime?: string
  duplicateCount: number
  referenceCount: number
  mappingCount: number
  readiness: { ready: boolean; blockers: string[] }
}

export interface GoodsMapping {
  id?: number
  accountId: number
  goodsId: string
  skuId?: string
  status?: 'ACTIVE' | 'INACTIVE'
  validFrom?: string
  validUntil?: string
  validityState?: string
}

export interface GrowthResource {
  id: number
  resourceType: GrowthResourceType
  name: string
  status: number
  accountId?: number
  accountIds: number[]
  goodsId?: string
  stock: number
  amount: number | null
  payload: Record<string, any>
  version?: ResourceVersion
  versions?: ResourceVersion[]
  goodsMappings?: GoodsMapping[]
  createdTime?: string
  updatedTime?: string
  idempotentReplay?: boolean
}

export interface SearchSnapshot {
  id: number
  requestId: string
  requestFingerprint: string
  searchType: 'KEYWORD' | 'PRICE_COMPARE' | 'SHOP'
  accountId: number
  query: string
  filters: Record<string, any>
  evidence: {
    source?: string
    authorizationStatus?: string
    collectedAt?: string
    expiresAt?: string
    freshness: 'FRESH' | 'STALE' | 'UNKNOWN'
    sampleCount?: number | null
    reportedTotal?: number | null
    duplicateCount?: number | null
  }
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED'
  result: {
    items?: Array<Record<string, any>>
    pageNumber?: number
    pageSize?: number
    hasMore?: boolean
    reportedTotal?: number | null
    priceEvidence?: {
      pricedSampleCount: number
      unpricedSampleCount: number
      minimum?: number | null
      median?: number | null
      maximum?: number | null
      coverageStatus: string
    }
    notice?: string
  }
  error?: string
  operatorUsername?: string
  createdTime?: string
  updatedTime?: string
  idempotentReplay?: boolean
}

export interface WorkflowDefinitionVersion {
  id: number
  version: number
  lifecycleState: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  requestId?: string
  requestFingerprint?: string
  fingerprint: string
  definition: { nodes: WorkflowNode[]; edges: WorkflowEdge[]; executionBoundary?: string }
  changeSummary?: string
  operatorUsername?: string
  activationRequestId?: string
  publishedTime?: string
  createdTime?: string
}

export interface WorkflowNode {
  id: string
  type: 'TRIGGER' | 'SEARCH' | 'FILTER' | 'COLLECT' | 'MATERIAL' | 'PUBLISH'
  name: string
  x?: number
  y?: number
  config: Record<string, any>
}

export interface WorkflowEdge { source: string; target: string }

export interface GrowthWorkflow {
  id: number
  name: string
  status: number
  accountId: number
  versionCount?: number
  runCount?: number
  currentVersion?: Partial<WorkflowDefinitionVersion> | null
  versions?: WorkflowDefinitionVersion[]
  createdTime?: string
  updatedTime?: string
  idempotentReplay?: boolean
}

export interface WorkflowRunSummary {
  id: number
  workflowId: number
  workflowName: string
  versionId: number
  version: number
  accountId: number
  requestId: string
  executionMode: 'DRY_RUN' | 'QA_MOCK'
  status: WorkflowRunStatus
  currentNodeId?: string
  cancelRequested: boolean
  manualDispatch: boolean
  error?: string
  operatorUsername?: string
  nodeCount: number
  succeededCount: number
  failedCount: number
  unknownCount: number
  pendingCount: number
  cancelledCount: number
  eventCount: number
  createdTime?: string
  updatedTime?: string
  completedTime?: string
  platformWrite: 'NOT_PERFORMED'
}

export interface WorkflowRunDetail extends WorkflowRunSummary {
  input: Record<string, any>
  output: Record<string, any>
  nodes: Array<Record<string, any>>
  events: Array<Record<string, any>>
  idempotentReplay: boolean
}

export interface PageResult<T> {
  items: T[]
  total: number
  pageNumber: number
  pageSize: number
  hasMore: boolean
  source: string
  syncedAt: string
}

export const getGrowthResources = (type: GrowthResourceType, status?: number) =>
  request<GrowthResource[]>({ url: '/growth-workspace/resources', method: 'GET', params: { type, status } })

export const getGrowthResource = (resourceId: number) =>
  request<GrowthResource>({ url: `/growth-workspace/resources/${resourceId}`, method: 'GET' })

export const saveGrowthResourceVersion = (data: Record<string, any>) =>
  request<GrowthResource>({ url: '/growth-workspace/resources/versions', method: 'POST', data })

export const activateGrowthResourceVersion = (resourceId: number, version: number, requestId: string) =>
  request<GrowthResource>({ url: `/growth-workspace/resources/${resourceId}/versions/${version}/activate`, method: 'POST', data: { requestId } })

export const createGrowthSearch = (data: Record<string, any>) =>
  request<SearchSnapshot>({ url: '/growth-workspace/searches', method: 'POST', data })

export const getGrowthSearches = (params: { accountId?: number; searchType?: string; limit?: number } = {}) =>
  request<SearchSnapshot[]>({ url: '/growth-workspace/searches', method: 'GET', params })

export const getGrowthWorkflows = () =>
  request<GrowthWorkflow[]>({ url: '/growth-workspace/workflows', method: 'GET' })

export const getGrowthWorkflow = (workflowId: number) =>
  request<GrowthWorkflow>({ url: `/growth-workspace/workflows/${workflowId}`, method: 'GET' })

export const saveGrowthWorkflowVersion = (data: Record<string, any>) =>
  request<GrowthWorkflow>({ url: '/growth-workspace/workflows/versions', method: 'POST', data })

export const activateGrowthWorkflowVersion = (workflowId: number, version: number, requestId: string) =>
  request<GrowthWorkflow>({ url: `/growth-workspace/workflows/${workflowId}/versions/${version}/activate`, method: 'POST', data: { requestId } })

export const preflightGrowthWorkflow = (workflowId: number, data: Record<string, any>) =>
  request<Record<string, any>>({ url: `/growth-workspace/workflows/${workflowId}/preflight`, method: 'POST', data })

export const createGrowthWorkflowRun = (data: Record<string, any>) =>
  request<WorkflowRunDetail>({ url: '/growth-workspace/workflow-runs', method: 'POST', data })

export const getGrowthWorkflowRuns = (params: Record<string, any> = {}) =>
  request<PageResult<WorkflowRunSummary>>({ url: '/growth-workspace/workflow-runs', method: 'GET', params })

export const getGrowthWorkflowRun = (runId: number) =>
  request<WorkflowRunDetail>({ url: `/growth-workspace/workflow-runs/${runId}`, method: 'GET' })

export const cancelGrowthWorkflowRun = (runId: number, requestId: string) =>
  request<WorkflowRunDetail>({ url: `/growth-workspace/workflow-runs/${runId}/cancel`, method: 'POST', data: { requestId } })

export const retryGrowthWorkflowRun = (runId: number, requestId: string, nodeIds: string[] = []) =>
  request<WorkflowRunDetail>({ url: `/growth-workspace/workflow-runs/${runId}/retry-failed`, method: 'POST', data: { requestId, nodeIds } })

export const compensateGrowthWorkflowRun = (runId: number, requestId: string) =>
  request<WorkflowRunDetail>({ url: `/growth-workspace/workflow-runs/${runId}/compensate`, method: 'POST', data: { requestId } })

export const resolveUnknownGrowthWorkflowNode = (runId: number, nodeId: string, data: Record<string, any>) =>
  request<WorkflowRunDetail>({ url: `/growth-workspace/workflow-runs/${runId}/nodes/${encodeURIComponent(nodeId)}/resolve-unknown`, method: 'POST', data })
