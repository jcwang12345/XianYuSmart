import { request } from '@/utils/request';

export interface KamiConfig {
  id: number;
  xianyuAccountId: number;
  xianyuAccountIds: number[];
  sharingMode: 'PRIVATE' | 'SHARED';
  configVersion?: number;
  aliasName: string;
  sourceType?: 'LOCAL' | 'API';
  externalApiUrl?: string;
  externalApiHeaders?: string;
  externalApiHeadersConfigured?: boolean;
  externalApiBody?: string;
  externalApiBodySensitiveConfigured?: boolean;
  externalApiResultPath?: string;
  externalApiTimeoutSeconds?: number;
  externalDailyQuota?: number;
  externalFailureThreshold?: number;
  externalCooldownSeconds?: number;
  externalCircuitState?: 'CLOSED' | 'OPEN' | 'HALF_OPEN';
  externalConsecutiveFailures?: number;
  externalCircuitOpenedAt?: string;
  externalQuotaUsed?: number;
  alertEnabled?: number;
  alertThresholdType?: number;
  alertThresholdValue?: number;
  alertEmail?: string;
  totalCount: number;
  usedCount: number;
  availableCount: number;
  reservedCount?: number;
  reviewRequiredCount?: number;
  createTime: string;
  updateTime: string;
}

export interface KamiItem {
  id: number;
  kamiConfigId: number;
  kamiContent: string;
  status: number;
  orderId: string | null;
  reservedAccountId?: number | null;
  reservationExpireTime?: string | null;
  sourceConfigVersion?: number | null;
  rowVersion?: number;
  usedTime: string | null;
  sortOrder: number;
  createTime: string;
}

export interface SaveKamiConfigReq {
  id?: number;
  xianyuAccountId: number;
  xianyuAccountIds?: number[];
  sharingMode?: 'PRIVATE' | 'SHARED';
  aliasName?: string;
  sourceType?: 'LOCAL' | 'API';
  externalApiUrl?: string;
  externalApiHeaders?: string;
  externalApiBody?: string;
  externalApiResultPath?: string;
  externalApiTimeoutSeconds?: number;
  externalDailyQuota?: number;
  externalFailureThreshold?: number;
  externalCooldownSeconds?: number;
  requestId?: string;
  alertEnabled?: number;
  alertThresholdType?: number;
  alertThresholdValue?: number;
  alertEmail?: string;
}

export interface QueryKamiItemsReq {
  kamiConfigId: number;
  status?: number;
  keyword?: string;
}

export function saveKamiConfig(data: SaveKamiConfigReq) {
  return request<KamiConfig>({
    url: '/kami-config/save',
    method: 'POST',
    data
  });
}

export function getKamiConfigsByAccountId(xianyuAccountId: number) {
  return request<KamiConfig[]>({
    url: '/kami-config/list',
    method: 'POST',
    params: { xianyuAccountId }
  });
}

export function getKamiConfigById(id: number) {
  return request<KamiConfig>({
    url: '/kami-config/detail',
    method: 'POST',
    params: { id }
  });
}

export function deleteKamiConfig(id: number, requestId: string) {
  return request({
    url: '/kami-config/delete',
    method: 'POST',
    params: { id, requestId }
  });
}

export function addKamiItem(data: { kamiConfigId: number; kamiContent: string; requestId: string }) {
  return request<KamiItem>({
    url: '/kami-config/item/add',
    method: 'POST',
    data
  });
}

export function batchImportKamiItems(data: { kamiConfigId: number; kamiContents: string; requestId: string }) {
  return request<number>({
    url: '/kami-config/item/batchImport',
    method: 'POST',
    data
  });
}

export function getKamiItemsByConfigId(kamiConfigId: number) {
  return request<KamiItem[]>({
    url: '/kami-config/item/list',
    method: 'POST',
    params: { kamiConfigId }
  });
}

export function queryKamiItems(data: QueryKamiItemsReq) {
  return request<KamiItem[]>({
    url: '/kami-config/item/query',
    method: 'POST',
    data
  });
}

export function deleteKamiItem(id: number, requestId: string) {
  return request({
    url: '/kami-config/item/delete',
    method: 'POST',
    params: { id, requestId }
  });
}

export function resetKamiItem(id: number, requestId: string) {
  return request({
    url: '/kami-config/item/reset',
    method: 'POST',
    params: { id, requestId }
  });
}

export interface KamiInventoryEvent {
  id: number;
  kamiItemId?: number | null;
  accountId?: number | null;
  orderId?: string | null;
  eventType: string;
  outcomeState: string;
  requestId: string;
  reservationToken?: string | null;
  configVersion?: number | null;
  quantity: number;
  beforeJson?: string | null;
  afterJson?: string | null;
  source: string;
  operatorUsername?: string | null;
  createdTime: string;
}

export interface KamiInventoryEventPage {
  configId: number;
  configVersion?: number;
  page: number;
  pageSize: number;
  total: number;
  events: KamiInventoryEvent[];
}

export interface ExternalSupplyRequest {
  id: number;
  accountId: number;
  orderId: string;
  quantity: number;
  requestStatus: string;
  resultUnknown: number;
  attemptCount: number;
  errorMessage?: string | null;
  nextRetryTime?: string | null;
  circuitStateAtRequest?: string | null;
  quotaUsedAfter?: number | null;
  resolutionDecision?: string | null;
  resolutionNote?: string | null;
  resolutionRequestId?: string | null;
  resolvedTime?: string | null;
  createTime: string;
  updateTime: string;
  attachedReviewCount: number;
}

export interface ExternalSupplyRequestPage {
  configId: number;
  page: number;
  pageSize: number;
  total: number;
  requests: ExternalSupplyRequest[];
  secretFieldsReturned: false;
  dataNotice: string;
}

export interface ExternalResolutionPreview {
  externalRequestId: number;
  configId: number;
  accountId: number;
  orderId: string;
  quantity: number;
  decision: 'CONFIRMED_SUPPLIED' | 'CONFIRMED_NOT_SUPPLIED';
  requiredCardCount: number;
  platformWrite: 'NOT_PERFORMED';
  confirmationText: string;
  effect: string;
}

export function exportKamiItems(data: { kamiConfigId: number; includeUnused: boolean; includeUsed: boolean; requestId: string }) {
  return request<KamiItem[]>({
    url: '/kami-config/item/export',
    method: 'POST',
    data
  });
}

export function getKamiInventoryEvents(kamiConfigId: number, page = 1, pageSize = 20) {
  return request<KamiInventoryEventPage>({
    url: `/kami-config/${kamiConfigId}/events`,
    method: 'GET',
    params: { page, pageSize }
  });
}

export function resetExternalSupplyCircuit(kamiConfigId: number, requestId: string) {
  return request<KamiConfig>({
    url: '/kami-config/external/circuit/reset',
    method: 'POST',
    data: { kamiConfigId, requestId }
  });
}

export function getExternalSupplyRequests(kamiConfigId: number, status = 'ALL', page = 1, pageSize = 20) {
  return request<ExternalSupplyRequestPage>({
    url: `/kami-config/${kamiConfigId}/external/requests`,
    method: 'GET',
    params: { status, page, pageSize }
  });
}

export function previewExternalSupplyResolution(externalRequestId: number,
  decision: 'CONFIRMED_SUPPLIED' | 'CONFIRMED_NOT_SUPPLIED') {
  return request<ExternalResolutionPreview>({
    url: `/kami-config/external/requests/${externalRequestId}/resolution-preview`,
    method: 'POST',
    data: { decision }
  });
}

export function resolveExternalSupplyRequest(externalRequestId: number, data: {
  decision: 'CONFIRMED_SUPPLIED' | 'CONFIRMED_NOT_SUPPLIED';
  confirmationText: string;
  cardContents: string[];
  note?: string;
  requestId: string;
}) {
  return request<{ idempotentReplay: boolean; externalRequestId: number; requestStatus: string;
    attachedReviewCount: number; automaticBuyerSend: false; requestId: string }>({
    url: `/kami-config/external/requests/${externalRequestId}/resolve`,
    method: 'POST',
    data
  });
}
