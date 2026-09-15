import service, { getAuthToken, request } from '@/utils/request'

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

export interface AccountRuntimeProfile {
  profileKey?: string
  profileType?: string
  platform?: string
  locale?: string
  timezoneId?: string
  viewportWidth?: number
  viewportHeight?: number
  viewport?: string
  deviceScaleFactor?: number
  colorScheme?: string
  browserVersion?: string
  browserStateReady: boolean
  storageStateUpdatedTime?: string
  status: 'ACTIVE' | 'DISABLED' | 'UNSYNCED'
  updatedTime?: string
}

export interface AccountDatasetEvidence {
  source: string
  syncStatus: string
  coverageStatus: CoverageStatus
  asOfTime?: string
  lastAttemptTime?: string
  lastSuccessTime?: string
  lastErrorCode?: string
  lastErrorMessage?: string
  requestId?: string
}

export interface MatrixAccount {
  accountId: number
  accountNote?: string
  unb?: string
  accountStatus?: number
  connectionStatus: string
  authorizationStatus: string
  connectionSource?: string
  connectionLastCheckedTime?: string
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
  runtimeProfileStatus?: string
  runtimeProfileType?: string
  runtimePlatform?: string
  runtimeViewport?: string
  browserStateReady?: boolean
  runtimeProfile?: AccountRuntimeProfile
  datasetEvidence?: {
    shopProfile: AccountDatasetEvidence
    shopRisks: AccountDatasetEvidence
  }
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

export interface BusinessAnalyticsScopes {
  accounts: Array<{ id: number; accountNote?: string }>
  groups: AccountGroup[]
  accountSummary: AccountMatrixSummary
}

export function getBusinessAnalytics(params: { start?: string; end?: string; accountId?: number; groupId?: number }) {
  return request<BusinessAnalyticsOverview>({ url: '/business-analytics/overview', method: 'GET', params })
}

export function getBusinessAnalyticsScopes() {
  return request<BusinessAnalyticsScopes>({ url: '/business-analytics/scopes', method: 'GET' })
}

export interface ProductFilter {
  search?: string
  accountIds?: number[]
  groupId?: number
  statusBucket?: string
  source?: string
  publishChannel?: string
  metricWindowDays?: 1 | 7 | 30
  page?: number
  pageSize?: number
}

export interface MatrixProduct {
  id: number
  goodsId: string
  outerId?: string
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
  warehouseStatus?: string
  fulfillmentMappingCount?: number
  rowVersion: number
  metric?: {
    windowDays: number
    sampleDays: number
    exposureCount?: number | null
    visitorCount?: number | null
    inquiryCount?: number | null
    paidOrderCount?: number | null
    coverageStatus: CoverageStatus
    dataDate?: string | null
    syncedAt?: string | null
  }
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

export interface ListingFormSchema {
  accountId: number
  listingType: 'VIRTUAL' | 'PHYSICAL' | 'SERVICE'
  source: string
  catalogVersion: string
  verificationStatus: string
  limits: { title: number; description: number; images: number; skuDimensions: number; skuCombinations: number }
  businessModes: Array<{ value: string; label: string; description: string }>
  conditions: Array<{ value: string; label: string; description: string }>
  shippingModes: Array<{ value: string; label: string; description: string }>
  industries: Array<{
    code: string
    name: string
    leafCategories: Array<{
      code: string
      name: string
      source: string
      attributes: Array<{ code: string; name: string; required: boolean; options: string[] }>
    }>
  }>
  serviceProtocols: Array<{ code: string; label: string; description: string; status: string }>
  typeRequirements: { fulfillmentFields: string[]; afterSalesFields: string[]; notice: string }
  channelCapabilities: Record<string, any>
  notice: string
}

export function getListingFormSchema(accountId: number, listingType: 'VIRTUAL' | 'PHYSICAL' | 'SERVICE') {
  return request<ListingFormSchema>({ url: `/publishing/accounts/${accountId}/form-schema`, method: 'GET', params: { listingType } })
}

export function getListingDrafts(accountId: number) {
  return request<Array<Record<string, any>>>({ url: `/publishing/accounts/${accountId}/drafts`, method: 'GET' })
}

export function createListingDraft(payload: Record<string, unknown>, requestId: string, changeSource: 'AUTO_SAVE' | 'MANUAL_SAVE' = 'MANUAL_SAVE') {
  return request<Record<string, any>>({ url: '/publishing/drafts', method: 'POST', data: { payload, requestId, changeSource } })
}

export function updateListingDraft(id: number, revision: number, payload: Record<string, unknown>, requestId: string, changeSource: 'AUTO_SAVE' | 'MANUAL_SAVE' = 'MANUAL_SAVE') {
  return request<Record<string, any>>({ url: `/publishing/drafts/${id}`, method: 'PUT', data: { revision, payload, requestId, changeSource } })
}

export function getListingDraftVersions(id: number) {
  return request<Array<Record<string, any>>>({ url: `/publishing/drafts/${id}/versions`, method: 'GET' })
}

export function validateListingDraft(payload: Record<string, unknown>) {
  return request<Record<string, any>>({ url: '/publishing/validate', method: 'POST', data: { payload } })
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
  idempotencyKey: string
  operationType: string
  selectionMode: 'EXPLICIT_IDS' | 'FILTER_SNAPSHOT'
  items?: ProductRef[]
  excludedItems?: ProductRef[]
  filter?: ProductFilter
  operationParams?: Record<string, unknown>
  maxOperationsPerMinute?: number
  confirmationText?: string
  previewToken?: string
}

export function previewProductBatch(data: ProductBatchRequest) {
  return request<Record<string, any>>({ url: '/product-matrix/batches/preview', method: 'POST', data })
}

export function createProductBatch(data: ProductBatchRequest) {
  const operation = data.operationType.toLowerCase().replace(/_/g, '-')
  return request<Record<string, any>>({ url: `/product-matrix/batches/${operation}/create`, method: 'POST', data })
}

export function getProductBatches(params?: { status?: string; operationType?: string; accountId?: number; operatorUserId?: number; search?: string; createdFrom?: string; createdTo?: string; limit?: number }) {
  return request<Array<Record<string, any>>>({ url: '/product-matrix/batches', method: 'GET', params: { ...params, limit: params?.limit || 50 } })
}

export function getProductBatch(jobId: number) {
  return request<Record<string, any>>({ url: `/product-matrix/batches/${jobId}`, method: 'GET' })
}

export function cancelProductBatch(jobId: number, requestId: string, reason: string) {
  return request<Record<string, any>>({ url: `/product-matrix/batches/${jobId}/cancel`, method: 'POST', data: { requestId, reason } })
}

export function retryProductBatch(jobId: number, operationType: string, itemIds: number[], requestId: string) {
  const operation = operationType.toLowerCase().replace(/_/g, '-')
  return request<Record<string, any>>({ url: `/product-matrix/batches/${operation}/${jobId}/retry`, method: 'POST', data: { requestId, itemIds } })
}

export function updateProductLocalDetails(accountId: number, goodsId: string, data: Record<string, unknown>) {
  return request<Record<string, any>>({ url: `/product-matrix/accounts/${accountId}/products/${goodsId}/local-details`, method: 'PUT', data })
}

export function updateProductAutomation(accountId: number, goodsId: string, data: Record<string, unknown>) {
  return request<Record<string, any>>({ url: `/product-matrix/accounts/${accountId}/products/${goodsId}/automation`, method: 'PUT', data })
}

export async function exportProductBatchFailures(jobId: number, requestId: string) {
  const response = await service.post(`/product-matrix/batches/${jobId}/failures/export`, { requestId }, {
    responseType: 'blob', headers: { Authorization: `Bearer ${getAuthToken() || ''}` }
  })
  return response.data as Blob
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

export interface ReturnShipmentCommand {
  requestId: string
  direction: 'BUYER_TO_SELLER' | 'SELLER_TO_BUYER'
  logisticsCompanyCode?: string
  logisticsCompanyName: string
  trackingNumber: string
  shipmentStatus: 'PENDING_PICKUP' | 'IN_TRANSIT' | 'DELIVERED' | 'RECEIVED' | 'EXCEPTION' | 'RETURNED'
  latestEvent?: string
  shippedTime?: string
  receivedTime?: string
  platformConfirmed: boolean
  confirmationText?: string
}

export function previewReturnShipment(refundCaseId: number, data: ReturnShipmentCommand) {
  return request<Record<string, any>>({ url: `/order-matrix/refunds/${refundCaseId}/return-shipment/preview`, method: 'POST', data })
}

export function recordReturnShipment(refundCaseId: number, data: ReturnShipmentCommand) {
  return request<Record<string, any>>({ url: `/order-matrix/refunds/${refundCaseId}/return-shipment/record`, method: 'POST', data })
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

export function getPublishingRequestStatus(requestId: string) {
  return request<Record<string, any>>({ url: `/publishing/requests/${encodeURIComponent(requestId)}`, method: 'GET' })
}

export function newRequestId(prefix = 'web') {
  return typeof crypto.randomUUID === 'function'
    ? `${prefix}-${crypto.randomUUID()}`
    : `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
