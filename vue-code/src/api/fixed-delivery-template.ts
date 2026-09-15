import { request } from '@/utils/request'

export interface FixedDeliveryTemplate {
  id: number
  xianyuAccountId: number
  xianyuAccountIds: number[]
  templateVersion: number
  referenceCount?: number
  templateName: string
  deliveryContent: string
  messageTemplate: string
  createTime: string
  updateTime: string
}

export interface SaveFixedDeliveryTemplateReq {
  id?: number
  xianyuAccountId: number
  xianyuAccountIds: number[]
  templateName: string
  deliveryContent: string
  messageTemplate: string
  requestId: string
}

export interface FixedTemplatePreview {
  renderedContent: string
  length: number
  maxLength: number
  templateVersion?: number | null
  variables: Record<string, string>
  writePerformed: boolean
}

export interface FixedTemplateReference {
  configId: number
  accountId: number
  goodsId: string
  skuId?: string | null
  skuName?: string | null
}

export interface FixedTemplateVersion {
  templateVersion: number
  requestId: string
  operatorUsername?: string | null
  createdTime: string
  deliveryContentLength: number
  messageTemplateLength: number
  accountIdsJson: string
}

export function getFixedDeliveryTemplates(xianyuAccountId: number, keyword?: string) {
  return request<FixedDeliveryTemplate[]>({
    url: '/fixed-delivery-template/list',
    method: 'GET',
    params: { xianyuAccountId, keyword: keyword || undefined }
  })
}

export function saveFixedDeliveryTemplate(data: SaveFixedDeliveryTemplateReq) {
  return request<FixedDeliveryTemplate>({
    url: '/fixed-delivery-template/save',
    method: 'POST',
    data
  })
}

export function deleteFixedDeliveryTemplate(xianyuAccountId: number, id: number, requestId: string) {
  return request<void>({
    url: '/fixed-delivery-template/delete',
    method: 'POST',
    params: { xianyuAccountId, id, requestId }
  })
}

export function previewFixedDeliveryTemplate(data: {
  xianyuAccountId: number
  templateId?: number
  buyerName?: string
  orderId?: string
  deliveryContent?: string
  messageTemplate?: string
}) {
  return request<FixedTemplatePreview>({ url: '/fixed-delivery-template/preview', method: 'POST', data })
}

export function getFixedTemplateReferences(xianyuAccountId: number, id: number) {
  return request<FixedTemplateReference[]>({
    url: '/fixed-delivery-template/references', method: 'GET', params: { xianyuAccountId, id }
  })
}

export function getFixedTemplateVersions(xianyuAccountId: number, id: number) {
  return request<FixedTemplateVersion[]>({
    url: '/fixed-delivery-template/versions', method: 'GET', params: { xianyuAccountId, id }
  })
}
