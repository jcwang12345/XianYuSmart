import { request } from '@/utils/request'

export type CoverageStatus = 'FULL' | 'PARTIAL' | 'UNSYNCED'

export interface AccountGroup {
  id: number
  groupName: string
  color?: string
  description?: string
  sortOrder?: number
  accountCount?: number
  accountIdsCsv?: string
}

export interface MatrixAccount {
  accountId: number
  accountNote?: string
  unb?: string
  accountStatus?: number
  connectionStatus: string
  authorizationStatus: string
  credentialExpireTime?: string
  shopNickname?: string
  shopLevel?: string
  profileSource: string
  profileSyncStatus: string
  profileCoverageStatus: CoverageStatus
  profileSyncedAt?: string
  riskSource: string
  riskSyncStatus: string
  riskCoverageStatus: CoverageStatus
  riskSyncedAt?: string
  knownActiveRiskCount?: number
  highestRiskSeverity?: string
  groups: AccountGroup[]
  profile?: Record<string, unknown>
  accessChannels?: Array<Record<string, unknown>>
  risks?: Array<Record<string, unknown>>
}

export interface AccountMatrixSummary {
  accountCount: number
  connectedCount: number
  attentionAccountCount: number
  unsyncedProfileCount: number
  knownRiskAccountCount: number
  knownActiveRiskCount: number | null
  riskCoverage: CoverageStatus
  generatedAt: string
}

export interface MatrixPage<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

export function getAccountMatrixSummary() {
  return request<AccountMatrixSummary>({ url: '/account-matrix/summary', method: 'GET' })
}

export function queryAccountMatrix(params: {
  search?: string
  connectionStatus?: string
  riskSeverity?: string
  groupId?: number
  page?: number
  pageSize?: number
}) {
  return request<MatrixPage<MatrixAccount>>({ url: '/account-matrix/accounts', method: 'GET', params })
}

export function getAccountMatrixDetail(accountId: number) {
  return request<MatrixAccount>({ url: `/account-matrix/accounts/${accountId}`, method: 'GET' })
}

export function getAccountGroups() {
  return request<AccountGroup[]>({ url: '/account-groups', method: 'GET' })
}

export function saveAccountGroup(data: Partial<AccountGroup> & { groupName: string; requestId: string }) {
  return request<AccountGroup>({ url: '/account-groups', method: 'POST', data })
}

export function replaceAccountGroupMembers(groupId: number, accountIds: number[], requestId: string) {
  return request<void>({ url: `/account-groups/${groupId}/members`, method: 'PUT', data: { accountIds, requestId } })
}

export interface AnalyticsAggregate {
  gmv: number | null
  paidOrderCount: number | null
  paidBuyerCount: number | null
  refundAmount: number | null
  refundOrderCount: number | null
  exposureCount: number | null
  visitorCount: number | null
  inquiryCount: number | null
  repliedInquiryCount: number | null
  activeProductCount: number | null
  averageOrderValue: number | null
  refundRate: number | null
  replyRate: number | null
  visitRate: number | null
  inquiryRate: number | null
  paymentRate: number | null
  source: string
  syncStatus: string
  coverageStatus: CoverageStatus
  coveredAccountCount: number
  requestedAccountCount: number
  sampleDays: number
  sampleSize: number
  syncedAt?: string
}

export interface BusinessAnalyticsOverview {
  range: { start: string; end: string; days: number }
  scope: { accountId?: number | ''; groupId?: number | ''; accountCount: number }
  summary: AnalyticsAggregate
  previous: AnalyticsAggregate
  trend: Array<Record<string, unknown>>
  funnel: Record<string, number | null | string>
  shopRank: Array<Record<string, unknown>>
  productRank: Array<Record<string, unknown>>
  anomalies: Array<Record<string, unknown>>
  productAnomalies: Array<Record<string, unknown>>
  definitions: Record<string, string>
  generatedAt: string
}

export function getBusinessAnalytics(params: { start?: string; end?: string; accountId?: number; groupId?: number }) {
  return request<BusinessAnalyticsOverview>({ url: '/business-analytics/overview', method: 'GET', params })
}

export interface ProductFilter {
  search?: string
  accountIds?: number[]
  statusBucket?: string
  source?: string
  publishChannel?: string
  page?: number
  pageSize?: number
}

export interface MatrixProduct {
  id: number
  goodsId: string
  accountId: number
  accountNote?: string
  accountUnb?: string
  title?: string
  coverPic?: string
  price?: number
  stock?: number
  skuCount?: number
  status?: number
  statusBucket: string
  source: string
  publishChannel?: string
  syncStatus: string
  coverageStatus: CoverageStatus
  lastSyncedTime?: string
  lastSyncErrorMessage?: string
}

export interface ProductMatrixPage extends MatrixPage<MatrixProduct> {
  statusCounts: Record<string, number>
  summary: Record<string, number | null>
  summaryScope: string
  dataNotice: string
}

export function queryProductMatrix(filter: ProductFilter) {
  return request<ProductMatrixPage>({ url: '/product-matrix/products/query', method: 'POST', data: filter })
}

export function getProductMatrixDetail(accountId: number, goodsId: string) {
  return request<Record<string, any>>({ url: `/product-matrix/accounts/${accountId}/products/${goodsId}`, method: 'GET' })
}

export function getSavedProductFilters() {
  return request<Array<Record<string, any>>>({ url: '/product-matrix/filters', method: 'GET' })
}

export function saveProductFilter(data: { name: string; filter: ProductFilter; requestId: string }) {
  return request<Record<string, any>>({ url: '/product-matrix/filters', method: 'POST', data })
}

export interface ProductRef { accountId: number; goodsId: string }
export interface ProductBatchRequest {
  requestId: string
  operationType: string
  selectionMode: 'EXPLICIT' | 'FILTER_SNAPSHOT'
  items?: ProductRef[]
  filter?: ProductFilter
  operationParams?: Record<string, unknown>
  maxOperationsPerMinute?: number
  confirmationText?: string
}

export function previewProductBatch(data: ProductBatchRequest) {
  return request<Record<string, any>>({ url: '/product-matrix/batches/preview', method: 'POST', data })
}

export function createProductBatch(data: ProductBatchRequest) {
  const operation = data.operationType.toLowerCase().replace(/_/g, '-')
  return request<Record<string, any>>({ url: `/product-matrix/batches/${operation}/create`, method: 'POST', data })
}

export function getProductBatches(status?: string) {
  return request<Array<Record<string, any>>>({ url: '/product-matrix/batches', method: 'GET', params: { status, limit: 20 } })
}

export interface OrderFilter {
  search?: string
  accountIds?: number[]
  orderStatus?: string
  deliveryStatus?: string
  refundStatus?: string
  startDate?: string
  endDate?: string
  page?: number
  pageSize?: number
}

export interface MatrixOrder {
  orderRecordId: number
  accountId: number
  accountName?: string
  orderId: string
  buyerName?: string
  goodsId?: string
  goodsTitle?: string
  goodsCover?: string
  amount: number | null
  currency?: string
  orderStatus?: string
  deliveryStatus?: string
  refundStatus?: string
  flag?: string
  note?: string
  hasException?: boolean
  dataSource?: string
  syncStatus?: string
  coverageStatus?: CoverageStatus
  lastSyncedTime?: string
  createdTime?: string
}

export interface OrderMatrixPage extends MatrixPage<MatrixOrder> {
  summary: Record<string, number | null | string>
  dataset: Record<string, unknown>
  dataNotice: string
}

export function queryOrderMatrix(filter: OrderFilter) {
  return request<OrderMatrixPage>({ url: '/order-matrix/orders/query', method: 'POST', data: filter })
}

export function getOrderMatrixDetail(orderRecordId: number) {
  return request<Record<string, any>>({ url: `/order-matrix/orders/${orderRecordId}`, method: 'GET' })
}

export function updateOrderNote(orderRecordId: number, data: { requestId: string; flag: string; note: string }) {
  return request<Record<string, any>>({ url: `/order-matrix/orders/${orderRecordId}/note`, method: 'PUT', data })
}

export function getPublishingCapabilities(accountId: number) {
  return request<Record<string, any>>({ url: `/publishing/accounts/${accountId}/capabilities`, method: 'GET' })
}

export function preflightPublish(data: Record<string, unknown>) {
  return request<Record<string, any>>({ url: '/publishing/preflight', method: 'POST', data })
}

export function executePublish(data: Record<string, unknown>) {
  return request<Record<string, any>>({ url: '/publishing/execute', method: 'POST', data })
}

export function newRequestId(prefix = 'web') {
  return typeof crypto.randomUUID === 'function'
    ? `${prefix}-${crypto.randomUUID()}`
    : `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
